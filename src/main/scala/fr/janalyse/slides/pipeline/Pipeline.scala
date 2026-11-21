package fr.janalyse.slides.pipeline

import fr.janalyse.slides.AppConfig
import fr.janalyse.slides.core.{DeckParser, SourceFile, Sources}
import fr.janalyse.slides.model.*
import fr.janalyse.slides.render.{Renderer, Themes}
import fr.janalyse.slides.search.{Embeddings, WordIndex}
import fr.janalyse.slides.search.Embeddings.digest
import fr.janalyse.slides.store.Store
import zio.*
import zio.stream.*

import java.nio.charset.{CharacterCodingException, StandardCharsets}
import java.nio.file.{Files, Path}
import java.time.Instant

/** Something the open views care about.
  *
  * @param kind
  *   deck-changed, deck-removed, assets-changed, thumbnail, theme-changed, settings-changed, catalog-changed
  */
case class AppEvent(kind: String, deckId: Option[String] = None, stamp: Option[String] = None)

case class SourceInfo(name: String, path: String, git: Boolean, decks: Int)

case class PipelineStatus(
  decks: Int,
  slides: Int,
  sources: List[SourceInfo],
  lastScan: Option[Instant],
  lastScanMillis: Long,
  scans: Long,
  thumbnailsPending: Int,
  embeddingsPending: Int,
  embeddingsEnabled: Boolean,
  embeddingsAvailable: Option[Boolean],
  embeddingsModel: String,
  vectors: Long,
  browserAvailable: Boolean,
  lastError: Option[String]
)

/** A source directory, known by a name unique among the sources. */
case class SourceRoot(name: String, path: Path)

/** A queue of decks to process in the background, each deck queued once. */
class WorkQueue(queue: Queue[String], pending: Ref[Set[String]]) {
  def offer(id: String): UIO[Unit]                = pending.modify(all => (!all.contains(id), all + id)).flatMap(added => ZIO.when(added)(queue.offer(id)).unit)
  def offerAll(ids: Iterable[String]): UIO[Unit]  = ZIO.foreachDiscard(ids)(offer)
  def take: UIO[String]                           = queue.take.tap(id => pending.update(_ - id))
  def size: UIO[Int]                              = pending.get.map(_.size)
}

object WorkQueue {
  def make: UIO[WorkQueue] = for { queue <- Queue.unbounded[String]; pending <- Ref.make(Set.empty[String]) } yield WorkQueue(queue, pending)
}

/** The synchronization : the sources are checked every few seconds, the changed decks are read again, saved in the database, indexed, and the open views told. Thumbnails and
  * embeddings follow, in the background. The git history gives the decks their dates.
  */
class Pipeline(
  config: AppConfig,
  store: Store,
  words: WordIndex,
  embeddings: Embeddings,
  renderer: Renderer,
  themes: Themes,
  hub: Hub[AppEvent],
  thumbnails: WorkQueue,
  vectors: WorkQueue,
  scanState: Ref[(Option[Instant], Long, Long)],
  themeStamps: Ref[Map[String, String]],
  lastError: Ref[Option[String]]
) {

  val roots: List[SourceRoot] = {
    val paths = config.sourcePaths.distinct
    paths.zipWithIndex.map { (path, index) =>
      val base = Option(path.getFileName).map(_.toString).getOrElse("root").toLowerCase.replaceAll("[^a-z0-9_-]", "-")
      SourceRoot(if (paths.count(p => Option(p.getFileName).map(_.toString.toLowerCase).contains(base)) > 1) s"$base-${index + 1}" else base, path)
    }
  }

  def root(name: String): Option[SourceRoot] = roots.find(_.name == name)

  def events: ZStream[Any, Nothing, AppEvent] = ZStream.fromHub(hub)

  private def publish(event: AppEvent): UIO[Unit] = hub.publish(event).unit

  private def fail(context: String)(err: Throwable): UIO[Unit] =
    lastError.set(Some(s"$context : ${err.getMessage}")) *> ZIO.logWarning(s"$context : ${err.getClass.getSimpleName} ${err.getMessage}")

  // ==================================================================================================================
  // Scan

  private def read(path: Path): Task[String] =
    ZIO.attemptBlocking {
      val bytes = Files.readAllBytes(path)
      try StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString
      catch { case _: CharacterCodingException => new String(bytes, StandardCharsets.ISO_8859_1) }
    }

  private def fallbackTitle(path: String): String = {
    val parts = path.split('/')
    if (parts.last.equalsIgnoreCase("index.md") && parts.length > 1) parts(parts.length - 2) else parts.last
  }

  /** The date a deck tells : its front matter `date:`, else the closest directory or its file name starting with yyyy-mm-dd,
    * yyyy-mm or yyyy ; None leaves the git history date it.
    */
  private def dateOf(frontMatter: Map[String, String], path: String): Option[String] =
    frontMatter.get("date").flatMap(DeckParser.dateOf).orElse(path.split('/').reverseIterator.flatMap(DeckParser.dateOf).nextOption())

  /** Reads a new or changed deck ; None when its content did not change. */
  private def load(source: SourceRoot, file: SourceFile, known: Option[Deck], now: Instant): Task[Option[(Deck, DeckContent)]] =
    read(file.absolute).map { markdown =>
      val hash = digest(markdown)
      if (known.exists(deck => deck.contentHash == hash && deck.parserVersion == DeckParser.Version)) None
      else {
        val parsed = DeckParser.parse(markdown, fallbackTitle(file.path))
        val id     = s"${source.name}:${file.path}"
        val name   = file.path.split('/').last
        val date   = dateOf(parsed.frontMatter, file.path)
        val deck   = Deck(
          id = id,
          source = source.name,
          path = file.path,
          directory = file.directory,
          fileName = name,
          title = parsed.title,
          subtitle = parsed.subtitle,
          date = date,
          frontMatter = parsed.frontMatter,
          slideCount = parsed.slides.size,
          stackCount = parsed.stacks.size,
          contentHash = hash,
          size = file.size,
          lastModified = file.lastModified,
          assetsStamp = known.map(_.assetsStamp).getOrElse(""),
          git = known.flatMap(_.git),
          thumbnailKey = known.flatMap(_.thumbnailKey),
          embeddingKey = known.flatMap(_.embeddingKey),
          parserVersion = DeckParser.Version,
          discoveredAt = known.map(_.discoveredAt).getOrElse(now),
          changedAt = now
        )
        Some((deck, DeckContent(id, hash, markdown, parsed.slides)))
      }
    }

  /** The stamp of the pictures and other files of each deck directory : a file belongs to the closest directory holding decks above it. */
  private def assetStamps(files: List[SourceFile]): Map[String, String] = {
    val deckDirectories = files.filter(_.isMarkdown).map(_.directory).toSet
    def owner(directory: String): Option[String] =
      Iterator.iterate(Option(directory))(_.flatMap(d => if (d.isEmpty) None else Some(Sources.directoryOf(d)))).takeWhile(_.isDefined).flatten.find(deckDirectories.contains)
    files
      .filterNot(_.isMarkdown)
      .flatMap(file => owner(file.directory).map(_ -> file))
      .groupBy(_._1)
      .view
      .mapValues(owned => digest(owned.map(_._2).sortBy(_.path).map(f => s"${f.path}|${f.size}|${f.lastModified.toEpochMilli}").mkString("\n")).take(12))
      .toMap
  }

  /** Checks a source for changes ; how many decks changed. */
  private def scanSource(source: SourceRoot): Task[Int] =
    for {
      now      <- Clock.instant
      files    <- ZIO.attemptBlocking(Sources.list(source.path, Sources.matchers(config.excludePatterns)))
      known    <- store.decksOf(source.name).map(_.map(deck => deck.path -> deck).toMap)
      markdown  = files.filter(_.isMarkdown)
      changed   = markdown.filter(file => known.get(file.path).forall(deck => deck.size != file.size || deck.lastModified != file.lastModified || deck.parserVersion != DeckParser.Version))
      loaded   <- ZIO.foreach(changed) { file =>
                    load(source, file, known.get(file.path), now).map(file -> _).catchAll(err => fail(s"Unable to read ${file.absolute}")(err).as(file -> None))
                  }
      updated  <- ZIO.foreach(loaded) {
                    case (_, Some((deck, content))) =>
                      for {
                        git <- store.history(deck.id).map(_.orElse(deck.git))
                        d    = deck.copy(git = git)
                        _   <- store.saveDeckAndContent(d, content)
                        _   <- words.index(d, content)
                        _   <- publish(AppEvent("deck-changed", Some(d.id), Some(d.contentHash)))
                        _   <- thumbnails.offer(d.id) *> vectors.offer(d.id)
                      } yield 1
                    case (file, None)               =>
                      // same content, the file was only touched
                      ZIO.foreachDiscard(known.get(file.path))(deck => store.saveDeck(deck.copy(size = file.size, lastModified = file.lastModified))).as(0)
                  }
      present   = markdown.map(_.path).toSet
      removed   = known.values.filterNot(deck => present.contains(deck.path)).toList
      _        <- ZIO.foreachDiscard(removed) { deck =>
                    store.deleteDeck(deck.id) *> words.remove(deck.id) *> embeddings.remove(deck.id).ignore *> renderer.removeThumbnail(deck.id) *>
                      publish(AppEvent("deck-removed", Some(deck.id)))
                  }
      // pictures changed : the views reload them, the thumbnails are drawn again
      stamps    = assetStamps(files)
      current  <- store.decksOf(source.name)
      restamped = current.filter(deck => deck.assetsStamp != stamps.getOrElse(deck.directory, ""))
      _        <- ZIO.foreachDiscard(restamped) { deck =>
                    val stamp = stamps.getOrElse(deck.directory, "")
                    store.saveDeck(deck.copy(assetsStamp = stamp)) *> thumbnails.offer(deck.id) *>
                      ZIO.when(deck.assetsStamp.nonEmpty)(publish(AppEvent("assets-changed", Some(deck.id), Some(stamp))))
                  }
      // dates told by the names, also for the decks stored before the naming rules changed
      redated   = current.filter(deck => deck.date != dateOf(deck.frontMatter, deck.path))
      _        <- ZIO.foreachDiscard(redated) { deck =>
                    store.saveDeck(deck.copy(date = dateOf(deck.frontMatter, deck.path))) *> publish(AppEvent("deck-changed", Some(deck.id), Some(deck.contentHash)))
                  }
    } yield updated.sum + removed.size

  /** Theme files changed : the views reload them, the thumbnails are drawn again. */
  private def scanThemes: UIO[Unit] =
    for {
      all      <- themes.list
      stamps    = all.map(theme => theme.name -> theme.stamp).toMap
      previous <- themeStamps.getAndSet(stamps)
      changed   = stamps.filter((name, stamp) => previous.nonEmpty && !previous.get(name).contains(stamp)).keys
      _        <- ZIO.foreachDiscard(changed)(name => publish(AppEvent("theme-changed", stamp = Some(name))))
      _        <- ZIO.when(changed.nonEmpty || (previous.nonEmpty && previous.keySet != stamps.keySet))(publish(AppEvent("catalog-changed")) *> queueStaleThumbnails)
    } yield ()

  def scan: Task[Int] =
    for {
      started <- Clock.instant
      counts  <- ZIO.foreach(roots)(source => scanSource(source).catchAll(err => fail(s"Unable to scan ${source.path}")(err).as(0)))
      _       <- ZIO.when(counts.sum > 0)(words.refresh *> publish(AppEvent("catalog-changed")))
      _       <- scanThemes
      ended   <- Clock.instant
      _       <- scanState.update((_, _, count) => (Some(ended), java.time.Duration.between(started, ended).toMillis, count + 1))
    } yield counts.sum

  // ==================================================================================================================
  // Git history

  private def updateHistory(source: SourceRoot): Task[Unit] =
    for {
      state  <- store.sourceState(source.name)
      update <- ZIO.attemptBlocking(Sources.history(source.path, state.flatMap(_.headCommit)))
      _      <- ZIO.foreachDiscard(update) { update =>
                  val changes = update.files.map((path, info) => s"${source.name}:$path" -> info)
                  for {
                    _     <- ZIO.when(update.full)(store.clearHistories(source.name))
                    _     <- store.mergeHistories(changes, replace = update.full)(Sources.merge)
                    _     <- store.saveSourceState(SourceState(source.name, source.path.toString, Some(update.head)))
                    decks <- store.decksOf(source.name)
                    dated <- ZIO.foreach(decks)(deck => store.history(deck.id).map(git => deck -> git))
                    moved  = dated.collect { case (deck, git) if git != deck.git => deck.copy(git = git) }
                    _     <- ZIO.foreachDiscard(moved)(store.saveDeck)
                    _     <- ZIO.when(moved.nonEmpty)(publish(AppEvent("catalog-changed")))
                    _     <- ZIO.logInfo(s"Git history of ${source.name} read up to ${update.head.take(8)}, ${update.files.size} decks changed")
                  } yield ()
                }
    } yield ()

  // ==================================================================================================================
  // Background work

  def queueStaleThumbnails: UIO[Unit] =
    (for {
      decks <- store.allDecks
      stale <- ZIO.filter(decks.toList)(deck => renderer.lookKey(deck).map(key => !deck.thumbnailKey.contains(key) || !Files.exists(renderer.thumbnailFile(deck.id))))
      _     <- thumbnails.offerAll(stale.sortBy(_.sortKey).reverse.map(_.id))
    } yield ()).catchAll(fail("Unable to queue the thumbnails"))

  private def queueStaleVectors: UIO[Unit] =
    (for {
      decks <- store.allDecks
      _     <- vectors.offerAll(decks.filterNot(deck => deck.embeddingKey.contains(embeddings.keyOf(deck))).sortBy(_.sortKey).reverse.map(_.id))
    } yield ()).catchAll(fail("Unable to queue the embeddings"))

  private def drawThumbnail(id: String): UIO[Unit] =
    (for {
      deck <- store.deck(id)
      _    <- ZIO.foreachDiscard(deck) { deck =>
                renderer.lookKey(deck).flatMap { key =>
                  ZIO.unless(deck.thumbnailKey.contains(key) && Files.exists(renderer.thumbnailFile(id))) {
                    renderer.drawThumbnail(deck).flatMap { drawn =>
                      store.updateDeck(id)(_.copy(thumbnailKey = Some(drawn))) *> publish(AppEvent("thumbnail", Some(id), Some(drawn)))
                    }
                  }
                }
              }
    } yield ()).catchAll(fail(s"Unable to draw the thumbnail of $id"))

  private def computeVectors(id: String): Task[Unit] =
    for {
      deck    <- store.deck(id)
      content <- store.content(id)
      _       <- ZIO.foreachDiscard(deck.zip(content)) { (deck, content) =>
                   val key = embeddings.keyOf(deck)
                   ZIO.unless(deck.embeddingKey.contains(key))(embeddings.update(deck, content) *> store.updateDeck(id)(_.copy(embeddingKey = Some(key))))
                 }
    } yield ()

  private val thumbnailWorker: UIO[Nothing] =
    if (!renderer.available) ZIO.never
    // the browser draws the pages of the web server, started meanwhile
    else ZIO.sleep(3.seconds) *> thumbnails.take.flatMap(drawThumbnail).forever

  /** While the embeddings server is unavailable, the deck waits and is tried again later. */
  private val vectorWorker: UIO[Nothing] =
    if (!embeddings.enabled) ZIO.never
    else
      vectors.take.flatMap { id =>
        computeVectors(id).catchAll { err =>
          embeddings.isAvailable.flatMap { available =>
            (if (available.contains(false)) ZIO.logWarning(s"Embeddings server ${config.embeddings.baseUrl} unavailable (${err.getMessage}), tried again in a minute")
             else fail(s"Unable to compute the embeddings of $id")(err)) *> ZIO.sleep(1.minute) *> vectors.offer(id)
          }
        }
      }.forever

  // ==================================================================================================================

  /** The search index is rebuilt from the database, then the sources are scanned continuously. */
  def start: URIO[Scope, Unit] =
    for {
      _       <- ZIO.logInfo(s"Sources : ${roots.map(root => s"${root.name} = ${root.path}").mkString(", ")}")
      indexed <- store.contentsStream
                   .mapZIO(content => store.deck(content.id).flatMap(ZIO.foreachDiscard(_)(deck => words.index(deck, content))))
                   .runCount
                   .zipLeft(words.refresh)
                   .catchAll(err => fail("Unable to rebuild the search index")(err).as(0L))
      _       <- ZIO.logInfo(s"Search index rebuilt from $indexed decks")
      _       <- ZIO.when(!renderer.available)(ZIO.logWarning(s"No browser found (${config.browser}) : thumbnails drawn by the user interface, no PDF export"))
      _       <- (scan.catchAll(fail("Scan failed")) *> ZIO.sleep(config.scanIntervalSeconds.max(1).seconds)).forever.forkScoped
      _       <- ZIO.foreachDiscard(roots)(root => (updateHistory(root).catchAll(fail(s"Unable to read the git history of ${root.path}")) *> ZIO.sleep(30.seconds)).forever.forkScoped)
      // after the first scan, what was not done in a previous run
      _       <- (ZIO.sleep(5.seconds) *> queueStaleThumbnails *> queueStaleVectors).forkScoped
      _       <- thumbnailWorker.forkScoped
      _       <- vectorWorker.forkScoped
    } yield ()

  def notifySettingsChanged: UIO[Unit] = publish(AppEvent("settings-changed")) *> queueStaleThumbnails

  def status: Task[PipelineStatus] =
    for {
      decks     <- store.allDecks
      scanned   <- scanState.get
      thumbs    <- thumbnails.size
      pending   <- vectors.size
      available <- embeddings.isAvailable
      count     <- embeddings.count.orElseSucceed(0L)
      error     <- lastError.get
      sources    = roots.map(root => SourceInfo(root.name, root.path.toString, Sources.openRepository(root.path).map(_.close()).isDefined, decks.count(_.source == root.name)))
    } yield PipelineStatus(
      decks = decks.size,
      slides = decks.map(_.slideCount).sum,
      sources = sources,
      lastScan = scanned._1,
      lastScanMillis = scanned._2,
      scans = scanned._3,
      thumbnailsPending = thumbs,
      embeddingsPending = pending,
      embeddingsEnabled = embeddings.enabled,
      embeddingsAvailable = available,
      embeddingsModel = embeddings.model,
      vectors = count,
      browserAvailable = renderer.available,
      lastError = error
    )
}

object Pipeline {
  val layer: ZLayer[AppConfig & Store & WordIndex & Embeddings & Renderer & Themes, Nothing, Pipeline] =
    ZLayer.fromZIO(for {
      config      <- ZIO.service[AppConfig]
      store       <- ZIO.service[Store]
      words       <- ZIO.service[WordIndex]
      embeddings  <- ZIO.service[Embeddings]
      renderer    <- ZIO.service[Renderer]
      themes      <- ZIO.service[Themes]
      hub         <- Hub.sliding[AppEvent](4096)
      thumbnails  <- WorkQueue.make
      vectors     <- WorkQueue.make
      scanState   <- Ref.make((Option.empty[Instant], 0L, 0L))
      themeStamps <- Ref.make(Map.empty[String, String])
      lastError   <- Ref.make(Option.empty[String])
    } yield Pipeline(config, store, words, embeddings, renderer, themes, hub, thumbnails, vectors, scanState, themeStamps, lastError))
}
