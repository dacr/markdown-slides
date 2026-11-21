package fr.janalyse.slides.search

import fr.janalyse.slides.core.DeckParser
import fr.janalyse.slides.model.*
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.analysis.fr.FrenchAnalyzer
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter
import org.apache.lucene.analysis.standard.StandardTokenizer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.util.ElisionFilter
import org.apache.lucene.analysis.{Analyzer, CharArraySet, LowerCaseFilter, StopFilter, TokenStream}
import org.apache.lucene.document.*
import org.apache.lucene.index.*
import org.apache.lucene.queryparser.classic.{MultiFieldQueryParser, ParseException, QueryParser, QueryParserBase}
import org.apache.lucene.search.*
import org.apache.lucene.store.ByteBuffersDirectory
import zio.*

import scala.jdk.CollectionConverters.*

/** A slide matching a query. */
case class SlideHit(deckId: String, h: Int, v: Int, score: Double) {
  def key: String = SlideHit.key(deckId, h, v)
}

object SlideHit {
  def key(deckId: String, h: Int, v: Int): String = f"$deckId#$h%04d#$v%04d"

  def parseKey(key: String): Option[(String, Int, Int)] =
    key.split('#').toList.reverse match {
      case v :: h :: rest if rest.nonEmpty => for { hh <- h.toIntOption; vv <- v.toIntOption } yield (rest.reverse.mkString("#"), hh, vv)
      case _                               => None
    }
}

/** Embedded full text search, one document per slide : a derived view of the database, kept in memory and rebuilt at each start. */
class WordIndex(writer: IndexWriter, searcherManager: SearcherManager) {

  import WordIndex.*

  private val analyzer = writer.getAnalyzer

  private def text(doc: Document, field: String, value: String): Unit =
    if (value != null && value.nonEmpty) doc.add(new TextField(field, value, Field.Store.NO))

  def documents(deck: Deck, content: DeckContent): List[Document] =
    content.slides.map { slide =>
      val doc = new Document()
      doc.add(new StringField(FieldDeck, deck.id, Field.Store.YES))
      doc.add(new StoredField(FieldH, slide.h))
      doc.add(new StoredField(FieldV, slide.v))
      doc.add(new StringField(FieldSource, deck.source, Field.Store.NO))
      text(doc, FieldTitle, slide.title)
      text(doc, FieldDeckTitle, s"${deck.title}\n${deck.subtitle}")
      text(doc, FieldText, slide.text)
      text(doc, FieldNotes, DeckParser.plainText(slide.notes))
      text(doc, FieldPath, deck.path.replaceAll("[-_./]", " "))
      deck.date.foreach(date => text(doc, FieldWhen, s"$date ${date.take(4)}"))
      doc
    }

  /** Replaces whatever was indexed for the deck. */
  def index(deck: Deck, content: DeckContent): Task[Unit] =
    ZIO.attemptBlocking {
      writer.deleteDocuments(new Term(FieldDeck, deck.id))
      writer.addDocuments(documents(deck, content).asJava)
      ()
    }

  def remove(deckId: String): Task[Unit] = ZIO.attemptBlocking { writer.deleteDocuments(new Term(FieldDeck, deckId)); () }

  /** Makes the latest changes visible to the searches. */
  def refresh: Task[Unit] = ZIO.attemptBlocking { writer.commit(); searcherManager.maybeRefresh(); () }

  def count: Task[Int] = withSearcher(_.getIndexReader.numDocs())

  private def withSearcher[A](f: IndexSearcher => A): Task[A] =
    ZIO.acquireReleaseWith(ZIO.attemptBlocking { searcherManager.maybeRefresh(); searcherManager.acquire() })(searcher =>
      ZIO.attemptBlocking(searcherManager.release(searcher)).orDie
    )(searcher => ZIO.attemptBlocking(f(searcher)))

  private val boosts = Map[String, java.lang.Float](
    FieldTitle     -> 4f,
    FieldDeckTitle -> 2f,
    FieldText      -> 1f,
    FieldNotes     -> 0.6f,
    FieldPath      -> 1f,
    FieldWhen      -> 1f
  )

  private def parser(operator: QueryParser.Operator): QueryParser = {
    val parser = new MultiFieldQueryParser(boosts.keys.toArray, analyzer, boosts.asJava)
    parser.setDefaultOperator(operator)
    parser.setAllowLeadingWildcard(false)
    parser
  }

  private def userQuery(query: String, operator: QueryParser.Operator): Query = {
    val trimmed = query.trim
    try parser(operator).parse(trimmed)
    catch { case _: ParseException => parser(operator).parse(QueryParserBase.escape(trimmed)) }
  }

  /** The slides matching all the words, or any of them when none has them all. Words typed so far match as prefixes, for a search as you type. */
  def search(query: String, sources: Set[String], limit: Int): Task[(List[SlideHit], Boolean)] =
    if (query.trim.isEmpty) ZIO.succeed((Nil, false))
    else
      withSearcher { searcher =>
        def run(operator: QueryParser.Operator): TopDocs = {
          val builder = new BooleanQuery.Builder()
          val typed   = userQuery(query, operator)
          // the last word, still being typed, as a prefix
          val last    = queryTerms(query).lastOption.filter(_ => query.matches("""(?s).*\w$""") && !query.contains("\""))
          val main    = last match {
            case Some(term) if term.length >= 2 =>
              val any = new BooleanQuery.Builder()
              any.add(typed, BooleanClause.Occur.SHOULD)
              val prefixed = userQuery(query.trim + "*", operator)
              any.add(new BoostQuery(prefixed, 0.5f), BooleanClause.Occur.SHOULD)
              any.build()
            case _                              => typed
          }
          builder.add(main, BooleanClause.Occur.MUST)
          if (sources.nonEmpty) {
            val any = new BooleanQuery.Builder()
            sources.foreach(source => any.add(new TermQuery(new Term(FieldSource, source)), BooleanClause.Occur.SHOULD))
            builder.add(any.build(), BooleanClause.Occur.FILTER)
          }
          searcher.search(builder.build(), limit)
        }
        val strict  = run(QueryParser.Operator.AND)
        val relaxed = strict.scoreDocs.isEmpty && query.trim.contains(' ')
        val top     = if (relaxed) run(QueryParser.Operator.OR) else strict
        val stored  = searcher.storedFields()
        val hits    = top.scoreDocs.toList.map { scoreDoc =>
          val doc = stored.document(scoreDoc.doc)
          SlideHit(doc.get(FieldDeck), doc.getField(FieldH).numericValue().intValue(), doc.getField(FieldV).numericValue().intValue(), scoreDoc.score.toDouble)
        }
        (hits, relaxed)
      }

  /** The query words, analyzed like the indexed text : to highlight the matches. */
  def queryTerms(query: String): List[String] = WordIndex.terms(analyzer, query)

  def close(): Unit = {
    searcherManager.close()
    writer.close()
  }
}

object WordIndex {
  val FieldDeck      = "deck"
  val FieldH         = "h"
  val FieldV         = "v"
  val FieldSource    = "source"
  val FieldTitle     = "title"
  val FieldDeckTitle = "decktitle"
  val FieldText      = "text"
  val FieldNotes     = "notes"
  val FieldPath      = "path"
  val FieldWhen      = "when"

  /** French and english words too common to tell anything. */
  val stopWords: CharArraySet = {
    val all = new CharArraySet(FrenchAnalyzer.getDefaultStopSet, true)
    all.addAll(EnglishAnalyzer.ENGLISH_STOP_WORDS_SET)
    CharArraySet.unmodifiableSet(all)
  }

  private val articles = new CharArraySet(java.util.Arrays.asList("l", "m", "t", "qu", "n", "s", "j", "d", "c", "jusqu", "quoiqu", "lorsqu", "puisqu"), true)

  /** Lower cased, french elisions (l'équipe) and common words removed, accents folded so that "réunion" matches "reunion", for french as well as english content. */
  class FoldingAnalyzer extends Analyzer {
    override protected def createComponents(fieldName: String): Analyzer.TokenStreamComponents = {
      val tokenizer           = new StandardTokenizer()
      val stream: TokenStream = new ASCIIFoldingFilter(new StopFilter(new ElisionFilter(new LowerCaseFilter(tokenizer), articles), stopWords))
      new Analyzer.TokenStreamComponents(tokenizer, stream)
    }
    override protected def normalize(fieldName: String, in: TokenStream): TokenStream          = new ASCIIFoldingFilter(new LowerCaseFilter(in))
  }

  def terms(analyzer: Analyzer, query: String): List[String] = {
    val found    = scala.collection.mutable.LinkedHashSet.empty[String]
    val stripped = query.replaceAll("""\b\w+:""", " ").replaceAll("""["()*?~^+\-]""", " ").replaceAll("""\b(AND|OR|NOT)\b""", " ")
    val stream   = analyzer.tokenStream(FieldText, stripped)
    try {
      val attribute = stream.addAttribute(classOf[CharTermAttribute])
      stream.reset()
      while (stream.incrementToken()) found += attribute.toString
      stream.end()
    } finally stream.close()
    found.toList
  }

  val layer: ZLayer[Scope, Throwable, WordIndex] =
    ZLayer.fromZIO(
      ZIO.acquireRelease(ZIO.attemptBlocking {
        val writer = new IndexWriter(new ByteBuffersDirectory(), new IndexWriterConfig(new FoldingAnalyzer()))
        new WordIndex(writer, new SearcherManager(writer, null))
      })(index => ZIO.attemptBlocking(index.close()).orDie)
    )
}
