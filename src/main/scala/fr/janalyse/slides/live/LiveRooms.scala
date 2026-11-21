package fr.janalyse.slides.live

import zio.*
import zio.stream.*

import java.security.SecureRandom
import java.time.Instant

/** Where the presenter is in the deck.
  *
  * @param f
  *   how many fragments of the slide are shown, -1 for none
  * @param seq
  *   increases with each change, so that the windows ignore the older ones
  * @param paused
  *   the screen is blacked out
  */
case class LiveState(h: Int, v: Int, f: Int, overview: Boolean, paused: Boolean, seq: Long, at: Instant)

/** What a room tells its followers : the presenter position, how many follow, or that it is over. */
case class LiveMessage(kind: String, room: String, deckId: String, state: LiveState, followers: Int)

/** A presentation given live : participants follow the presenter, who alone holds the token to drive it. */
case class Room(id: String, deckId: String, token: String, state: LiveState, followers: Int, startedAt: Instant, lastActivity: Instant)

case class RoomInfo(id: String, deckId: String, state: LiveState, followers: Int, startedAt: Instant)

/** The live presentations, kept in memory only : they end with the process, or after being idle for long. */
class LiveRooms(rooms: Ref.Synchronized[Map[String, Room]], hub: Hub[LiveMessage], changes: Hub[Unit]) {

  private val random = new SecureRandom()

  private def randomId(alphabet: String, size: Int): String = (1 to size).map(_ => alphabet.charAt(random.nextInt(alphabet.length))).mkString

  // no ambiguous characters : the room code may be typed
  private val roomAlphabet  = "abcdefghjkmnpqrstuvwxyz23456789"
  private val tokenAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

  private def info(room: Room): RoomInfo = RoomInfo(room.id, room.deckId, room.state, room.followers, room.startedAt)

  /** Starts a live presentation of the deck : the room, and the token to drive it. */
  def start(deckId: String, state: Option[LiveState]): UIO[Room] =
    for {
      now  <- Clock.instant
      room <- rooms.modify { all =>
                val id   = Iterator.continually(randomId(roomAlphabet, 6)).find(id => !all.contains(id)).get
                val room = Room(id, deckId, randomId(tokenAlphabet, 32), state.map(_.copy(seq = 1, at = now)).getOrElse(LiveState(0, 0, -1, false, false, 1, now)), 0, now, now)
                (room, all + (id -> room))
              }
      _    <- changes.publish(())
    } yield room

  def list: UIO[List[RoomInfo]] = rooms.get.map(_.values.toList.sortBy(_.startedAt).reverse.map(info))

  def get(id: String): UIO[Option[RoomInfo]] = rooms.get.map(_.get(id).map(info))

  private def authorized(id: String, token: String): UIO[Option[Room]] =
    rooms.get.map(_.get(id).filter(room => java.security.MessageDigest.isEqual(room.token.getBytes, token.getBytes)))

  /** The presenter moved : the followers are told. Older states, from another presenter window, are ignored. */
  def move(id: String, token: String, h: Int, v: Int, f: Int, overview: Boolean, paused: Boolean): UIO[Option[LiveState]] =
    authorized(id, token).flatMap {
      case None    => ZIO.none
      case Some(_) =>
        for {
          now     <- Clock.instant
          updated <- rooms.modify { all =>
                       all.get(id) match {
                         case None       => (None, all)
                         case Some(room) =>
                           val state = LiveState(h.max(0), v.max(0), f.max(-1), overview, paused, room.state.seq + 1, now)
                           (Some(room.copy(state = state, lastActivity = now)), all + (id -> room.copy(state = state, lastActivity = now)))
                       }
                     }
          _       <- ZIO.foreachDiscard(updated)(room => hub.publish(LiveMessage("state", room.id, room.deckId, room.state, room.followers)))
        } yield updated.map(_.state)
    }

  def stop(id: String, token: String): UIO[Boolean] =
    authorized(id, token).flatMap {
      case None       => ZIO.succeed(false)
      case Some(room) => rooms.update(_ - id) *> hub.publish(LiveMessage("ended", id, room.deckId, room.state, 0)) *> changes.publish(()).as(true)
    }

  private def follow(id: String, delta: Int): UIO[Unit] =
    rooms
      .modify { all =>
        all.get(id) match {
          case None       => (None, all)
          case Some(room) =>
            val updated = room.copy(followers = (room.followers + delta).max(0))
            (Some(updated), all + (id -> updated))
        }
      }
      .flatMap(ZIO.foreachDiscard(_)(room => hub.publish(LiveMessage("followers", room.id, room.deckId, room.state, room.followers))))

  /** The messages of a room, starting with its current state ; each follower is counted while listening. */
  def messages(id: String, counted: Boolean): ZStream[Any, Nothing, LiveMessage] =
    ZStream.unwrapScoped {
      for {
        queue   <- hub.subscribe
        current <- rooms.get.map(_.get(id))
        _       <- ZIO.when(counted && current.isDefined)(ZIO.acquireRelease(follow(id, 1))(_ => follow(id, -1)))
      } yield current match {
        case None       => ZStream.succeed(LiveMessage("ended", id, "", LiveState(0, 0, -1, false, false, 0, Instant.EPOCH), 0))
        case Some(room) =>
          ZStream.succeed(LiveMessage("state", room.id, room.deckId, room.state, room.followers + (if (counted) 1 else 0))) ++
            ZStream.fromQueue(queue).filter(_.room == id).takeUntil(_.kind == "ended")
      }
    }

  /** Tells when rooms start or end. */
  def roomChanges: ZStream[Any, Nothing, Unit] = ZStream.fromHub(changes)

  /** Rooms nobody drove for 12 hours are closed. */
  val expire: UIO[Nothing] =
    (for {
      now     <- Clock.instant
      expired <- rooms.modify(all => all.partition((_, room) => room.lastActivity.isBefore(now.minusSeconds(12 * 3600))) match { case (old, kept) => (old.values.toList, kept) })
      _       <- ZIO.foreachDiscard(expired)(room => hub.publish(LiveMessage("ended", room.id, room.deckId, room.state, 0)))
      _       <- ZIO.when(expired.nonEmpty)(changes.publish(()))
    } yield ()).repeat(Schedule.spaced(10.minutes)) *> ZIO.never
}

object LiveRooms {
  val layer: ZLayer[Any, Nothing, LiveRooms] =
    ZLayer.fromZIO(for {
      rooms   <- Ref.Synchronized.make(Map.empty[String, Room])
      hub     <- Hub.sliding[LiveMessage](1024)
      changes <- Hub.sliding[Unit](64)
    } yield LiveRooms(rooms, hub, changes))
}
