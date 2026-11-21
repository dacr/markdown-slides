package fr.janalyse.slides.web

import com.github.plokhotnyuk.jsoniter_scala.core.*
import fr.janalyse.slides.AppConfig
import fr.janalyse.slides.live.LiveRooms
import fr.janalyse.slides.model.*
import fr.janalyse.slides.pipeline.{AppEvent, Pipeline}
import fr.janalyse.slides.render.{Renderer, Themes}
import fr.janalyse.slides.search.SearchService
import fr.janalyse.slides.store.Store
import fr.janalyse.slides.web.Views.given
import zio.*
import zio.http.*
import zio.stream.*

import java.net.{Inet4Address, NetworkInterface, URLDecoder}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as FilePath}
import scala.jdk.CollectionConverters.*

case class HttpError(status: Status, message: String) extends Exception(message)

class WebServer(config: AppConfig, store: Store, search: SearchService, pipeline: Pipeline, renderer: Renderer, themes: Themes, live: LiveRooms) {

  // ==================================================================================================================
  // Helpers

  private def json[A: JsonValueCodec](value: A, status: Status = Status.Ok): Response =
    Response(status = status, headers = Headers(Header.ContentType(MediaType.application.json), Header.CacheControl.NoStore), body = Body.fromArray(writeToArray(value)))

  private def failure(status: Status, message: String): Response = json(Message(message), status)

  private def notFound(what: String): HttpError = HttpError(Status.NotFound, s"$what not found")

  /** Changes need a custom header, which browsers never send cross origin without a CORS agreement this server never gives. */
  private def guard(request: Request): IO[HttpError, Unit] =
    if (request.method != Method.GET && request.method != Method.HEAD && !request.headers.get("X-Requested-With").contains("markdown-slides"))
      ZIO.fail(HttpError(Status.Forbidden, "Missing X-Requested-With header"))
    else ZIO.unit

  private def handle(request: Request)(effect: => Task[Response]): UIO[Response] =
    (guard(request) *> ZIO.suspend(effect)).catchAll {
      case HttpError(status, message) => ZIO.succeed(failure(status, message))
      case err                        =>
        ZIO
          .logError(s"${request.method} ${request.path} : ${err.getClass.getSimpleName} ${err.getMessage}")
          .as(failure(Status.InternalServerError, Option(err.getMessage).getOrElse(err.toString)))
    }

  private def param(request: Request, name: String): Option[String] = request.url.queryParams.getAll(name).headOption.map(_.trim).filter(_.nonEmpty)

  private def idParam(request: Request): Task[String] = ZIO.fromOption(param(request, "id")).orElseFail(HttpError(Status.BadRequest, "Missing id"))

  private def body[A: JsonValueCodec](request: Request): Task[A] =
    request.body.asArray.flatMap(bytes => ZIO.attempt(readFromArray[A](bytes))).mapError(err => HttpError(Status.BadRequest, s"Invalid request : ${err.getMessage}"))

  private def fileResponse(file: FilePath, headers: Headers): UIO[Response] = Body.fromFile(file.toFile).map(body => Response(headers = headers, body = body))

  private def deckOrFail(id: String): Task[Deck] = store.deck(id).someOrFail(notFound("deck"))

  // ==================================================================================================================
  // Static files

  private val contentTypes = Map(
    "html"  -> MediaType.text.html,
    "js"    -> MediaType.application.javascript,
    "mjs"   -> MediaType.application.javascript,
    "css"   -> MediaType.text.css,
    "svg"   -> MediaType.image.`svg+xml`,
    "png"   -> MediaType.image.png,
    "json"  -> MediaType.application.json,
    "woff"  -> MediaType.forContentType("font/woff").getOrElse(MediaType.application.`octet-stream`),
    "woff2" -> MediaType.forContentType("font/woff2").getOrElse(MediaType.application.`octet-stream`),
    "ttf"   -> MediaType.forContentType("font/ttf").getOrElse(MediaType.application.`octet-stream`),
    "eot"   -> MediaType.application.`vnd.ms-fontobject`
  )

  private def mediaType(name: String): MediaType = {
    val extension = name.split('.').last.toLowerCase
    contentTypes.get(extension).orElse(MediaType.forFileExtension(extension)).getOrElse(MediaType.application.`octet-stream`)
  }

  private val securityHeaders = List(
    Header.Custom("X-Content-Type-Options", "nosniff"),
    Header.Custom("Referrer-Policy", "no-referrer"),
    // slides show pictures, videos and frames from anywhere ; scripts only from here
    Header.Custom(
      "Content-Security-Policy",
      "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src * data: blob:; media-src *; frame-src *; font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'"
    )
  )

  private def classpath(resource: String, cache: Header.CacheControl): UIO[Response] =
    ZIO
      .attemptBlocking(Option(getClass.getClassLoader.getResourceAsStream(resource)).map(in => scala.util.Using.resource(in)(_.readAllBytes())))
      .orDie
      .map {
        case None        => failure(Status.NotFound, "not found")
        case Some(bytes) => Response(headers = Headers(Header.ContentType(mediaType(resource)) :: cache :: securityHeaders), body = Body.fromArray(bytes))
      }

  private def page(name: String): UIO[Response] = classpath(s"web/$name", Header.CacheControl.NoCache)

  private val immutable = Header.CacheControl.Multiple(NonEmptyChunk(Header.CacheControl.Public, Header.CacheControl.MaxAge(31536000), Header.CacheControl.Immutable))

  private def safe(path: String): Boolean = !path.split('/').exists(part => part == ".." || part == "." || part.isEmpty) && !path.contains('\\')

  /** The browser libraries : marked, highlight.js, KaTeX, mermaid. */
  private def webjar(path: Path): UIO[Response] = {
    val relative = path.encode.stripPrefix("/")
    val allowed  = List("marked/", "highlightjs__cdn-assets/", "katex/", "mermaid/")
    if (safe(relative) && allowed.exists(relative.startsWith)) classpath(s"META-INF/resources/webjars/$relative", immutable)
    else ZIO.succeed(failure(Status.NotFound, "not found"))
  }

  /** The fonts the reveal.js themes import, `@import url(../../lib/font/source-sans-pro/source-sans-pro.css)`. */
  private def font(path: Path): UIO[Response] = {
    val relative = path.encode.stripPrefix("/")
    if (safe(relative) && relative.startsWith("source-sans-pro/")) classpath(s"META-INF/resources/webjars/reveal.js/5.0.4/dist/theme/fonts/$relative", immutable)
    else ZIO.succeed(failure(Status.NotFound, "not found"))
  }

  /** A file of a source : the pictures of the decks. Hidden files and files outside the source are refused. */
  private def sourceFile(path: Path, request: Request): Task[Response] = {
    val segments = path.segments.toList.map(segment => URLDecoder.decode(segment, StandardCharsets.UTF_8))
    segments match {
      case source :: rest if rest.nonEmpty && rest.forall(part => part.nonEmpty && part != ".." && part != "." && !part.startsWith(".")) =>
        val root = pipeline.root(source).getOrElse(throw notFound("source"))
        val file = root.path.resolve(rest.mkString("/")).normalize()
        ZIO.attemptBlocking(file.startsWith(root.path) && Files.isRegularFile(file)).flatMap { exists =>
          if (!exists) ZIO.fail(notFound("file"))
          else {
            // the views ask for the pictures with the stamp of their directory : a changed picture has another URL
            val cache = if (param(request, "v").isDefined) Header.CacheControl.Multiple(NonEmptyChunk(Header.CacheControl.Public, Header.CacheControl.MaxAge(86400))) else Header.CacheControl.NoCache
            fileResponse(file, Headers(Header.ContentType(mediaType(file.getFileName.toString)), cache))
          }
        }
      case _ => ZIO.fail(notFound("file"))
    }
  }

  private def themeCss(file: String): UIO[Response] = {
    val name = file.stripSuffix(".css")
    themes.css(name).map {
      case None      => failure(Status.NotFound, "theme not found")
      case Some(css) => Response(headers = Headers(Header.ContentType(MediaType.text.css), Header.CacheControl.NoCache), body = Body.fromString(css))
    }
  }

  private def customCss: Task[Response] =
    store.settings.map(settings => Response(headers = Headers(Header.ContentType(MediaType.text.css), Header.CacheControl.NoCache), body = Body.fromString(settings.customCss)))

  // ==================================================================================================================
  // API

  private def summaryOf(deck: Deck): Task[DeckSummary] =
    ZIO.attemptBlocking(deck.thumbnailKey.isDefined && Files.exists(renderer.thumbnailFile(deck.id))).map(drawn => Views.summary(deck, drawn))

  private def roomViews: Task[List[RoomView]] =
    live.list.flatMap(rooms => ZIO.foreach(rooms)(room => store.deck(room.deckId).map(deck => RoomView(room.id, room.deckId, deck.map(_.title).getOrElse(room.deckId), room.state, room.followers, room.startedAt))))

  private def catalog: Task[Response] =
    for {
      decks     <- store.allDecks
      summaries <- ZIO.foreach(decks.toList)(deck => summaryOf(deck))
      rooms     <- roomViews
    } yield json(Catalog(Views.presentations(summaries), rooms, pipeline.roots.map(_.name)))

  private def clientConfig: Task[Response] =
    for {
      settings <- store.settings
      theme    <- themes.resolve(settings.theme)
      all      <- themes.list
    } yield json(ClientConfig(config.slideWidth, config.slideHeight, theme, all, settings.customCss, renderer.available, config.embeddings.enabled, config.listen.host))

  private def deckView(id: String): Task[Response] =
    for {
      deck     <- deckOrFail(id)
      content  <- store.content(id).someOrFail(notFound("deck content"))
      settings <- store.settings
      theme    <- themes.resolve(deck.frontMatter.get("theme").map(_.stripSuffix(".css").split('/').last).getOrElse(settings.theme))
      summary  <- summaryOf(deck)
      siblings <- store.decksOf(deck.source).map(_.filter(other => other.directory == deck.directory && other.id != deck.id).toList)
      others   <- ZIO.foreach(siblings)(other => summaryOf(other))
    } yield json(DeckView(summary, content.slides, theme, deck.frontMatter, others))

  private def thumbnail(id: String): Task[Response] =
    ZIO.attemptBlocking(renderer.thumbnailFile(id)).flatMap { file =>
      ZIO.attemptBlocking(Files.isRegularFile(file)).flatMap { exists =>
        if (!exists) ZIO.fail(notFound("thumbnail"))
        else fileResponse(file, Headers(Header.ContentType(MediaType.image.png), Header.CacheControl.Multiple(NonEmptyChunk(Header.CacheControl.Public, Header.CacheControl.MaxAge(86400)))))
      }
    }

  private def pdf(id: String): Task[Response] =
    for {
      _       <- ZIO.unless(renderer.available)(ZIO.fail(HttpError(Status.ServiceUnavailable, s"No browser found (${config.browser}) to print the PDF, set MARKDOWN_SLIDES_BROWSER")))
      deck    <- deckOrFail(id)
      content <- store.content(id).someOrFail(notFound("deck content"))
      file    <- renderer.pdf(deck, content)
      reply   <- fileResponse(
                   file,
                   Headers(Header.ContentType(MediaType.application.pdf), Header.ContentDisposition.attachment(renderer.exportName(deck)), Header.CacheControl.NoStore)
                 )
    } yield reply

  private def searchView(request: Request): Task[Response] =
    search
      .search(param(request, "q").getOrElse(""), param(request, "source").toSet, param(request, "limit").flatMap(_.toIntOption).map(_.max(1).min(200)).getOrElse(40))
      .map(json(_))

  private def updateSettings(request: Request): Task[Response] =
    for {
      update   <- body[SettingsUpdate](request)
      current  <- store.settings
      known    <- themes.list
      _        <- ZIO.foreachDiscard(update.theme)(theme => ZIO.unless(known.exists(_.name == theme))(ZIO.fail(HttpError(Status.BadRequest, s"Unknown theme $theme"))))
      settings  = current.copy(theme = update.theme.getOrElse(current.theme), customCss = update.customCss.getOrElse(current.customCss).take(200_000))
      _        <- store.saveSettings(settings)
      _        <- pipeline.notifySettingsChanged
    } yield json(settings)

  // ==================================================================================================================
  // Server sent events

  private val heartbeat: ZStream[Any, Nothing, ServerSentEvent[String]] =
    ZStream.tick(20.seconds).drop(1).as(ServerSentEvent("{}", eventType = Some("ping")))

  private def sse(stream: ZStream[Any, Nothing, ServerSentEvent[String]]): Response =
    Response.fromServerSentEvents(stream.merge(heartbeat, ZStream.HaltStrategy.Left)).addHeader(Header.CacheControl.NoCache).addHeader(Header.Custom("X-Accel-Buffering", "no"))

  /** What changes, for the open views : decks, thumbnails, themes, live presentations. */
  private def events: Response = {
    val changes = pipeline.events.map(event => ServerSentEvent(writeToString(event), eventType = Some(event.kind)))
    val rooms   = live.roomChanges.as(ServerSentEvent(writeToString(AppEvent("live-changed")), eventType = Some("live-changed")))
    sse(ZStream.succeed(ServerSentEvent("{}", eventType = Some("hello"))) ++ changes.merge(rooms))
  }

  private def liveEvents(request: Request): Task[Response] =
    for {
      room   <- ZIO.fromOption(param(request, "room")).orElseFail(HttpError(Status.BadRequest, "Missing room"))
      counted = !param(request, "presenter").contains("true")
    } yield sse(live.messages(room, counted).map(message => ServerSentEvent(writeToString(message), eventType = Some(message.kind))))

  // ==================================================================================================================
  // Live

  private def liveStart(request: Request): Task[Response] =
    for {
      start <- body[LiveStart](request)
      _     <- deckOrFail(start.deckId)
      now   <- Clock.instant
      room  <- live.start(start.deckId, Some(fr.janalyse.slides.live.LiveState(start.h, start.v, start.f, false, false, 0, now)))
    } yield json(LiveStarted(room.id, room.token, room.deckId))

  private def liveMove(request: Request): Task[Response] =
    for {
      room  <- ZIO.fromOption(param(request, "room")).orElseFail(HttpError(Status.BadRequest, "Missing room"))
      move  <- body[LiveMove](request)
      state <- live.move(room, move.token, move.h, move.v, move.f, move.overview, move.paused)
    } yield state.map(_ => json(Message("ok"))).getOrElse(failure(Status.Forbidden, "Unknown room or wrong token"))

  private def liveStop(request: Request): Task[Response] =
    for {
      room    <- ZIO.fromOption(param(request, "room")).orElseFail(HttpError(Status.BadRequest, "Missing room"))
      stop    <- body[LiveStop](request)
      stopped <- live.stop(room, stop.token)
    } yield if (stopped) json(Message("Live presentation ended")) else failure(Status.Forbidden, "Unknown room or wrong token")

  private def liveRoom(request: Request): Task[Response] =
    for {
      id   <- ZIO.fromOption(param(request, "room")).orElseFail(HttpError(Status.BadRequest, "Missing room"))
      room <- live.get(id).someOrFail(notFound("live presentation"))
      deck <- store.deck(room.deckId)
    } yield json(RoomView(room.id, room.deckId, deck.map(_.title).getOrElse(room.deckId), room.state, room.followers, room.startedAt))

  /** The addresses of this computer participants can use, when listening on every interface. */
  private def share: Task[Response] =
    ZIO.attemptBlocking {
      val port = config.listen.port
      config.listen.host match {
        case "0.0.0.0" | "::" | "" =>
          NetworkInterface.getNetworkInterfaces.asScala.toList
            .filter(i => i.isUp && !i.isLoopback && !i.isVirtual)
            .flatMap(_.getInetAddresses.asScala.collect { case a: Inet4Address => a.getHostAddress })
            .distinct
            .map(ip => s"http://$ip:$port")
        case "127.0.0.1" | "localhost" | "::1" => Nil
        case host                              => List(s"http://$host:$port")
      }
    }.map(urls => json(ShareInfo(urls)))

  // ==================================================================================================================

  val routes: Routes[Any, Nothing] = Routes(
    Method.GET / ""                                    -> handler(page("index.html")),
    Method.GET / "play"                                -> handler(page("player.html")),
    Method.GET / "speaker"                             -> handler(page("speaker.html")),
    Method.GET / "live" / string("room")             -> handler((_: String, _: Request) => page("player.html")),
    Method.GET / "print"                               -> handler(page("print.html")),
    Method.GET / "render"                              -> handler(page("print.html")),
    Method.GET / "assets" / string("file")             -> handler((file: String, _: Request) =>
      if (file.matches("[a-zA-Z0-9_-]+\\.(js|css|svg|png)")) classpath(s"web/$file", Header.CacheControl.NoCache) else ZIO.succeed(failure(Status.NotFound, "not found"))
    ),
    Method.GET / "webjars" / trailing                  -> handler((path: Path, _: Request) => webjar(path)),
    Method.GET / "lib" / "font" / trailing             -> handler((path: Path, _: Request) => font(path)),
    Method.GET / "themes" / string("file")             -> handler((file: String, _: Request) => themeCss(file)),
    Method.GET / "custom.css"                          -> handler((request: Request) => handle(request)(customCss)),
    Method.GET / "files" / trailing                    -> handler((path: Path, request: Request) => handle(request)(sourceFile(path, request))),
    Method.GET / "api" / "config"                      -> handler((request: Request) => handle(request)(clientConfig)),
    Method.GET / "api" / "catalog"                     -> handler((request: Request) => handle(request)(catalog)),
    Method.GET / "api" / "deck"                        -> handler((request: Request) => handle(request)(idParam(request).flatMap(deckView))),
    Method.GET / "api" / "thumbnail"                   -> handler((request: Request) => handle(request)(idParam(request).flatMap(thumbnail))),
    Method.GET / "api" / "pdf"                         -> handler((request: Request) => handle(request)(idParam(request).flatMap(pdf))),
    Method.GET / "api" / "search"                      -> handler((request: Request) => handle(request)(searchView(request))),
    Method.GET / "api" / "status"                      -> handler((request: Request) => handle(request)(pipeline.status.map(json(_)))),
    Method.PUT / "api" / "settings"                    -> handler((request: Request) => handle(request)(updateSettings(request))),
    Method.POST / "api" / "scan"                       -> handler((request: Request) => handle(request)(pipeline.scan.map(count => json(Message(s"$count decks changed"))))),
    Method.GET / "api" / "events"                      -> handler((request: Request) => handle(request)(ZIO.succeed(events))),
    Method.GET / "api" / "share"                       -> handler((request: Request) => handle(request)(share)),
    Method.GET / "api" / "live"                        -> handler((request: Request) => handle(request)(roomViews.map(json(_)))),
    Method.GET / "api" / "live" / "room"               -> handler((request: Request) => handle(request)(liveRoom(request))),
    Method.GET / "api" / "live" / "events"             -> handler((request: Request) => handle(request)(liveEvents(request))),
    Method.POST / "api" / "live" / "start"             -> handler((request: Request) => handle(request)(liveStart(request))),
    Method.POST / "api" / "live" / "move"              -> handler((request: Request) => handle(request)(liveMove(request))),
    Method.POST / "api" / "live" / "stop"              -> handler((request: Request) => handle(request)(liveStop(request)))
  )

  def serve: ZIO[Any, Throwable, Nothing] =
    (ZIO.logInfo(s"Listening on http://${config.listen.host}:${config.listen.port}/") *> Server.serve(routes))
      .provide(Server.defaultWith(_.binding(config.listen.host, config.listen.port).requestStreaming(Server.RequestStreaming.Disabled(1024 * 1024))))
}

object WebServer {
  val layer: ZLayer[AppConfig & Store & SearchService & Pipeline & Renderer & Themes & LiveRooms, Nothing, WebServer] = ZLayer.derive[WebServer]
}
