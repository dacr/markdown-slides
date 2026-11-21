package fr.janalyse.slides.render

import fr.janalyse.slides.AppConfig
import zio.*

import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

/** A headless Chrome or Chromium, run as a command, to draw the thumbnails and print the PDF exports.
  *
  * Each run gets a profile directory of its own, so that a browser the user has opened is left alone ; at most `slots` run at once.
  */
class Browser(config: AppConfig, slots: Queue[Int]) {

  /** The browser executable, None when not found. */
  val command: Option[String] = Browser.find(config.browser)

  def available: Boolean = command.isDefined

  private def run(arguments: List[String], timeout: Duration): Task[Unit] =
    ZIO.fromOption(command).orElseFail(new RuntimeException(s"No browser found (${config.browser}), set MARKDOWN_SLIDES_BROWSER")).flatMap { executable =>
      ZIO.acquireReleaseWith(slots.take)(slots.offer(_)) { slot =>
        val profile = config.browserProfile.resolve(s"slot-$slot")
        val output  = config.browserProfile.resolve(s"slot-$slot.log")
        val all     = executable :: Browser.baseArguments(profile) ++ arguments
        ZIO
          .acquireReleaseWith(ZIO.attemptBlocking {
            Files.createDirectories(profile)
            new ProcessBuilder(all*).redirectErrorStream(true).redirectOutput(output.toFile).start()
          })(process => ZIO.succeed(if (process.isAlive) process.destroyForcibly()))
          { process =>
            ZIO.attemptBlockingInterrupt(process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS)).flatMap { finished =>
              if (!finished) ZIO.fail(new RuntimeException(s"The browser did not finish within ${timeout.toSeconds}s"))
              else if (process.exitValue() != 0)
                ZIO.attemptBlocking(Browser.cause(Files.readString(output))).orElseSucceed("").flatMap { cause =>
                  ZIO.fail(new RuntimeException(s"The browser failed with code ${process.exitValue()}${if (cause.isEmpty) "" else s" : $cause"}"))
                }
              else ZIO.unit
            }
          }
      }
    }

  /** Draws the page at this size into a PNG file. */
  def screenshot(url: String, width: Int, height: Int, output: Path): Task[Unit] =
    run(List(s"--window-size=$width,$height", "--virtual-time-budget=8000", s"--screenshot=$output", url), 60.seconds) *>
      ZIO.attemptBlocking(Files.isRegularFile(output) && Files.size(output) > 0).filterOrFail(identity)(new RuntimeException("The browser made no screenshot")).unit

  /** Prints the page into a PDF file, its size given by the page `@page` rule. */
  def pdf(url: String, output: Path): Task[Unit] =
    run(List("--virtual-time-budget=20000", "--no-pdf-header-footer", s"--print-to-pdf=$output", url), 180.seconds) *>
      ZIO.attemptBlocking(Files.isRegularFile(output) && Files.size(output) > 0).filterOrFail(identity)(new RuntimeException("The browser printed no PDF")).unit
}

object Browser {

  /** What tells why the browser failed : its fatal error, else its last line. A sandbox refused by the system (Ubuntu 23.10+
    * forbids the user namespaces it needs to the browsers it does not know, installed by nix or by hand) points to the browser
    * of the distribution.
    */
  def cause(output: String): String = {
    val lines = output.linesIterator.map(_.trim).filter(_.nonEmpty).toList
    val cause = lines.find(_.contains("FATAL")).orElse(lines.lastOption).getOrElse("").take(400)
    if (cause.contains("No usable sandbox")) s"$cause - use the browser of the distribution : MARKDOWN_SLIDES_BROWSER=google-chrome"
    else cause
  }

  def baseArguments(profile: Path): List[String] = List(
    "--headless=new",
    "--disable-gpu",
    "--hide-scrollbars",
    "--mute-audio",
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-extensions",
    "--disable-sync",
    "--disable-background-networking",
    "--password-store=basic",
    "--run-all-compositor-stages-before-draw",
    s"--user-data-dir=$profile"
  )

  /** An absolute path, or a command of the PATH. */
  def find(command: String): Option[String] =
    Option(command).map(_.trim).filter(_.nonEmpty).flatMap { name =>
      if (name.contains(File.separator)) Some(name).filter(path => Files.isExecutable(Paths.get(path)))
      else
        sys.env
          .getOrElse("PATH", "")
          .split(File.pathSeparator)
          .iterator
          .filter(_.nonEmpty)
          .map(dir => Paths.get(dir, name))
          .find(path => Files.isExecutable(path))
          .map(_.toString)
    }

  val layer: ZLayer[AppConfig, Nothing, Browser] =
    ZLayer.fromZIO(for {
      config <- ZIO.service[AppConfig]
      slots  <- Queue.bounded[Int](2)
      _      <- slots.offerAll(List(0, 1))
    } yield Browser(config, slots))
}
