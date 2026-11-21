package fr.janalyse.slides

import fr.janalyse.slides.core.DeckParser
import fr.janalyse.slides.model.*
import fr.janalyse.slides.search.{SearchService, SlideHit, WordIndex}
import zio.*
import zio.test.*

import java.time.Instant

object SearchSpec extends ZIOSpecDefault {

  private def deck(id: String, markdown: String): (Deck, DeckContent) = {
    val parsed = DeckParser.parse(markdown, id)
    val now    = Instant.EPOCH
    val d      = Deck(id, "src", id, "", id, parsed.title, parsed.subtitle, None, Map.empty, parsed.slides.size, parsed.stacks.size, "hash", 0, now, discoveredAt = now, changedAt = now)
    (d, DeckContent(id, "hash", markdown, parsed.slides))
  }

  def spec = suite("Search")(
    test("the embeddings server is reached with basic authentication when a username is given, else with the API key") {
      assertTrue(
        EmbeddingsConfig.authorization("aladdin", Some("open sesame"), Some("key")).contains("Basic YWxhZGRpbjpvcGVuIHNlc2FtZQ=="),
        EmbeddingsConfig.authorization(" ", Some("open sesame"), Some("key")).contains("Bearer key"),
        EmbeddingsConfig.authorization("", None, None).isEmpty
      )
    },
    test("slide keys round trip, deck ids holding separators included") {
      val key = SlideHit.key("src:dir#1/index.md", 12, 3)
      assertTrue(SlideHit.parseKey(key).contains(("src:dir#1/index.md", 12, 3)))
    },
    test("reciprocal rank fusion ranks first what both lists agree on, words weighing more") {
      val fused = SearchService.fuse(List(List("a", "b", "c") -> 1.0, List("c", "d") -> 0.7))
      val order = fused.toList.sortBy(-_._2).map(_._1)
      assertTrue(order.head == "c", order.indexOf("a") < order.indexOf("d"), fused("a") == 1.0 / 61)
    },
    test("the closest slides by meaning are kept, the noise dropped") {
      val hits = List(SlideHit("a", 0, 0, 0.8), SlideHit("b", 0, 0, 0.7), SlideHit("c", 0, 0, 0.5), SlideHit("d", 0, 0, 0.2))
      assertTrue(SearchService.closest(hits).map(_.deckId) == List("a", "b"))
    },
    test("a snippet starts near the first word found, accents ignored") {
      val slide = Slide(0, 0, "", "", "", "t", ("lorem ipsum " * 20) + "la réunion de demain" + (" dolor" * 30))
      val text  = SearchService.snippet(slide, List("reunion"))
      assertTrue(text.startsWith("…"), text.contains("réunion"), text.length < 200)
    },
    test("finds slides by words, accents and case folded, typed words as prefixes") {
      val (nix, nixContent)     = deck("nix.md", "# Nix\n\n---\n\n### Réunion\n\nGénérations du système NixOS\n\n---\n\n### Other\n\nnothing")
      val (kafka, kafkaContent) = deck("kafka.md", "# Kafka basics\n\n---\n\n### Topics\n\npartitions")
      ZIO.scoped {
        for {
          index   <- WordIndex.layer.build.map(_.get)
          _       <- index.index(nix, nixContent) *> index.index(kafka, kafkaContent) *> index.refresh
          words   <- index.search("reunion generations", Set.empty, 10)
          prefix  <- index.search("partit", Set.empty, 10)
          relaxed <- index.search("generations kafka", Set.empty, 10)
          count   <- index.count
        } yield assertTrue(
          words._1.map(h => (h.deckId, h.h)) == List(("nix.md", 1)),
          !words._2,
          prefix._1.map(_.deckId) == List("kafka.md"),
          relaxed._2,
          relaxed._1.map(_.deckId).toSet == Set("nix.md", "kafka.md"),
          count == 5
        )
      }
    }
  )
}
