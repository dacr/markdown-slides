package fr.janalyse.slides.render

import fr.janalyse.slides.AppConfig
import fr.janalyse.slides.model.*
import fr.janalyse.slides.search.Embeddings.digest
import fr.janalyse.slides.store.Store
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.{PDDocumentOutline, PDOutlineItem}
import zio.*

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.{Calendar, GregorianCalendar}
import javax.imageio.ImageIO
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/** The thumbnails and PDF exports of the decks, drawn by the headless browser from the pages of this application : they look exactly as the slides shown, with the current theme. */
class Renderer(config: AppConfig, store: Store, themes: Themes, browser: Browser, exporting: Ref.Synchronized[Map[String, Promise[Throwable, Path]]]) {

  /** Bumped when the drawing changes : the thumbnails are drawn again. */
  private val RenderVersion = "1"

  val thumbnailWidth = 480

  def available: Boolean = browser.available

  private def fileKey(deckId: String): String = digest(deckId).take(24)

  def thumbnailFile(deckId: String): Path = config.thumbnailsPath.resolve(s"${fileKey(deckId)}.png")

  /** What a deck looks like : its content, its pictures, the theme and the custom css. */
  def lookKey(deck: Deck): UIO[String] =
    for {
      settings <- store.settings.orElseSucceed(Settings(config.defaultTheme))
      theme    <- themes.resolve(deck.frontMatter.getOrElse("theme", settings.theme))
      stamp    <- themes.stamp(theme)
    } yield digest(List(RenderVersion, deck.contentHash, deck.assetsStamp, theme, stamp, settings.customCss, s"${config.slideWidth}x${config.slideHeight}").mkString("|")).take(20)

  private def encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

  /** Draws the first slide of the deck, then returns the key it was drawn with. */
  def drawThumbnail(deck: Deck): Task[String] =
    for {
      key   <- lookKey(deck)
      _     <- ZIO.attemptBlocking(Files.createDirectories(config.thumbnailsPath))
      shot   = config.thumbnailsPath.resolve(s"${fileKey(deck.id)}.shot.png")
      _     <- browser.screenshot(s"${config.selfUrl}/render?id=${encode(deck.id)}&k=$key", config.slideWidth, config.slideHeight, shot)
      _     <- ZIO.attemptBlocking {
                 val image  = ImageIO.read(shot.toFile)
                 val height = (thumbnailWidth.toDouble * image.getHeight / image.getWidth).round.toInt
                 val scaled = new BufferedImage(thumbnailWidth, height, BufferedImage.TYPE_INT_RGB)
                 val g      = scaled.createGraphics()
                 g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                 g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                 g.drawImage(image, 0, 0, thumbnailWidth, height, null)
                 g.dispose()
                 val temporary = config.thumbnailsPath.resolve(s"${fileKey(deck.id)}.tmp.png")
                 ImageIO.write(scaled, "png", temporary.toFile)
                 Files.move(temporary, thumbnailFile(deck.id), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                 Files.deleteIfExists(shot)
               }
    } yield key

  def removeThumbnail(deckId: String): UIO[Unit] = ZIO.attemptBlocking(Files.deleteIfExists(thumbnailFile(deckId))).ignore

  // ==================================================================================================================
  // PDF

  /** The PDF export of a deck, printed when not already done for its current look ; concurrent requests for the same export wait for the same printing. */
  def pdf(deck: Deck, content: DeckContent): Task[Path] =
    for {
      key     <- lookKey(deck)
      file     = config.exportsPath.resolve(s"${fileKey(deck.id)}-$key.pdf")
      exists  <- ZIO.attemptBlocking(Files.isRegularFile(file))
      result  <- if (exists) ZIO.succeed(file)
                 else
                   exporting.modifyZIO { running =>
                     running.get(file.toString) match {
                       case Some(promise) => ZIO.succeed((promise, running))
                       case None          =>
                         for {
                           promise <- Promise.make[Throwable, Path]
                           _       <- print(deck, content, key, file)
                                        .intoPromise(promise)
                                        .ensuring(exporting.update(_ - file.toString))
                                        .forkDaemon
                         } yield (promise, running + (file.toString -> promise))
                     }
                   }.flatMap(_.await)
    } yield result

  private def print(deck: Deck, content: DeckContent, key: String, file: Path): Task[Path] =
    for {
      _       <- ZIO.attemptBlocking(Files.createDirectories(config.exportsPath))
      printed  = config.exportsPath.resolve(s"${fileKey(deck.id)}-$key.printed.pdf")
      started <- Clock.instant
      _       <- browser.pdf(s"${config.selfUrl}/print?id=${encode(deck.id)}&k=$key", printed)
      _       <- ZIO.attemptBlocking(finish(deck, content, printed, file))
      took    <- Clock.instant.map(now => java.time.Duration.between(started, now).toMillis)
      _       <- ZIO.logInfo(s"PDF export of ${deck.id} printed in ${took}ms")
    } yield file

  /** The document information and an outline of the slides, then the previous exports of the deck are removed. */
  private def finish(deck: Deck, content: DeckContent, printed: Path, file: Path): Unit = {
    Using.resource(Loader.loadPDF(printed.toFile)) { document =>
      val info = document.getDocumentInformation
      info.setTitle(deck.title)
      if (deck.subtitle.nonEmpty) info.setSubject(deck.subtitle)
      if (config.pdfAuthor.nonEmpty) info.setAuthor(config.pdfAuthor)
      info.setCreator("markdown-slides")
      info.setKeywords(deck.path)
      val calendar = new GregorianCalendar()
      calendar.setTimeInMillis(deck.lastModified.toEpochMilli)
      info.setModificationDate(calendar: Calendar)
      val pages    = document.getPages.asScala.toVector
      val slides   = content.slides.toVector
      if (pages.size == slides.size && pages.nonEmpty) {
        val outline = new PDDocumentOutline()
        slides.zip(pages).foreach { (slide, page) =>
          val item        = new PDOutlineItem()
          val destination = new PDPageFitDestination()
          destination.setPage(page)
          item.setDestination(destination)
          item.setTitle(s"${slide.h + 1}${if (slide.v > 0) s".${slide.v + 1}" else ""} ${if (slide.title.nonEmpty) slide.title else deck.title}")
          outline.addLast(item)
        }
        document.getDocumentCatalog.setDocumentOutline(outline)
      }
      val temporary = file.resolveSibling(file.getFileName.toString + ".tmp")
      document.save(temporary.toFile)
      Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    Files.deleteIfExists(printed)
    val prefix = s"${fileKey(deck.id)}-"
    Try(Using.resource(Files.list(config.exportsPath))(_.iterator().asScala.toList)).getOrElse(Nil).foreach { other =>
      val name = other.getFileName.toString
      if (name.startsWith(prefix) && name.endsWith(".pdf") && other != file) Files.deleteIfExists(other)
    }
  }

  /** The name a user downloads the export as. */
  def exportName(deck: Deck): String = {
    val base = if (deck.isIndex && deck.directory.nonEmpty) deck.directory.split('/').last else deck.fileName.stripSuffix(".md")
    base.replaceAll("[^A-Za-z0-9._-]", "-") + ".pdf"
  }
}

object Renderer {
  val layer: ZLayer[AppConfig & Store & Themes & Browser, Nothing, Renderer] = ZLayer.fromZIO(for {
    config    <- ZIO.service[AppConfig]
    store     <- ZIO.service[Store]
    themes    <- ZIO.service[Themes]
    browser   <- ZIO.service[Browser]
    exporting <- Ref.Synchronized.make(Map.empty[String, Promise[Throwable, Path]])
  } yield Renderer(config, store, themes, browser, exporting))
}
