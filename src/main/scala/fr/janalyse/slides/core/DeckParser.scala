package fr.janalyse.slides.core

import fr.janalyse.slides.model.Slide

import scala.util.Try
import scala.util.matching.Regex

/** Reads a reveal-md compatible markdown deck : its front matter, then its slides, separated by `---` lines (the next slide) and `----` lines (the next slide down, vertical
  * stacks). Unlike reveal-md, separators within fenced code blocks are left alone.
  *
  * Within a slide, a `Note:` line starts the speaker notes, and a `<!-- .slide: ... -->` comment gives the attributes of the slide.
  */
object DeckParser {

  /** Bumped when the parsing changes : the decks are read again. */
  val Version = 1

  /** @param stacks the slides, by horizontal position then vertical position */
  case class Parsed(frontMatter: Map[String, String], stacks: List[List[Slide]], title: String, subtitle: String) {
    def slides: List[Slide] = stacks.flatten
  }

  private val FenceStart     = """^\s{0,3}(`{3,}|~{3,}).*$""".r
  private val AtxHeading     = """^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$""".r
  private val NotesStart     = """(?i)^\s*notes?:(.*)$""".r
  private val SlideAttribute = """(?s)<!--\s*\.slide:\s*(.*?)\s*-->""".r
  private val FrontMatterKey = """^([A-Za-z0-9_.-]+)\s*:\s*(.*)$""".r
  private val DatePrefix     = """^(\d{4})-(\d{2})(?:-(\d{2}))?(?:[-_ ].*)?$""".r
  private val YearPrefix     = """^((?:19|20)\d{2})(?:[-_ ](?!\d{2}(?:[-_ ]|$)).*)?$""".r

  def parse(source: String, fallbackTitle: String): Parsed = {
    val text               = source.stripPrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
    val (frontMatter, body) = splitFrontMatter(text)
    val horizontal         = frontMatter.get("separator").flatMap(lineRegex).getOrElse("^---$".r)
    val vertical           = frontMatter.get("verticalSeparator").flatMap(lineRegex).getOrElse("^----$".r)
    val notes              = frontMatter.get("notesSeparator").flatMap(lineRegex)
    val raw                = splitSlides(body, horizontal, vertical)
    // a deck starting with a separator does not start with an empty slide
    val trimmed            = raw match {
      case List(blank) :: rest if blank.trim.isEmpty && rest.nonEmpty => rest
      case other                                                      => other
    }
    val stacks             = trimmed.zipWithIndex.map { (stack, h) => stack.zipWithIndex.map((markdown, v) => slide(h, v, markdown, notes)) }
    val first              = stacks.headOption.flatMap(_.headOption)
    val headings           = first.toList.flatMap(s => this.headings(s.markdown))
    val title              = frontMatter.get("title").map(clean).filter(_.nonEmpty).orElse(headings.headOption).getOrElse(humanize(fallbackTitle))
    val subtitle           = frontMatter.get("description").map(clean).filter(_.nonEmpty).orElse(headings.drop(1).headOption).getOrElse("")
    Parsed(frontMatter, if (stacks.isEmpty) List(List(slide(0, 0, "", notes))) else stacks, title, subtitle)
  }

  // ==================================================================================================================

  /** A separator given as a regular expression, as reveal-md takes it (`\r?\n---\r?\n`), turned into a whole line one. */
  private def lineRegex(expression: String): Option[Regex] = {
    val core = expression.trim.stripPrefix("^").stripSuffix("$").replace("\\r?\\n", "").replace("\\n", "").replace("\\r", "")
    if (core.isEmpty) None else Try(s"^(?:$core)$$".r).toOption.filter(r => Try(r.findFirstIn("")).isSuccess)
  }

  /** The YAML front matter, flattened (`revealOptions.transition`), and what follows it. Only simple values are read. */
  def splitFrontMatter(text: String): (Map[String, String], String) = {
    val lines = text.split("\n", -1).toList
    lines match {
      case first :: rest if first.trim == "---" =>
        val end = rest.indexWhere(line => line.trim == "---" || line.trim == "...")
        val yaml = if (end < 0) Nil else rest.take(end)
        val looksLikeYaml = yaml.nonEmpty && yaml.forall(line => line.trim.isEmpty || line.trim.startsWith("#") || FrontMatterKey.matches(line.trim) || line.startsWith(" ") || line.startsWith("\t") || line.trim.startsWith("- "))
        val firstIsKey = yaml.find(_.trim.nonEmpty).exists(line => !line.startsWith(" ") && FrontMatterKey.matches(line))
        if (end < 0 || !looksLikeYaml || !firstIsKey) (Map.empty, text)
        else (parseYaml(yaml), rest.drop(end + 1).mkString("\n"))
      case _ => (Map.empty, text)
    }
  }

  private def parseYaml(lines: List[String]): Map[String, String] = {
    var parents = List.empty[(Int, String)] // indentation and key of the enclosing mappings
    val values  = scala.collection.mutable.LinkedHashMap.empty[String, String]
    lines.filter(line => line.trim.nonEmpty && !line.trim.startsWith("#")).foreach { line =>
      val indent = line.takeWhile(c => c == ' ' || c == '\t').length
      parents = parents.dropWhile(_._1 >= indent)
      line.trim match {
        case FrontMatterKey(key, value) =>
          val path = (parents.reverse.map(_._2) :+ key).mkString(".")
          if (value.trim.isEmpty) parents = (indent, key) :: parents
          else values(path) = unquote(value.trim.replaceAll("""\s+#.*$""", ""))
        case _                          => ()
      }
    }
    values.toMap
  }

  private def unquote(value: String): String =
    if (value.length >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) value.substring(1, value.length - 1)
    else value

  /** Lines outside fenced code blocks are given to `f` with `true`. */
  private def withFences(lines: Iterable[String])(f: (String, Boolean) => Unit): Unit = {
    var fence: Option[String] = None
    lines.foreach { line =>
      fence match {
        case Some(marker) =>
          val trimmed = line.trim
          if (trimmed.startsWith(marker) && trimmed.forall(_ == marker.head)) fence = None
          f(line, false)
        case None         =>
          line match {
            case FenceStart(marker) =>
              fence = Some(marker)
              f(line, false)
            case _                  => f(line, true)
          }
      }
    }
  }

  private def splitSlides(body: String, horizontal: Regex, vertical: Regex): List[List[String]] = {
    val stacks  = scala.collection.mutable.ListBuffer.empty[List[String]]
    val stack   = scala.collection.mutable.ListBuffer.empty[String]
    val current = new StringBuilder
    def endSlide(): Unit = { stack += current.toString; current.clear() }
    withFences(body.split("\n", -1)) { (line, outside) =>
      val bare = line.stripTrailing
      if (outside && vertical.matches(bare)) endSlide()
      else if (outside && horizontal.matches(bare)) { endSlide(); stacks += stack.toList; stack.clear() }
      else current.append(line).append('\n')
    }
    endSlide()
    stacks += stack.toList
    stacks.toList
  }

  private def slide(h: Int, v: Int, raw: String, notesSeparator: Option[Regex]): Slide = {
    val attributes = SlideAttribute.findAllMatchIn(raw).map(_.group(1).trim).mkString(" ")
    val withoutAttributes = SlideAttribute.replaceAllIn(raw, "")
    // the speaker notes : from the first "Note:" line outside of the code blocks
    val lines      = withoutAttributes.split("\n", -1).toVector
    var notesAt    = -1
    var index      = 0
    withFences(lines) { (line, outside) =>
      if (notesAt < 0 && outside && (notesSeparator.exists(_.matches(line.stripTrailing)) || (notesSeparator.isEmpty && NotesStart.matches(line)))) notesAt = index
      index += 1
    }
    val (content, notes) =
      if (notesAt < 0) (withoutAttributes, "")
      else {
        val first = lines(notesAt) match {
          case NotesStart(rest) if notesSeparator.isEmpty => rest
          case _                                          => ""
        }
        (lines.take(notesAt).mkString("\n"), (first +: lines.drop(notesAt + 1)).mkString("\n").trim)
      }
    val markdown = content.replaceAll("""^\n+""", "").replaceAll("""\s+$""", "")
    val title    = headings(markdown).headOption.getOrElse(firstLine(markdown))
    Slide(h, v, markdown, notes, attributes, title, plainText(markdown))
  }

  /** The headings of a slide, as text. */
  def headings(markdown: String): List[String] = {
    val found = scala.collection.mutable.ListBuffer.empty[String]
    withFences(markdown.split("\n", -1)) { (line, outside) =>
      if (outside) line match {
        case AtxHeading(_, text) if clean(text).nonEmpty => found += clean(text)
        case _                                           => ()
      }
    }
    found.toList
  }

  private def firstLine(markdown: String): String =
    markdown.split("\n").iterator.map(clean).find(_.length > 1).map(line => if (line.length > 80) line.take(79) + "…" else line).getOrElse("")

  /** A markdown line as plain text : without links targets, images, html tags and emphasis. */
  def clean(text: String): String =
    text
      .replaceAll("""(?s)<!--.*?-->""", " ")
      .replaceAll("""!\[([^\]]*)\]\([^)]*\)""", "$1")
      .replaceAll("""\[([^\]]*)\]\([^)]*\)""", "$1")
      .replaceAll("""\[\[([^\]|]*)(\|[^\]]*)?\]\]""", "$1")
      .replaceAll("""<[^>]+>""", " ")
      .replaceAll("""(\*\*|__|~~|==|`)""", "")
      .replaceAll("""(?<![\w*])[*_](?=\S)|(?<=\S)[*_](?![\w*])""", "")
      .replaceAll("""&nbsp;""", " ")
      .replaceAll("""\s+""", " ")
      .trim

  /** The text of a slide for the search : code blocks content is kept, the markdown syntax removed. */
  def plainText(markdown: String): String = {
    val out = new StringBuilder
    withFences(markdown.replaceAll("""(?s)<!--.*?-->""", " ").split("\n", -1)) { (line, outside) =>
      val text =
        if (!outside) {
          // the language of a code block is searchable : ```mermaid, ```scala
          if (FenceStart.matches(line)) line.trim.dropWhile(c => c == '`' || c == '~').takeWhile(c => c.isLetterOrDigit || c == '-' || c == '+')
          else if (line.trim.matches("""^(`{3,}|~{3,})$""")) ""
          else line.trim
        }
        else clean(line.replaceAll("""^\s{0,3}#{1,6}\s+""", "").replaceAll("""^\s*([-*+]|\d+[.)])\s+(\[[ xX]\]\s+)?""", "").replaceAll("""^\s*>\s?""", ""))
      if (text.nonEmpty) out.append(text).append('\n')
    }
    out.toString.trim
  }

  /** `2024-09-side-projects-experience-returns-2` becomes `Side projects experience returns 2`. */
  def humanize(name: String): String = {
    val base  = name.stripSuffix(".md").stripSuffix(".markdown").replaceFirst("""^\d{4}-\d{2}(-\d{2})?[-_ ]?""", "")
    val words = base.replaceAll("[-_]+", " ").trim
    if (words.isEmpty) name else s"${words.head.toUpper}${words.tail}"
  }

  /** The date a name tells, `2024-02`, `2024-02-06`, or only its year `2024`. */
  def dateOf(name: String): Option[String] =
    name match {
      case DatePrefix(year, month, day) if month.toInt >= 1 && month.toInt <= 12 =>
        Some(if (day != null && day.toInt >= 1 && day.toInt <= 31) s"$year-$month-$day" else s"$year-$month")
      case YearPrefix(year)                                                        => Some(year)
      case _                                                                      => None
    }
}
