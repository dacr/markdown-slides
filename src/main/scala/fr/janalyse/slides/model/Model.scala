package fr.janalyse.slides.model

import zio.lmdb.json.LMDBCodecJson

import java.time.Instant

/** What the git history tells about a file. */
case class GitInfo(createdAt: Instant, updatedAt: Instant, commits: Int, authors: List[String] = Nil) derives LMDBCodecJson

/** One slide of a deck, as the player shows it.
  *
  * @param h
  *   horizontal position, from 0
  * @param v
  *   vertical position within its stack, from 0
  * @param markdown
  *   the slide content, without its speaker notes and its `<!-- .slide: -->` attributes
  * @param attributes
  *   the raw attributes of its `<!-- .slide: ... -->` comment, `data-background="#000" class="dark"`
  * @param title
  *   its first heading, or its first line of text
  * @param text
  *   its plain text, without the markdown syntax, for the search
  */
case class Slide(h: Int, v: Int, markdown: String, notes: String, attributes: String, title: String, text: String) derives LMDBCodecJson

/** A markdown file of a source, a reveal-md compatible deck.
  *
  * @param id
  *   `<source>:<path>`, keys sort by source then path
  * @param path
  *   relative to the source root, `2024-02-reveals-md/index.md`
  * @param directory
  *   the presentation it belongs to, the directory of the file, relative to the source root, empty for the root
  * @param date
  *   `2024-02` or `2024-02-06`, from the directory or file name, or the front matter
  * @param assetsStamp
  *   changes whenever a file of the directory, an image, changes : the views reload them
  * @param thumbnailKey
  *   what the stored thumbnail was drawn from, None without thumbnail
  * @param embeddingKey
  *   what the stored embeddings were computed from, None without embeddings
  * @param parserVersion
  *   the version of the parser which read it : a newer parser reads it again
  */
case class Deck(
  id: String,
  source: String,
  path: String,
  directory: String,
  fileName: String,
  title: String,
  subtitle: String,
  date: Option[String],
  frontMatter: Map[String, String],
  slideCount: Int,
  stackCount: Int,
  contentHash: String,
  size: Long,
  lastModified: Instant,
  assetsStamp: String = "",
  git: Option[GitInfo] = None,
  thumbnailKey: Option[String] = None,
  embeddingKey: Option[String] = None,
  parserVersion: Int = 0,
  discoveredAt: Instant,
  changedAt: Instant
) derives LMDBCodecJson {

  /** The deck shown for its presentation : `index.md` first. */
  def isIndex: Boolean = fileName.equalsIgnoreCase("index.md")

  /** When the deck was made : its date, or its first commit, or its file date. */
  def sortKey: String = date.getOrElse(git.map(_.createdAt).getOrElse(lastModified).toString.take(10))
}

/** The content of a deck, kept apart from its metadata which is read far more often. */
case class DeckContent(id: String, contentHash: String, markdown: String, slides: List[Slide]) derives LMDBCodecJson

/** Settings changed from the user interface. */
case class Settings(theme: String, customCss: String = "") derives LMDBCodecJson

case class SourceState(name: String, path: String, headCommit: Option[String]) derives LMDBCodecJson

case class VectorMeta(model: String, dimension: Int, collection: String) derives LMDBCodecJson
