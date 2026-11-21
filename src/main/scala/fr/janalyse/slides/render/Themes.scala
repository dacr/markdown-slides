package fr.janalyse.slides.render

import fr.janalyse.slides.AppConfig
import zio.*

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/** A reveal.js compatible theme : a css file of a theme directory, or a built-in one.
  *
  * @param stamp
  *   changes whenever the theme file changes
  */
case class Theme(name: String, origin: String, stamp: String)

/** The themes : the built-in ones, then those of the theme directories, which take precedence. */
class Themes(config: AppConfig) {

  private def builtinCss(name: String): Option[String] =
    Option(getClass.getClassLoader.getResourceAsStream(s"themes/$name.css")).map(in => Using.resource(in)(stream => new String(stream.readAllBytes(), "UTF-8")))

  private def files: List[Path] =
    config.themePaths.filter(Files.isDirectory(_)).flatMap { dir =>
      Try(Using.resource(Files.list(dir))(_.iterator().asScala.toList)).getOrElse(Nil).filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".css")).sortBy(_.toString)
    }

  private def nameOf(path: Path): String = path.getFileName.toString.stripSuffix(".css")

  /** All the themes, by name. Reading them is cheap : the scan checks them for changes. */
  def list: UIO[List[Theme]] =
    ZIO.attemptBlocking {
      val fromFiles = files.map(path => Theme(nameOf(path), "directory", s"${Files.size(path)}-${Files.getLastModifiedTime(path).toMillis}"))
      val builtins  = Themes.builtins.map(name => Theme(name, "built-in", "1"))
      (fromFiles ++ builtins.filterNot(theme => fromFiles.exists(_.name == theme.name))).sortBy(_.name)
    }.orElseSucceed(Nil)

  def stamp(name: String): UIO[String] = list.map(_.find(_.name == name).map(_.stamp).getOrElse(""))

  /** The css of a theme, None when unknown. */
  def css(name: String): UIO[Option[String]] =
    if (!name.matches("[A-Za-z0-9_.-]+")) ZIO.none
    else
      ZIO.attemptBlocking(files.find(nameOf(_) == name).map(path => Files.readString(path)).orElse(builtinCss(name))).orElseSucceed(None)

  /** The theme to use : the wanted one when it exists, then the configured default, then the first built-in. */
  def resolve(wanted: String): UIO[String] =
    list.map(themes => List(wanted, config.defaultTheme, Themes.builtins.head).find(name => themes.exists(_.name == name)).getOrElse(Themes.builtins.head))
}

object Themes {
  val builtins: List[String] = List("white", "black")

  val layer: ZLayer[AppConfig, Nothing, Themes] = ZLayer.derive[Themes]
}
