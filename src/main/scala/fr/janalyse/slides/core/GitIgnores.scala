package fr.janalyse.slides.core

import org.eclipse.jgit.ignore.IgnoreNode
import org.eclipse.jgit.ignore.IgnoreNode.MatchResult
import org.eclipse.jgit.lib.Repository

import java.nio.file.{Files, Path}
import scala.util.{Try, Using}

/** The ignore rules of a directory tree, evaluated as git does for untracked files : the `.gitignore` of each directory from the root down to the file, the deeper ones taking
  * precedence, then the repository `info/exclude` and the user's global excludes file. A file within an ignored directory is ignored, whatever the rules below it say.
  *
  * Unlike git, the rules are also applied to tracked files : a deck matching a `.gitignore` is never wanted.
  *
  * @param root
  *   the directory the paths are relative to, the git working tree when there is one
  * @param global
  *   rules of the repository and of the user, checked after all the `.gitignore` files
  */
class GitIgnores(root: Path, global: List[IgnoreNode]) {

  private val nodes = scala.collection.mutable.HashMap.empty[String, Option[IgnoreNode]]

  /** The rules of the `.gitignore` of a directory, relative to the root. */
  private def node(directory: String): Option[IgnoreNode] =
    nodes.getOrElseUpdate(
      directory, {
        val file = if (directory.isEmpty) root.resolve(".gitignore") else root.resolve(directory).resolve(".gitignore")
        if (!Files.isRegularFile(file)) None
        else
          Try(Using.resource(Files.newInputStream(file)) { in =>
            val n = new IgnoreNode(); n.parse(in); n
          }).toOption
      }
    )

  /** Is this entry, a file or a directory, ignored by the rules, without looking at its parents ? */
  private def matched(path: String, isDirectory: Boolean): Boolean = {
    val parts       = path.split('/')
    // the deepest .gitignore first, its path relative to the directory of the .gitignore
    val directories = (parts.length - 1 to 0 by -1).map(depth => parts.take(depth).mkString("/"))
    val local       = directories.iterator.flatMap { directory =>
      node(directory).map { rules =>
        val relative = if (directory.isEmpty) path else path.drop(directory.length + 1)
        rules.isIgnored(relative, isDirectory)
      }
    }
    val results     = local ++ global.iterator.map(_.isIgnored(path, isDirectory))
    results.find(_ != MatchResult.CHECK_PARENT).contains(MatchResult.IGNORED)
  }

  private val ignoredDirectories = scala.collection.mutable.HashMap.empty[String, Boolean]

  /** Is the file ignored, by its own rules or because one of its directories is ? */
  def ignored(path: String): Boolean = {
    val parts = path.split('/')
    val dirs  = (1 until parts.length).map(depth => parts.take(depth).mkString("/"))
    dirs.exists(dir => ignoredDirectories.getOrElseUpdate(dir, matched(dir, isDirectory = true))) || matched(path, isDirectory = false)
  }
}

object GitIgnores {

  private def parse(file: Path): Option[IgnoreNode] =
    if (!Files.isRegularFile(file)) None
    else
      Try(Using.resource(Files.newInputStream(file)) { in =>
        val n = new IgnoreNode(); n.parse(in); n
      }).toOption

  /** The rules of a git working tree : its `.gitignore` files, `info/exclude` and the user's `core.excludesFile` (by default `~/.config/git/ignore`). */
  def of(repo: Repository): GitIgnores = {
    val home         = java.lang.System.getProperty("user.home")
    val xdg          = sys.env.get("XDG_CONFIG_HOME").filter(_.nonEmpty).getOrElse(s"$home/.config")
    val configured   = Option(repo.getConfig.getString("core", null, "excludesfile")).map(_.replaceFirst("^~", home))
    val excludesFile = Path.of(configured.getOrElse(s"$xdg/git/ignore"))
    val infoExclude  = repo.getDirectory.toPath.resolve("info").resolve("exclude")
    new GitIgnores(repo.getWorkTree.toPath.toAbsolutePath.normalize(), List(parse(infoExclude), parse(excludesFile)).flatten)
  }

  /** The rules of a plain directory : its `.gitignore` files only. */
  def plain(root: Path): GitIgnores = new GitIgnores(root, Nil)
}
