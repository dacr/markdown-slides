package fr.janalyse.slides

import fr.janalyse.slides.live.LiveRooms
import fr.janalyse.slides.pipeline.Pipeline
import fr.janalyse.slides.render.{Browser, Renderer, Themes}
import fr.janalyse.slides.search.{Embeddings, SearchService, WordIndex}
import fr.janalyse.slides.store.Store
import fr.janalyse.slides.web.WebServer
import io.netty.channel.unix.Errors
import io.netty.channel.unix.Errors.NativeIoException
import zio.*
import zio.config.typesafe.TypesafeConfigProvider
import zio.logging.slf4j.bridge.Slf4jBridge
import zio.logging.{LogFilter, LogFormat, LogGroup, ZLoggerZIOLayerOps, makeSystemOutLogger}

import java.nio.channels.ClosedChannelException

object Main extends ZIOAppDefault {

  // the libraries (pdfbox, jgit...) are verbose : only their warnings
  private val logFilter = LogFilter.LogLevelByNameConfig(
    LogLevel.Info,
    "org.apache.pdfbox"  -> LogLevel.Error,
    "org.apache.fontbox" -> LogLevel.Error,
    "org.eclipse.jgit"   -> LogLevel.Warning,
    "io.netty"           -> LogLevel.Warning
  )

  // a browser dropping its connection (a tab closed or reloaded, the headless browser done) is no failure
  private def closedByPeer(error: Throwable): Boolean = error match {
    case native: NativeIoException =>
      native.expectedErr == Errors.ERRNO_ECONNRESET_NEGATIVE || native.expectedErr == Errors.ERRNO_EPIPE_NEGATIVE
    case _: ClosedChannelException => true
    case null                      => false
    case other                     => other.getCause != other && closedByPeer(other.getCause)
  }

  private val notClosedByPeer = LogFilter[Any, Cause[Any]](
    LogGroup.cause,
    cause => !(cause.defects ++ cause.failures.collect { case error: Throwable => error }).exists(closedByPeer)
  )

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>>
      Runtime.setConfigProvider(TypesafeConfigProvider.fromResourcePath()) >>>
      makeSystemOutLogger(LogFormat.colored.toLogger).filter(logFilter.toFilter[String] && notClosedByPeer).install >+>
      Slf4jBridge.initialize

  override def run =
    (for {
      pipeline <- ZIO.service[Pipeline]
      live     <- ZIO.service[LiveRooms]
      _        <- pipeline.start
      _        <- live.expire.forkScoped
      _        <- ZIO.serviceWithZIO[WebServer](_.serve)
    } yield ())
      .provideSome[Scope](
        AppConfig.layer,
        Store.layer,
        WordIndex.layer,
        Embeddings.layer,
        SearchService.layer,
        Themes.layer,
        Browser.layer,
        Renderer.layer,
        Pipeline.layer,
        LiveRooms.layer,
        WebServer.layer
      )
}
