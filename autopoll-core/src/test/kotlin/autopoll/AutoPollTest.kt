package autopoll

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class AutoPollTest {
  private fun poll(a: String = "08:00", b: String = "09:00", c: String = "10:00") =
    SmartPollParser.parse(listOf(a,b,c,"Инициативный клиент", "Выходной"))
  private fun pick(p: ParsedPoll) = ShiftSelectionEngine(Random(7)).propose(p, emptyList())?.slot
  @Test fun reservedPriorityExamples() {
    assertEquals(Slot.SLOT_10, pick(poll(b="09:00 Иванов")))
    assertEquals(Slot.SLOT_09, pick(poll(a="08:00 Петров")))
    assertTrue(pick(poll(c="10:00 Иванов")) in listOf(Slot.SLOT_08, Slot.SLOT_09))
    assertEquals(Slot.SLOT_10, pick(poll(a="08:00 Петров",b="09:00 Иванов")))
    assertEquals(Slot.SLOT_09, pick(poll(a="08:00 Петров",c="10:00 Иванов")))
    assertNull(pick(poll("08:00 Петров","09:00 Иванов","10:00 Сидоров")))
  }
  @Test fun spellings() {
    for (a in listOf("08:00","8:00","8.00","С 8:00","с 08:00", "\u00a0с\u202f8:00  "))
      for (b in listOf("09:00","9.00","с 9:00"))
        for (c in listOf("10:00","с 10:00")) assertEquals(3, poll(a,b,c).available.size)
  }
  @Test fun suffixesFailClosed() {
    for (s in listOf(" Иванов"," Петров"," — Сидоров"," (занято)"," А.Б."," / Иванов","\u200b", " /"))
      assertEquals(OptionStatus.RESERVED, poll(b="09:00$s").options[1].status)
    for (s in listOf(" "," — "," – ","; !")) assertEquals(OptionStatus.AVAILABLE, poll(b="09:00$s").options[1].status)
  }
  @Test fun invalidAndForbidden() {
    for (b in listOf("19:00","09:30","109:00","09:001","09:00123","смена 09:00","09:00:00","09:00,5")) assertFalse(poll(b=b).valid)
    assertFalse(SmartPollParser.parse(listOf("Выходной")).valid)
    assertFalse(SmartPollParser.parse(listOf("08:00","09:00","10:00")).valid)
    assertFalse(poll(a="09:00",b="08:00").valid)
    val p=SmartPollParser.parse(listOf("08:00","09:00","10:00","08:00","09:00"))
    assertEquals(listOf(OptionStatus.EXCLUDED,OptionStatus.EXCLUDED),p.options.drop(3).map { it.status })
  }
  @Test fun shuffleCyclesAndNoMutationOnProposal() {
    val e=ShiftSelectionEngine(Random(8)); var bag=emptyList<Slot>()
    repeat(20) {
      val cycle=mutableListOf<Slot>()
      repeat(3) { val d=e.propose(poll(),bag)!!; cycle+=d.slot; bag=d.nextBag }
      assertEquals(Slot.entries.toSet(),cycle.toSet()); assertTrue(bag.isEmpty())
    }
    val existing=listOf(Slot.SLOT_09,Slot.SLOT_08)
    assertNull(e.propose(poll("08:00 X","09:00 X","10:00 X"),existing))
    assertEquals(listOf(Slot.SLOT_09,Slot.SLOT_08),existing)
  }
  @Test fun fixedAndRandomStayEligible() {
    val p=poll(b="09:00 X"); val e=ShiftSelectionEngine(Random(4))
    assertNull(e.propose(p,emptyList(),Mode.FIXED,Slot.SLOT_09))
    assertEquals(Slot.SLOT_08,e.propose(p,emptyList(),Mode.FIXED,Slot.SLOT_08)!!.slot)
    repeat(100) { assertEquals(Slot.SLOT_10,e.propose(p,emptyList(),Mode.RANDOM)!!.slot) }
  }
  private fun snapshot()=PollSnapshot(1,2,3,listOf("08:00","09:00","10:00","Инициативный","Выходной"),false,emptyList(),false,true)
  @Test fun liveValidation() {
    val p=snapshot()
    assertNull(VoteProtocol.revalidate(p,p,1))
    assertEquals("POLL_CLOSED",VoteProtocol.revalidate(p,p.copy(closed=true),1))
    assertEquals("ALREADY_ANSWERED",VoteProtocol.revalidate(p,p.copy(chosen=listOf(0)),1))
    assertEquals("ALREADY_ANSWERED",VoteProtocol.revalidate(p,p.copy(pending=true),1))
    assertEquals("IDENTITY_CHANGED",VoteProtocol.revalidate(p,p.copy(poll=4),1))
    assertEquals("OPTIONS_CHANGED",VoteProtocol.revalidate(p,p.copy(texts=p.texts.reversed()),1))
    assertEquals("OPTIONS_CHANGED",VoteProtocol.revalidate(p.copy(optionIds=listOf("a")),p.copy(optionIds=listOf("b")),1))
    assertEquals("FORBIDDEN_INDEX",VoteProtocol.revalidate(p,p,4))
    assertEquals("UNSUPPORTED_POLL_TYPE",VoteProtocol.rejection(p.copy(regular=false)))
  }
  @Test fun confirmationRequiresChosenNotCommandAcceptance() {
    val p=snapshot()
    assertFalse(VoteProtocol.verified(p,p,1))
    assertFalse(VoteProtocol.verified(p.copy(chosen=listOf(1),pending=true),p,1))
    assertTrue(VoteProtocol.verified(p.copy(chosen=listOf(1)),p,1))
    assertFalse(VoteProtocol.verified(p.copy(chosen=listOf(0)),p,1))
  }
  @Test fun uncertainAndRestartStatesNeverResubmit() {
    for (s in VoteState.entries) assertEquals(s==VoteState.VALIDATED,VoteProtocol.maySubmit(VoteState.valueOf(s.name)))
  }
  @Test fun newPollsAreNotBlockedByDelayedConfirmation() {
    val coordinator=PollCoordinator()
    val first="account:chat:message-a:poll-a"
    val second="account:chat:message-b:poll-b"
    val third="account:chat:message-c:poll-c"
    assertTrue(coordinator.tryStart(first,null))
    assertFalse(coordinator.tryStart(first,null))
    assertTrue(coordinator.tryStart(second,null))
    assertTrue(coordinator.tryStart(third,null))
    assertTrue(coordinator.isActive(first))
    coordinator.finish(second)
    assertFalse(coordinator.tryStart(second,VoteState.PENDING))
    assertFalse(coordinator.tryStart(first,VoteState.UNKNOWN))
    assertTrue(coordinator.isActive(third))
  }
  @Test fun identicalTextWithNewIdentityIsIndependent() {
    val first=snapshot()
    val reposted=first.copy(message=first.message+1)
    val replacement=first.copy(message=first.message+2,poll=first.poll+1)
    assertEquals(first.texts,reposted.texts)
    val keys=listOf(first,reposted,replacement).map { VoteProtocol.key(42,it) }
    assertEquals(3,keys.toSet().size)
    val coordinator=PollCoordinator()
    assertTrue(keys.all { coordinator.tryStart(it,null) })
    assertFalse(coordinator.tryStart(keys[0],null))
    assertFalse(coordinator.tryStart(keys[0],VoteState.UNKNOWN))
  }
  @Test fun skippedReservationDoesNotConsumeShuffleBag() {
    val committed=listOf(Slot.SLOT_08,Slot.SLOT_09,Slot.SLOT_10)
    val pending=listOf(Slot.SLOT_08)
    assertEquals(listOf(Slot.SLOT_09,Slot.SLOT_10),BagReservations.available(committed,pending))
    assertEquals(committed,BagReservations.available(committed,emptyList()))
    assertEquals(listOf(Slot.SLOT_10),BagReservations.available(committed,pending+Slot.SLOT_09))
  }
  @Test fun outOfOrderConfirmationsDoNotRestoreConsumedSlots() {
    var bag=Slot.entries.toList()
    bag=BagReservations.afterVerification(bag,Slot.SLOT_10)
    assertEquals(listOf(Slot.SLOT_08,Slot.SLOT_09),bag)
    bag=BagReservations.afterVerification(bag,Slot.SLOT_08)
    assertEquals(listOf(Slot.SLOT_09),bag)
  }
  @Test fun recoveryIntervalIsBoundedWithoutDelayingEvents() {
    assertEquals(90_000L,RecoverySchedule.intervalMillis(0))
    assertEquals(90_000L,RecoverySchedule.intervalMillis(90))
    assertEquals(105_000L,RecoverySchedule.intervalMillis(105))
    assertEquals(120_000L,RecoverySchedule.intervalMillis(120))
    assertEquals(120_000L,RecoverySchedule.intervalMillis(999))
    val coordinator=PollCoordinator()
    assertTrue(coordinator.tryStart("event-after-offline",null))
  }
  @Test fun closedPollIsExpiredEvenDuringRevalidation() {
    val p=snapshot()
    assertEquals(VoteState.EXPIRED,VoteProtocol.skippedState(VoteProtocol.rejection(p.copy(closed=true))))
    assertEquals(VoteState.EXPIRED,VoteProtocol.skippedState(VoteProtocol.revalidate(p,p.copy(closed=true),1)))
  }
}
