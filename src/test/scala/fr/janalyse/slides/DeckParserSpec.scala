package fr.janalyse.slides

import fr.janalyse.slides.core.DeckParser
import zio.test.*

object DeckParserSpec extends ZIOSpecDefault {

  private val deck =
    """# reveal-md
      |## Web of slides in markdown
      |
      |---
      |
      |### Introduction
      |
      |- simple
      |
      |Note: say **hello**
      |first
      |
      |----
      |
      |### Below
      |
      |```markdown
      |---
      |not a separator
      |----
      |```
      |
      |---
      |<!-- .slide: data-background="#000" class="dark" -->
      |![](images/x.png)<!-- .element: width="900px" -->
      |
      |Some [link](other.md) text
      |""".stripMargin

  def spec = suite("DeckParser")(
    test("splits horizontal and vertical slides, leaving code blocks alone") {
      val parsed = DeckParser.parse(deck, "index.md")
      assertTrue(
        parsed.stacks.map(_.size) == List(1, 2, 1),
        parsed.slides.map(s => (s.h, s.v)) == List((0, 0), (1, 0), (1, 1), (2, 0)),
        parsed.stacks(1)(1).markdown.contains("not a separator"),
        parsed.stacks(1)(1).markdown.contains("----")
      )
    },
    test("gives the deck title and subtitle from the first slide") {
      val parsed = DeckParser.parse(deck, "index.md")
      assertTrue(parsed.title == "reveal-md", parsed.subtitle == "Web of slides in markdown")
    },
    test("extracts the speaker notes") {
      val slide = DeckParser.parse(deck, "index.md").stacks(1).head
      assertTrue(slide.notes == "say **hello**\nfirst", !slide.markdown.contains("Note:"), slide.title == "Introduction")
    },
    test("extracts the slide attributes and keeps the element ones") {
      val slide = DeckParser.parse(deck, "index.md").stacks(2).head
      assertTrue(
        slide.attributes == """data-background="#000" class="dark"""",
        !slide.markdown.contains(".slide:"),
        slide.markdown.contains(".element:"),
        slide.text == "Some link text"
      )
    },
    test("leaves the slide attributes of the code blocks as written") {
      val slide = DeckParser.parse(
        """### Backgrounds
          |
          |```markdown
          |<!-- .slide: data-background="#1c2430" -->
          |```
          |
          |<!-- .slide:
          |  class="real" -->
          |""".stripMargin,
        "talk.md"
      ).slides.head
      assertTrue(
        slide.attributes == """class="real"""",
        slide.markdown.contains("""<!-- .slide: data-background="#1c2430" -->"""),
        !slide.markdown.contains("real")
      )
    },
    test("leaves the task markers out of the searched text, Obsidian's in progress one too") {
      val slide = DeckParser.parse("- [ ] todo\n- [x] done\n- [/] doing\n", "talk.md").slides.head
      assertTrue(slide.text == "todo\ndone\ndoing")
    },
    test("reads the front matter, flattening nested values") {
      val parsed = DeckParser.parse(
        """---
          |title: "My talk"
          |revealOptions:
          |  transition: 'fade'
          |  showNotes: false
          |---
          |
          |# Ignored title
          |""".stripMargin,
        "talk.md"
      )
      assertTrue(
        parsed.title == "My talk",
        parsed.frontMatter == Map("title" -> "My talk", "revealOptions.transition" -> "fade", "revealOptions.showNotes" -> "false"),
        parsed.slides.size == 1
      )
    },
    test("supports custom separators from the front matter") {
      val parsed = DeckParser.parse(
        """---
          |separator: <!--s-->
          |verticalSeparator: <!--v-->
          |---
          |one
          |<!--s-->
          |two
          |<!--v-->
          |three
          |""".stripMargin,
        "x.md"
      )
      assertTrue(parsed.stacks.map(_.size) == List(1, 2))
    },
    test("a deck starting with a separator has no empty first slide") {
      val parsed = DeckParser.parse("---\n# First\n---\n# Second\n", "x.md")
      assertTrue(parsed.slides.map(_.title) == List("First", "Second"))
    },
    test("names a deck without heading after its directory") {
      val parsed = DeckParser.parse("![](images/title.jpg)\n\n---\n\n#### Introduction\n", "2026-10-26-agentic-security-audit-rex")
      assertTrue(parsed.title == "Agentic security audit rex")
    },
    test("keeps the code and its language searchable") {
      val text = DeckParser.plainText("### Charts\n\n```mermaid\npie title NETFLIX\n```\n\n- **bold** and `code`")
      assertTrue(text == "Charts\nmermaid\npie title NETFLIX\nbold and code")
    },
    test("tells the date of a name") {
      assertTrue(
        DeckParser.dateOf("2024-02-reveals-md").contains("2024-02"),
        DeckParser.dateOf("2025-01-28-home-nixification").contains("2025-01-28"),
        DeckParser.dateOf("2016-analysis").contains("2016"),
        DeckParser.dateOf("kafka-basics").isEmpty,
        DeckParser.dateOf("2024-13-x").isEmpty
      )
    }
  )
}
