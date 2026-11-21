package fr.janalyse.slides

import zio.*
import zio.config.*
import zio.config.magnolia.*

import java.nio.file.{Path, Paths}

case class ListenConfig(host: String, port: Int)

case class EmbeddingsConfig(
  baseUrl: String,
  model: String,
  apiKeyVariable: String,
  username: String,
  passwordVariable: String,
  timeoutSeconds: Int,
  batchSize: Int
) {
  def enabled: Boolean         = baseUrl.trim.nonEmpty && model.trim.nonEmpty
  def apiKey: Option[String]   = EmbeddingsConfig.secret(apiKeyVariable)
  def password: Option[String] = EmbeddingsConfig.secret(passwordVariable)

  /** The Authorization header : basic authentication when a username is given, else the API key. */
  def authorization: Option[String] = EmbeddingsConfig.authorization(username, password, apiKey)
}

object EmbeddingsConfig {

  /** A secret is read from the environment variable named by the configuration, never written in it. */
  def secret(variable: String): Option[String] = Option(variable).filter(_.nonEmpty).flatMap(sys.env.get).map(_.trim).filter(_.nonEmpty)

  def authorization(username: String, password: Option[String], apiKey: Option[String]): Option[String] =
    Option(username).map(_.trim).filter(_.nonEmpty) match {
      case Some(user) =>
        val credentials = s"$user:${password.getOrElse("")}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        Some(s"Basic ${java.util.Base64.getEncoder.encodeToString(credentials)}")
      case None       => apiKey.map(key => s"Bearer $key")
    }
}

/** Static configuration, read from `application.conf` (HOCON), overridable with environment variables. */
case class AppConfig(
  listen: ListenConfig,
  dataDirectory: String,
  sources: String,
  excludes: String,
  scanIntervalSeconds: Int,
  themeDirectories: String,
  defaultTheme: String,
  slideWidth: Int,
  slideHeight: Int,
  browser: String,
  pdfAuthor: String,
  embeddings: EmbeddingsConfig
) {
  def dataPath: Path       = AppConfig.expand(dataDirectory)
  def thumbnailsPath: Path = dataPath.resolve("thumbnails")
  def exportsPath: Path    = dataPath.resolve("exports")
  def browserProfile: Path = dataPath.resolve("browser-profile")

  def sourcePaths: List[Path]     = AppConfig.items(sources).map(AppConfig.expand)
  def excludePatterns: List[String] = AppConfig.items(excludes)
  def themePaths: List[Path]      = AppConfig.items(themeDirectories).map(AppConfig.expand)

  /** The address this process reaches itself at, for the headless browser. */
  def selfUrl: String = {
    val host = listen.host match {
      case "0.0.0.0" | "::" | "" => "127.0.0.1"
      case other                 => other
    }
    s"http://$host:${listen.port}"
  }
}

object AppConfig {
  def items(text: String): List[String] = text.split(",").map(_.trim).filter(_.nonEmpty).toList

  def expand(path: String): Path = Paths.get(path.replaceFirst("^~", java.lang.System.getProperty("user.home"))).toAbsolutePath.normalize()

  val config: Config[AppConfig] = deriveConfig[AppConfig].mapKey(toKebabCase).nested("markdown-slides")

  val layer: ZLayer[Any, Config.Error, AppConfig] = ZLayer.fromZIO(ZIO.config(config))
}
