package fr.janalyse.slides.search

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import fr.janalyse.slides.AppConfig
import fr.janalyse.slides.model.*
import fr.janalyse.slides.store.Store
import zio.*
import zio.lmdb.vector.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.security.MessageDigest
import java.time.Duration as JDuration

private case class EmbeddingRequest(model: String, input: List[String])
private case class EmbeddingEntry(embedding: Array[Float] = Array.empty, index: Int = 0)
private case class EmbeddingError(message: String = "")
private case class EmbeddingResponse(data: List[EmbeddingEntry] = Nil, error: Option[EmbeddingError] = None)

private object EmbeddingCodecs {
  given JsonValueCodec[EmbeddingRequest]  = JsonCodecMaker.make
  given JsonValueCodec[EmbeddingResponse] = JsonCodecMaker.make
}

/** The search by meaning : one vector per slide, computed by an OpenAI compatible embeddings server, stored in the database (zio-lmdb-vector). Changing the model starts a new
  * collection, the previous one is dropped.
  */
class Embeddings(config: AppConfig, store: Store, current: Ref.Synchronized[Option[(VectorMeta, LMDBVectorIndex[String])]], available: Ref[Option[Boolean]]) {

  import EmbeddingCodecs.given

  private val settings = config.embeddings
  private val client   = HttpClient.newBuilder().connectTimeout(JDuration.ofSeconds(10)).build()

  def enabled: Boolean = settings.enabled

  def model: String = settings.model

  /** None : not tried yet. */
  def isAvailable: UIO[Option[Boolean]] = available.get

  private def toThrowable(err: Any): Throwable = err match {
    case t: Throwable => t
    case other        => new RuntimeException(s"Vector index error : $other")
  }

  /** What the vectors of a deck are computed from : the model and the deck content. */
  def keyOf(deck: Deck): String = s"$model:${deck.contentHash}:${Embeddings.TextVersion}:${fr.janalyse.slides.core.DeckParser.Version}"

  // ==================================================================================================================
  // Server

  def embed(inputs: List[String]): Task[List[Array[Float]]] =
    if (inputs.isEmpty) ZIO.succeed(Nil)
    else
      ZIO.suspend {
        val builder = HttpRequest
          .newBuilder(URI.create(settings.baseUrl.stripSuffix("/") + "/v1/embeddings"))
          .timeout(JDuration.ofSeconds(settings.timeoutSeconds.max(5).toLong))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofByteArray(writeToArray(EmbeddingRequest(model, inputs))))
        settings.authorization.foreach(builder.header("Authorization", _))
        for {
          response <- ZIO.fromCompletableFuture(client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray()))
          _        <- ZIO.when(response.statusCode() / 100 != 2)(
                        ZIO.fail(new RuntimeException(s"Embeddings server answered ${response.statusCode()} : ${new String(response.body()).take(300)}"))
                      )
          parsed   <- ZIO.attempt(readFromArray[EmbeddingResponse](response.body()))
          _        <- ZIO.foreachDiscard(parsed.error)(err => ZIO.fail(new RuntimeException(s"Embeddings server error : ${err.message}")))
          vectors   = parsed.data.sortBy(_.index).map(_.embedding)
          _        <- ZIO.when(vectors.size != inputs.size || vectors.exists(_.isEmpty))(ZIO.fail(new RuntimeException(s"${vectors.size} embeddings received for ${inputs.size} inputs")))
        } yield vectors
      }.tapBoth(_ => available.set(Some(false)), _ => available.set(Some(true)))

  // ==================================================================================================================
  // Vectors

  private def open(meta: VectorMeta): Task[LMDBVectorIndex[String]] =
    LMDBVectorIndex.create[String](meta.collection, meta.dimension, VectorMetric.Cosine, failIfExists = false).provideEnvironment(ZEnvironment(store.lmdb)).mapError(toThrowable)

  /** The index of the configured model, when some vectors were already computed with it. */
  private def index: Task[Option[LMDBVectorIndex[String]]] =
    current.modifyZIO {
      case Some((meta, index)) if meta.model == model => ZIO.succeed((Some(index), Some((meta, index))))
      case cached                                     =>
        store.vectorMeta.flatMap {
          case Some(meta) if meta.model == model => open(meta).map(index => (Some(index), Some((meta, index))))
          case _                                 => ZIO.succeed((None, cached))
        }
    }

  /** The index for vectors of this dimension, created when needed, dropping the vectors of a previous model. */
  private def prepare(dimension: Int): Task[LMDBVectorIndex[String]] =
    current.modifyZIO {
      case Some((meta, index)) if meta.model == model && meta.dimension == dimension => ZIO.succeed((index, Some((meta, index))))
      case _                                                                         =>
        for {
          previous <- store.vectorMeta
          result   <- previous match {
                        case Some(meta) if meta.model == model && meta.dimension == dimension => open(meta).map(index => (meta, index))
                        case _                                                                =>
                          val meta = VectorMeta(model, dimension, s"vectors-slides-${Embeddings.digest(s"$model/$dimension").take(10)}")
                          ZIO.foreachDiscard(previous.toList.map(_.collection))(store.dropCollection) *>
                            store.saveVectorMeta(meta) *>
                            ZIO.logInfo(s"Embeddings of model $model ($dimension dimensions) stored in a new collection") *>
                            open(meta).map(index => (meta, index))
                      }
        } yield (result._2, Some(result))
    }

  private def keysOf(index: LMDBVectorIndex[String], deckId: String): Task[Chunk[String]] =
    index.collection.streamPrefixWithKeys(s"$deckId#").map(_._1).runCollect.mapError(toThrowable)

  /** Computes and stores the vectors of the slides of a deck, replacing the previous ones. */
  def update(deck: Deck, content: DeckContent): Task[Unit] = {
    val slides = content.slides.filter(slide => (slide.title + slide.text + slide.notes).trim.length > 2)
    val texts  = slides.map(slide => Embeddings.slideText(deck, slide))
    for {
      vectors <- ZIO.foreach(texts.grouped(settings.batchSize.max(1)).toList)(embed).map(_.flatten)
      _       <- ZIO.foreachDiscard(vectors.headOption) { first =>
                   for {
                     index  <- prepare(first.length)
                     keys    = slides.zip(vectors).map((slide, vector) => SlideHit.key(deck.id, slide.h, slide.v) -> vector)
                     _      <- ZIO.foreachDiscard(keys)((key, vector) => index.insert(key, vector).mapError(toThrowable))
                     stored <- keysOf(index, deck.id)
                     wanted  = keys.map(_._1).toSet
                     _      <- ZIO.foreachDiscard(stored.filterNot(wanted.contains))(key => index.delete(key).mapError(toThrowable))
                   } yield ()
                 }
      _       <- ZIO.when(vectors.isEmpty)(remove(deck.id))
    } yield ()
  }

  def remove(deckId: String): Task[Unit] =
    index.flatMap(ZIO.foreachDiscard(_)(index => keysOf(index, deckId).flatMap(ZIO.foreachDiscard(_)(key => index.delete(key).mapError(toThrowable)))))

  /** The slides closest to the query, by meaning ; nothing when the server is unavailable. */
  def search(query: String, limit: Int): Task[List[SlideHit]] =
    if (!enabled || query.trim.length < 3) ZIO.succeed(Nil)
    else
      index.flatMap {
        case None        => ZIO.succeed(Nil)
        case Some(index) =>
          embed(List(Embeddings.queryText(model, query))).flatMap { vectors =>
            index
              .searchNearest(vectors.head, limit)
              .mapError(toThrowable)
              .map(_.toList.flatMap((key, distance) => SlideHit.parseKey(key).map((deck, h, v) => SlideHit(deck, h, v, 1.0 - distance))))
          }
      }

  def count: Task[Long] = index.flatMap(ZIO.foreach(_)(_.size().mapError(toThrowable))).map(_.getOrElse(0L))
}

object Embeddings {

  /** Bumped when the embedded text changes : the vectors are computed again. */
  val TextVersion = "1"

  def digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8")).map("%02x".format(_)).mkString

  /** The text a slide is embedded from : where it is, its title, its text and its notes. */
  def slideText(deck: Deck, slide: Slide): String =
    List(s"${deck.title} › ${slide.title}", slide.text, fr.janalyse.slides.core.DeckParser.plainText(slide.notes)).map(_.trim).filter(_.nonEmpty).mkString("\n").take(2000)

  /** Instruction aware models (Qwen3 embeddings, the e5 and gte instruct ones) compare a query better to passages when told the task. */
  def queryText(model: String, query: String): String =
    if (model.toLowerCase.matches(".*(qwen|instruct).*")) s"Instruct: Given a search query, retrieve the presentation slides that answer it\nQuery: ${query.trim}"
    else query.trim

  val layer: ZLayer[AppConfig & Store, Nothing, Embeddings] =
    ZLayer.fromZIO(for {
      config    <- ZIO.service[AppConfig]
      store     <- ZIO.service[Store]
      current   <- Ref.Synchronized.make(Option.empty[(VectorMeta, LMDBVectorIndex[String])])
      available <- Ref.make(Option.empty[Boolean])
    } yield Embeddings(config, store, current, available))
}
