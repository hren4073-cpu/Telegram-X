package autopoll

enum class VoteState { DETECTED, VALIDATED, SUBMITTING, PENDING, VERIFIED, UNKNOWN, FAILED, SKIPPED, EXPIRED }
data class PollSnapshot(val chat: Long, val message: Long, val poll: Long, val texts: List<String>,
                        val closed: Boolean, val chosen: List<Int>, val pending: Boolean, val regular: Boolean, val optionIds: List<String> = emptyList())
object VoteProtocol {
  fun key(account: Long, p: PollSnapshot): String = "$account:${p.chat}:${p.message}:${p.poll}"
  fun skippedState(reason: String?): VoteState = if(reason=="POLL_CLOSED") VoteState.EXPIRED else VoteState.SKIPPED
  fun rejection(p: PollSnapshot): String? = when {
    p.closed -> "POLL_CLOSED"
    !p.regular -> "UNSUPPORTED_POLL_TYPE"
    p.chosen.isNotEmpty() || p.pending -> "ALREADY_ANSWERED"
    !SmartPollParser.parse(p.texts).valid -> "UNKNOWN_TEMPLATE"
    SmartPollParser.parse(p.texts).available.isEmpty() -> "NO_AVAILABLE_SLOTS"
    else -> null
  }
  fun revalidate(before: PollSnapshot, live: PollSnapshot, index: Int): String? = when {
    before.chat != live.chat || before.message != live.message || before.poll != live.poll -> "IDENTITY_CHANGED"
    before.texts != live.texts || before.optionIds != live.optionIds -> "OPTIONS_CHANGED"
    index !in 0..2 -> "FORBIDDEN_INDEX"
    else -> rejection(live) ?: if (SmartPollParser.parse(live.texts).options[index].status != OptionStatus.AVAILABLE) "SLOT_RESERVED" else null
  }
  fun verified(live: PollSnapshot, expected: PollSnapshot, index: Int): Boolean =
    live.chat == expected.chat && live.message == expected.message && live.poll == expected.poll &&
      live.texts == expected.texts && live.optionIds == expected.optionIds && !live.pending && live.chosen == listOf(index)
  fun maySubmit(state: VoteState) = state == VoteState.VALIDATED
}
