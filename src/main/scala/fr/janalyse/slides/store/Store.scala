package fr.janalyse.slides.store

import fr.janalyse.slides.AppConfig
import fr.janalyse.slides.model.*
import zio.*
import zio.lmdb.*
import zio.stream.*

/** The database, single source of truth : decks and their content, settings, git histories. The search index, the thumbnails and the PDF exports are derived from it. */
class Store(
  val lmdb: LMDB,
  decks: LMDBCollection[String, Deck],
  contents: LMDBCollection[String, DeckContent],
  settingsCollection: LMDBCollection[String, Settings],
  sourceStates: LMDBCollection[String, SourceState],
  histories: LMDBCollection[String, GitInfo],
  vectorMetas: LMDBCollection[String, VectorMeta],
  initialSettings: Settings
) {
  private val settingsKey = "settings"

  private def toThrowable(err: Any): Throwable = err match {
    case t: Throwable => t
    case other        => new RuntimeException(s"Database error : $other")
  }

  // ------------------------------------------------------------------------------------------------------------------
  def settings: Task[Settings] = settingsCollection.fetch(settingsKey).mapError(toThrowable).map(_.getOrElse(initialSettings))

  def saveSettings(settings: Settings): Task[Unit] = settingsCollection.upsertOverwrite(settingsKey, settings).mapError(toThrowable)

  // ------------------------------------------------------------------------------------------------------------------
  def deck(id: String): Task[Option[Deck]] = decks.fetch(id).mapError(toThrowable)

  def saveDeck(deck: Deck): Task[Unit] = decks.upsertOverwrite(deck.id, deck).mapError(toThrowable)

  def updateDeck(id: String)(modifier: Deck => Deck): Task[Option[Deck]] = decks.update(id, modifier).mapError(toThrowable)

  /** Saves a deck and its content together. */
  def saveDeckAndContent(deck: Deck, content: DeckContent): Task[Unit] =
    lmdb
      .readWrite { ops =>
        decks.lift(ops).upsertOverwrite(deck.id, deck) *> contents.lift(ops).upsertOverwrite(content.id, content)
      }
      .mapError(toThrowable)

  def deleteDeck(id: String): Task[Unit] =
    lmdb.readWrite(ops => decks.lift(ops).delete(id) *> contents.lift(ops).delete(id)).mapError(toThrowable).unit

  /** All decks, sorted by source then path. */
  def allDecks: Task[Chunk[Deck]] = decks.stream().mapError(toThrowable).runCollect

  def decksOf(source: String): Task[Chunk[Deck]] = decks.stream(keyFilter = _.startsWith(s"$source:")).mapError(toThrowable).runCollect

  def content(id: String): Task[Option[DeckContent]] = contents.fetch(id).mapError(toThrowable)

  def contentsStream: ZStream[Any, Throwable, DeckContent] = contents.stream().mapError(toThrowable)

  // ------------------------------------------------------------------------------------------------------------------
  def sourceState(name: String): Task[Option[SourceState]] = sourceStates.fetch(name).mapError(toThrowable)

  def saveSourceState(state: SourceState): Task[Unit] = sourceStates.upsertOverwrite(state.name, state).mapError(toThrowable)

  /** The git history of a deck, by its id. */
  def history(id: String): Task[Option[GitInfo]] = histories.fetch(id).mapError(toThrowable)

  /** Merges, or replaces, the histories of files, in one transaction. */
  def mergeHistories(changes: Map[String, GitInfo], replace: Boolean)(merge: (GitInfo, GitInfo) => GitInfo): Task[Unit] =
    ZIO
      .foreachDiscard(changes.toList.grouped(500).toList) { batch =>
        histories.readWrite { ops =>
          ZIO.foreachDiscard(batch) { (key, change) =>
            if (replace) ops.upsertOverwrite(key, change)
            else ops.upsert(key, previous => previous.map(merge(_, change)).getOrElse(change)).unit
          }
        }
      }
      .mapError(toThrowable)

  def clearHistories(source: String): Task[Unit] =
    histories
      .streamWithKeys(keyFilter = _.startsWith(s"$source:"))
      .map(_._1)
      .mapError(toThrowable)
      .runCollect
      .flatMap(keys => ZIO.foreachDiscard(keys)(key => histories.delete(key).mapError(toThrowable)))

  // ------------------------------------------------------------------------------------------------------------------
  def vectorMeta: Task[Option[VectorMeta]] = vectorMetas.fetch("current").mapError(toThrowable)

  def saveVectorMeta(meta: VectorMeta): Task[Unit] = vectorMetas.upsertOverwrite("current", meta).mapError(toThrowable)

  def dropCollection(name: String): Task[Unit] =
    lmdb.collectionExists(name).mapError(toThrowable).flatMap(exists => ZIO.when(exists)(lmdb.collectionDrop(name).mapError(toThrowable))).unit
}

object Store {
  private def create[T](lmdb: LMDB, name: String)(using LMDBCodec[T], zio.lmdb.schema.LMDBSchema[T]): Task[LMDBCollection[String, T]] =
    lmdb.collectionCreate[String, T](name, failIfExists = false).mapError(err => new RuntimeException(s"Unable to open the collection $name : $err"))

  val layer: ZLayer[AppConfig & Scope, Throwable, Store] =
    ZLayer.fromZIO(
      for {
        config       <- ZIO.service[AppConfig]
        _            <- ZIO.attemptBlocking(java.nio.file.Files.createDirectories(config.dataPath))
        lmdb         <- LMDBLive
                          .setup(
                            LMDBConfig.default.copy(
                              databaseName = "database",
                              databasesHome = Some(config.dataPath.toString),
                              mapSize = BigInt(20_000_000_000L),
                              maxCollections = 50
                            )
                          )
                          .mapError(err => new RuntimeException(s"Unable to open the database : $err"))
        decks        <- create[Deck](lmdb, "decks")
        contents     <- create[DeckContent](lmdb, "contents")
        settings     <- create[Settings](lmdb, "settings")
        sourceStates <- create[SourceState](lmdb, "source-states")
        histories    <- create[GitInfo](lmdb, "histories")
        vectorMetas  <- create[VectorMeta](lmdb, "vector-meta")
      } yield Store(lmdb, decks, contents, settings, sourceStates, histories, vectorMetas, Settings(config.defaultTheme))
    )
}
