package autopoll

/** Only the same poll is locked while its network request is outstanding. */
class PollCoordinator {
  private val active = mutableSetOf<String>()

  fun tryStart(key: String, existing: VoteState?): Boolean =
    existing == null && active.add(key)

  fun isActive(key: String): Boolean = key in active

  fun finish(key: String) { active.remove(key) }
}
