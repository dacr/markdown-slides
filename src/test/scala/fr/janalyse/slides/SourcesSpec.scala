package fr.janalyse.slides

import fr.janalyse.slides.core.Sources
import zio.*
import zio.test.*

import java.nio.file.{Files, Path}

object SourcesSpec extends ZIOSpecDefault {

  private def write(root: Path, path: String, content: String = "x"): Unit = {
    val file = root.resolve(path)
    Files.createDirectories(file.getParent)
    Files.writeString(file, content)
  }

  private val tree = ZIO.acquireRelease(ZIO.attemptBlocking {
    val root = Files.createTempDirectory("markdown-slides-sources")
    write(root, "2024-02-talk/index.md", "# Talk")
    write(root, "2024-02-talk/images/logo.png")
    write(root, "2024-02-talk/draft.md", "# Draft")
    write(root, "2024-02-talk/node_modules/lib/README.md")
    write(root, ".hidden/secret.md")
    write(root, "build/out.md")
    write(root, ".gitignore", "build/\n*.bak\n")
    write(root, "notes.bak")
    root
  })(root => ZIO.attemptBlocking(Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))).orDie)

  def spec = suite("Sources")(
    test("lists the files, without hidden, ignored and excluded ones") {
      ZIO.scoped {
        tree.flatMap { root =>
          ZIO.attemptBlocking(Sources.list(root, Sources.matchers(List("**/node_modules/**")))).map { files =>
            val paths = files.map(_.path).sorted
            assertTrue(
              paths == List("2024-02-talk/draft.md", "2024-02-talk/images/logo.png", "2024-02-talk/index.md"),
              files.filter(_.isMarkdown).map(_.directory).distinct == List("2024-02-talk")
            )
          }
        }
      }
    },
    test("outside of git there is no history") {
      ZIO.scoped(tree.flatMap(root => ZIO.attemptBlocking(Sources.history(root, None)).map(update => assertTrue(update.isEmpty))))
    }
  )
}
