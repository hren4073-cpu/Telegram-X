package autopoll

enum class Slot(val hour: Int) { SLOT_08(8), SLOT_09(9), SLOT_10(10) }
enum class OptionStatus { AVAILABLE, RESERVED, EXCLUDED, INVALID }
data class ParsedOption(val index: Int, val text: String, val slot: Slot?, val status: OptionStatus)
data class ParsedPoll(val options: List<ParsedOption>, val valid: Boolean) {
  val available get() = options.filter { it.status == OptionStatus.AVAILABLE }.mapNotNull { it.slot }
  val reserved get() = options.filter { it.status == OptionStatus.RESERVED }.mapNotNull { it.slot }
}

/** Only leading shift times count. Unknown suffixes fail closed, without a name dictionary. */
object SmartPollParser {
  private val time = Regex("^(?:[сСcC]\\s*)?(0?[89]|10)[:.]00(?![\\p{N}:.,])")
  private val neutral = Regex("^[\\s\\p{Zs}.,;:!—–-]*$")
  private fun normalize(s: String) = s.replace('\u00a0', ' ').replace('\u202f', ' ').trim()
  @JvmStatic fun parse(texts: List<String>): ParsedPoll {
    val parsed = texts.mapIndexed { i, raw ->
      if (i >= 3) ParsedOption(i, raw, null, OptionStatus.EXCLUDED)
      else {
        val s = normalize(raw)
        val m = time.find(s)
        val slot = m?.groupValues?.get(1)?.toInt()?.let { h -> Slot.entries.first { it.hour == h } }
        val status = when {
          m == null -> OptionStatus.INVALID
          neutral.matches(s.substring(m.value.length)) -> OptionStatus.AVAILABLE
          else -> OptionStatus.RESERVED
        }
        ParsedOption(i, raw, slot, status)
      }
    }
    // Strict five-option template and ordered first three shifts. Extra times in 4/5 never qualify.
    return ParsedPoll(parsed, texts.size == 5 && parsed.take(3).map { it.slot } == Slot.entries)
  }
}
