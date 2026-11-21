package fr.janalyse.slides.search

import fr.janalyse.slides.model.*
import fr.janalyse.slides.store.Store
import zio.*

import java.text.Normalizer

/** A matching slide.
  *
  * @param words
  *   found by its words
  * @param meaning
  *   found by its meaning (embeddings)
  */
case class SlideMatch(h: Int, v: Int, title: String, snippet: String, words: Boolean, meaning: Boolean, score: Double)

case class DeckMatch(deckId: String, score: Double, slides: List[SlideMatch])

/** @param terms the query words as indexed, to highlight them
  * @param relaxed no slide has all the words : those having some of them are given
  * @param semantic the search by meaning took part
  */
case class SearchResults(query: String, terms: List[String], relaxed: Boolean, semantic: Boolean, decks: List[DeckMatch])

/** The hybrid search : by words (Lucene) and by meaning (embeddings), both ranked lists merged by reciprocal rank fusion, then grouped by deck. */
class SearchService(store: Store, words: WordIndex, embeddings: Embeddings) {

  import SearchService.*

  def search(query: String, sources: Set[String], limit: Int): Task[SearchResults] =
    for {
      lexical  <- words.search(query, sources, 200)
      semantic <- embeddings.search(query, 100).map(closest).catchAll(err => ZIO.logDebug(s"Search by meaning unavailable : ${err.getMessage}").as(Nil))
      inSource  = semantic.filter(hit => sources.isEmpty || sources.exists(source => hit.deckId.startsWith(s"$source:")))
      fused     = fuse(List(lexical._1.map(_.key) -> WordsWeight, inSource.map(_.key) -> MeaningWeight))
      byWords   = lexical._1.map(_.key).toSet
      byMeaning = inSource.map(_.key).toSet
      terms     = words.queryTerms(query)
      grouped   = fused.toList
                    .flatMap((key, score) => SlideHit.parseKey(key).map((deck, h, v) => (deck, h, v, score)))
                    .groupBy(_._1)
                    .toList
                    .map { (deckId, slides) =>
                      val ranked = slides.sortBy(-_._4)
                      (deckId, ranked.take(3).map(_._4).sum, ranked.take(6))
                    }
                    .sortBy(-_._2)
                    .take(limit)
      decks    <- ZIO.foreach(grouped) { (deckId, score, slides) =>
                    store.content(deckId).map { content =>
                      val byPosition = content.toList.flatMap(_.slides).map(slide => (slide.h, slide.v) -> slide).toMap
                      val matches    = slides.flatMap { (_, h, v, slideScore) =>
                        byPosition.get((h, v)).map { slide =>
                          val key = SlideHit.key(deckId, h, v)
                          SlideMatch(h, v, slide.title, snippet(slide, terms), byWords.contains(key), byMeaning.contains(key), slideScore)
                        }
                      }
                      DeckMatch(deckId, score, matches)
                    }
                  }
    } yield SearchResults(query, terms, lexical._2, inSource.nonEmpty, decks.filter(_.slides.nonEmpty))
}

object SearchService {

  /** Reciprocal rank fusion : each list gives `weight / (k + rank)` to its items, the lists agreeing on an item rank it first. */
  def fuse(rankings: List[(List[String], Double)], k: Int = 60): Map[String, Double] =
    rankings.flatMap((keys, weight) => keys.distinct.zipWithIndex.map((key, rank) => key -> weight / (k + rank + 1))).groupMapReduce(_._1)(_._2)(_ + _)

  /** The words typed beat a closeness of meaning : a slide having them ranks before a slide only about the same subject. */
  val WordsWeight   = 1.0
  val MeaningWeight = 0.7

  /** The slides nearly as close as the closest one : the farther ones are noise, a query having no meaningful match finds nothing. */
  def closest(hits: List[SlideHit]): List[SlideHit] = {
    val sorted = hits.sortBy(-_.score)
    sorted.headOption match {
      case None       => Nil
      case Some(best) => sorted.filter(hit => hit.score >= 0.35 && hit.score >= best.score - 0.15)
    }
  }

  private def fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase

  /** A short excerpt of the slide around the first query word found, its whole start otherwise. */
  def snippet(slide: Slide, terms: List[String], size: Int = 180): String = {
    val text   = (slide.text + "\n" + slide.notes).replaceAll("""\s+""", " ").trim
    // the folded text keeps the same length as long as the accents are combining characters
    val folded = fold(text)
    val at     = if (folded.length == text.length) terms.flatMap(term => Option(folded.indexOf(term)).filter(_ >= 0)).minOption else None
    at match {
      case Some(position) if position > size / 3 =>
        val start = text.lastIndexOf(' ', position - size / 3).max(0)
        val cut   = text.substring(start, (start + size).min(text.length))
        "…" + cut.trim + (if (start + size < text.length) "…" else "")
      case _                                     => if (text.length > size) text.take(size).trim + "…" else text
    }
  }

  val layer: ZLayer[Store & WordIndex & Embeddings, Nothing, SearchService] = ZLayer.derive[SearchService]
}
