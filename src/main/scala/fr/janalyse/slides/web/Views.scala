package fr.janalyse.slides.web

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import fr.janalyse.slides.live.{LiveMessage, LiveState, RoomInfo}
import fr.janalyse.slides.model.*
import fr.janalyse.slides.pipeline.{AppEvent, PipelineStatus}
import fr.janalyse.slides.render.Theme
import fr.janalyse.slides.search.SearchResults

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

/** A deck as listed.
  *
  * @param base
  *   the URL its relative links and pictures are resolved against
  * @param thumbnail
  *   the URL of its thumbnail, None when not drawn (yet) : the user interface draws its first slide itself
  */
case class DeckSummary(
  id: String,
  source: String,
  path: String,
  directory: String,
  fileName: String,
  title: String,
  subtitle: String,
  date: Option[String],
  sortKey: String,
  slideCount: Int,
  stackCount: Int,
  created: Option[Instant],
  updated: Instant,
  commits: Int,
  base: String,
  assetsStamp: String,
  thumbnail: Option[String],
  theme: Option[String]
)

/** A directory of decks : its main deck (index.md) and the others. */
case class PresentationView(key: String, source: String, directory: String, title: String, sortKey: String, main: DeckSummary, others: List[DeckSummary])

case class RoomView(id: String, deckId: String, title: String, state: LiveState, followers: Int, startedAt: Instant)

case class Catalog(presentations: List[PresentationView], live: List[RoomView], sources: List[String])

case class ClientConfig(
  slideWidth: Int,
  slideHeight: Int,
  theme: String,
  themes: List[Theme],
  customCss: String,
  browser: Boolean,
  embeddings: Boolean,
  listenHost: String
)

case class DeckView(deck: DeckSummary, slides: List[Slide], theme: String, frontMatter: Map[String, String], others: List[DeckSummary])

case class SettingsUpdate(theme: Option[String] = None, customCss: Option[String] = None)

case class LiveStart(deckId: String, h: Int = 0, v: Int = 0, f: Int = -1)

case class LiveStarted(room: String, token: String, deckId: String)

case class LiveMove(token: String, h: Int, v: Int, f: Int = -1, overview: Boolean = false, paused: Boolean = false)

case class LiveStop(token: String)

case class Message(message: String)

/** The addresses participants can join from, when listening on every interface. */
case class ShareInfo(urls: List[String])

object Views {
  given JsonValueCodec[Catalog]         = JsonCodecMaker.make(CodecMakerConfig.withTransientEmpty(false).withTransientNone(true))
  given JsonValueCodec[DeckView]        = JsonCodecMaker.make(CodecMakerConfig.withTransientEmpty(false))
  given JsonValueCodec[ClientConfig]    = JsonCodecMaker.make(CodecMakerConfig.withTransientEmpty(false))
  given JsonValueCodec[SearchResults]   = JsonCodecMaker.make(CodecMakerConfig.withTransientEmpty(false))
  given JsonValueCodec[PipelineStatus]  = JsonCodecMaker.make(CodecMakerConfig.withTransientEmpty(false))
  given JsonValueCodec[AppEvent]        = JsonCodecMaker.make
  given JsonValueCodec[LiveMessage]     = JsonCodecMaker.make(CodecMakerConfig.withTransientEmpty(false).withTransientDefault(false))
  given JsonValueCodec[List[RoomView]]  = JsonCodecMaker.make(CodecMakerConfig.withTransientEmpty(false).withTransientDefault(false))
  given JsonValueCodec[RoomView]        = JsonCodecMaker.make(CodecMakerConfig.withTransientEmpty(false).withTransientDefault(false))
  given JsonValueCodec[SettingsUpdate]  = JsonCodecMaker.make
  given JsonValueCodec[Settings]        = JsonCodecMaker.make(CodecMakerConfig.withTransientEmpty(false).withTransientDefault(false))
  given JsonValueCodec[LiveStart]       = JsonCodecMaker.make
  given JsonValueCodec[LiveStarted]     = JsonCodecMaker.make
  given JsonValueCodec[LiveMove]        = JsonCodecMaker.make
  given JsonValueCodec[LiveStop]        = JsonCodecMaker.make
  given JsonValueCodec[Message]         = JsonCodecMaker.make
  given JsonValueCodec[ShareInfo]       = JsonCodecMaker.make(CodecMakerConfig.withTransientEmpty(false))

  private def encodePath(path: String): String =
    path.split('/').filter(_.nonEmpty).map(part => URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20")).mkString("/")

  def base(deck: Deck): String = {
    val directory = encodePath(deck.directory)
    s"/files/${encodePath(deck.source)}/${if (directory.isEmpty) "" else directory + "/"}"
  }

  def summary(deck: Deck, thumbnail: Boolean): DeckSummary =
    DeckSummary(
      id = deck.id,
      source = deck.source,
      path = deck.path,
      directory = deck.directory,
      fileName = deck.fileName,
      title = deck.title,
      subtitle = deck.subtitle,
      date = deck.date,
      sortKey = deck.sortKey,
      slideCount = deck.slideCount,
      stackCount = deck.stackCount,
      created = deck.git.map(_.createdAt),
      updated = deck.git.map(_.updatedAt).getOrElse(deck.lastModified),
      commits = deck.git.map(_.commits).getOrElse(0),
      base = base(deck),
      assetsStamp = deck.assetsStamp,
      thumbnail = deck.thumbnailKey.filter(_ => thumbnail).map(key => s"/api/thumbnail?id=${URLEncoder.encode(deck.id, StandardCharsets.UTF_8)}&k=$key"),
      theme = deck.frontMatter.get("theme").map(_.stripSuffix(".css").split('/').last)
    )

  /** The decks grouped by directory, the most recent first ; within a presentation `index.md` first. */
  def presentations(decks: List[DeckSummary]): List[PresentationView] =
    decks
      .groupBy(deck => (deck.source, deck.directory))
      .toList
      .map { case ((source, directory), all) =>
        val sorted = all.sortBy(deck => (!deck.fileName.equalsIgnoreCase("index.md"), deck.fileName.toLowerCase))
        val main   = sorted.head
        PresentationView(s"$source:$directory", source, directory, main.title, all.map(_.sortKey).max, main, sorted.tail)
      }
      .sortBy(p => (p.sortKey, p.directory))
      .reverse
}
