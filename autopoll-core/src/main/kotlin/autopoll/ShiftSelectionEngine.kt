package autopoll

import kotlin.random.Random

enum class Mode { SHUFFLE, RANDOM, FIXED }
data class Decision(val slot: Slot, val nextBag: List<Slot>)

object BagReservations {
  fun available(committed: List<Slot>, pending: List<Slot>): List<Slot> =
    committed.filterNot { it in pending }

  fun afterVerification(committed: List<Slot>, chosen: Slot): List<Slot> =
    committed.filterNot { it==chosen }
}

/** Pure proposal: persist the reservation before sending; consume a slot only on verification. */
class ShiftSelectionEngine(private val random: Random = Random.Default) {
  fun propose(poll: ParsedPoll, bag: List<Slot>, mode: Mode = Mode.SHUFFLE,
              fixed: Slot = Slot.SLOT_09, nextFree: Boolean = true): Decision? {
    if (!poll.valid || poll.available.isEmpty()) return null
    if (mode == Mode.FIXED) return if (fixed in poll.available) Decision(fixed, bag) else null
    var candidates = poll.available
    if (nextFree && poll.reserved.isNotEmpty()) {
      val pairs = poll.reserved.flatMap { busy -> candidates.filter { it.hour > busy.hour }.map { free -> free to (free.hour - busy.hour) } }
      val distance = pairs.minOfOrNull { it.second }
      if (distance != null) candidates = pairs.filter { it.second == distance }.map { it.first }.distinct()
    }
    if (mode == Mode.RANDOM) return Decision(candidates.random(random), bag)
    // Eligible slots absent from the current cycle wait until its remaining eligible slots are used.
    val pool = if (bag.any { it in candidates }) bag else Slot.entries.shuffled(random)
    val chosen = pool.first { it in candidates }
    return Decision(chosen, pool.filter { it != chosen })
  }
}
