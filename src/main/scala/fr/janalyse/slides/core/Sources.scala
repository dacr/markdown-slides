package fr.janalyse.slides.core

import fr.janalyse.slides.model.GitInfo
import org.eclipse.jgit.diff.{DiffEntry, DiffFormatter}
import org.eclipse.jgit.lib.{Constants, ObjectId, Repository}
import org.eclipse.jgit.revwalk.{RevCommit, RevWalk}
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.util.io.DisabledOutputStream

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileSystems, FileVisitResult, Files, Path, PathMatcher, SimpleFileVisitor}
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/** A file found in a source directory.
  *
  * @param path
  *   relative to the source root, with `/` separators
  */
case class SourceFile(path: String, absolute: Path, size: Long, lastModified: Instant) {
  def isMarkdown: Boolean = Sources.isMarkdown(path)
  def directory: String   = Sources.directoryOf(path)
}

/** What a source directory holds : its files, except the hidden ones, those a `.gitignore` ignores, and the excluded ones. And what the git history tells about them, when the
  * source is within a git working tree.
  */
object Sources {

  def isMarkdown(path: String): Boolean = {
    val lower = path.toLowerCase
    lower.endsWith(".md") || lower.endsWith(".markdown")
  }

  def directoryOf(path: String): String = path.split('/').dropRight(1).mkString("/")

  def matchers(excludes: List[String]): List[PathMatcher] =
    excludes.map(_.trim).filter(_.nonEmpty).flatMap(glob => Try(FileSystems.getDefault.getPathMatcher(s"glob:$glob")).toOption)

  def excluded(path: String, matchers: List[PathMatcher]): Boolean = {
    val p = Path.of(path)
    // "**/x/**" globs need a parent directory : also try with a leading one
    matchers.exists(m => m.matches(p) || m.matches(Path.of("_", path)))
  }

  def openRepository(root: Path): Option[Repository] =
    Try {
      val builder = new FileRepositoryBuilder().setMustExist(true).readEnvironment()
      builder.findGitDir(root.toFile)
      Option(builder.getGitDir).map(_ => builder.build())
    }.toOption.flatten.filter(repo => !repo.isBare)

  /** Where the source is within its working tree, `""` at its root, `slides/` below. */
  private def prefixOf(repo: Repository, root: Path): String = {
    val workTree = repo.getWorkTree.toPath.toAbsolutePath.normalize()
    if (root == workTree) "" else workTree.relativize(root).toString.replace('\\', '/') + "/"
  }

  /** The files of a source. Ignored and hidden directories are not walked through. */
  def list(root: Path, excludes: List[PathMatcher]): List[SourceFile] =
    if (!Files.isDirectory(root)) Nil
    else {
      val repository           = openRepository(root)
      val (ignores, prefix)    = repository match {
        case Some(repo) => Using.resource(repo)(r => (GitIgnores.of(r), prefixOf(r, root)))
        case None       => (GitIgnores.plain(root), "")
      }
      val found                = List.newBuilder[SourceFile]
      def relative(path: Path) = root.relativize(path).toString.replace('\\', '/')
      Files.walkFileTree(
        root,
        new SimpleFileVisitor[Path] {
          override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
            if (dir == root) FileVisitResult.CONTINUE
            else {
              val path = relative(dir)
              val name = dir.getFileName.toString
              if (name.startsWith(".") || excluded(s"$path/_", excludes) || ignores.ignored(s"$prefix$path/_")) FileVisitResult.SKIP_SUBTREE
              else FileVisitResult.CONTINUE
            }

          override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
            val path = relative(file)
            if (attrs.isRegularFile && !attrs.isSymbolicLink && !file.getFileName.toString.startsWith(".") && !excluded(path, excludes) && !ignores.ignored(s"$prefix$path"))
              found += SourceFile(path, file, attrs.size(), attrs.lastModifiedTime().toInstant)
            FileVisitResult.CONTINUE
          }

          // a file removed or unreadable meanwhile
          override def visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        }
      )
      found.result()
    }

  // ==================================================================================================================
  // History

  /** The commit HEAD points to, None outside of git or in an empty repository. */
  def head(root: Path): Option[String] =
    openRepository(root).flatMap(repo => Using.resource(repo)(r => Option(r.resolve(Constants.HEAD)).map(_.name())))

  /** @param files the markdown files changed by the walked commits, relative to the source root */
  case class HistoryUpdate(head: String, files: Map[String, GitInfo], full: Boolean)

  def merge(a: GitInfo, b: GitInfo): GitInfo =
    GitInfo(
      createdAt = if (a.createdAt.isBefore(b.createdAt)) a.createdAt else b.createdAt,
      updatedAt = if (a.updatedAt.isAfter(b.updatedAt)) a.updatedAt else b.updatedAt,
      commits = a.commits + b.commits,
      authors = (a.authors ++ b.authors).distinct.take(8)
    )

  /** Walks the commits between `since` (excluded) and HEAD, telling for each markdown file of the source when it was first and last changed, and how many times. Moved files keep
    * their history.
    *
    * @param since
    *   the head of the previous walk, the whole history when None or when it is no longer an ancestor of HEAD (history rewritten)
    * @return
    *   None outside of git, or when HEAD did not change
    */
  def history(root: Path, since: Option[String]): Option[HistoryUpdate] =
    openRepository(root).flatMap { repository =>
      Using.resource(repository) { repo =>
        Option(repo.resolve(Constants.HEAD)).filterNot(head => since.contains(head.name())).map { head =>
          val prefix = prefixOf(repo, root)
          Using.resource(new RevWalk(repo)) { walk =>
            val headCommit = walk.parseCommit(head)
            val previous   = since.flatMap(id => Try(walk.parseCommit(ObjectId.fromString(id))).toOption).filter(commit => walk.isMergedInto(commit, headCommit))
            walk.reset()
            walk.markStart(walk.parseCommit(head))
            previous.foreach(commit => walk.markUninteresting(walk.parseCommit(commit)))
            val files                         = scala.collection.mutable.HashMap.empty[String, GitInfo]
            // older path -> newer path, learnt while walking back in time
            val renames                       = scala.collection.mutable.HashMap.empty[String, String]
            def current(path: String): String = {
              var p     = path
              var steps = 0
              while (renames.contains(p) && steps < 100) { p = renames(p); steps += 1 }
              p
            }
            Using.resource(new DiffFormatter(DisabledOutputStream.INSTANCE)) { formatter =>
              formatter.setRepository(repo)
              formatter.setDetectRenames(true)
              if (prefix.nonEmpty) formatter.setPathFilter(PathFilter.create(prefix.stripSuffix("/")))
              walk.iterator().asScala.foreach { commit =>
                val when   = Instant.ofEpochSecond(commit.getCommitTime.toLong)
                val author = Option(commit.getAuthorIdent).map(_.getName).getOrElse("")
                changes(formatter, walk, commit).foreach { (path0, renamedFrom) =>
                  val path = current(path0)
                  renamedFrom.foreach(old => if (old != path0) renames(old) = path)
                  if (path.startsWith(prefix) && isMarkdown(path)) {
                    val event = GitInfo(when, when, 1, List(author).filter(_.nonEmpty))
                    val key   = path.drop(prefix.length)
                    files.update(key, files.get(key).map(merge(_, event)).getOrElse(event))
                  }
                }
              }
            }
            HistoryUpdate(head.name(), files.toMap, full = previous.isEmpty)
          }
        }
      }
    }

  /** Files added, modified or moved by a commit, compared to its first parent, with their former path when moved. */
  private def changes(formatter: DiffFormatter, walk: RevWalk, commit: RevCommit): List[(String, Option[String])] = {
    val parentTree = if (commit.getParentCount == 0) null else walk.parseCommit(commit.getParent(0)).getTree
    formatter.scan(parentTree, commit.getTree).asScala.toList.flatMap { entry =>
      entry.getChangeType match {
        case DiffEntry.ChangeType.ADD | DiffEntry.ChangeType.MODIFY | DiffEntry.ChangeType.COPY => List(entry.getNewPath -> None)
        case DiffEntry.ChangeType.RENAME                                                        => List(entry.getNewPath -> Some(entry.getOldPath))
        case _                                                                                  => Nil
      }
    }
  }
}
