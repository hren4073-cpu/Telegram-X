package org.thunderdog.challegram.autopoll

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import autopoll.*
import org.drinkless.tdlib.TdApi
import org.thunderdog.challegram.MainActivity
import org.thunderdog.challegram.telegram.*

/** All decisions, callbacks, history recovery and disk writes run on one queue. */
class AutoPollService : Service(), MessageListener, ConnectionListener {
  private lateinit var queue: Handler
  private lateinit var worker: HandlerThread
  private lateinit var config: AutoPollConfig
  private lateinit var journal: VoteJournal
  private var tdlib: Tdlib?=null
  private val ledgerAccount: Long get() = tdlib?.myUserId() ?: 0L
  private var activeAccount=0
  private var activeChat=0L
  private val coordinator=PollCoordinator()
  private val verifying=mutableMapOf<String,Long>()
  private var wake: PowerManager.WakeLock?=null
  private val engine=ShiftSelectionEngine()
  private val pollListener = PollListener { poll -> queue.post { onPollUpdate(poll) } }
  private val watched=mutableSetOf<Long>()
  override fun onBind(intent: Intent?) = null
  override fun onCreate() {
    super.onCreate()
    config=AutoPollConfig(this); journal=VoteJournal(this)
    worker=HandlerThread("AutoPoll").apply { start() }; queue=Handler(worker.looper)
  }
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (!config.enabled || config.chat==0L) { stopSelf(); return START_NOT_STICKY }
    showNotification("Starting monitoring")
    queue.post {
      if(tdlib==null) {
        activeAccount=config.account; activeChat=config.chat
        tdlib=TdlibManager.getTdlib(activeAccount).also {
          it.setAutoPollActive(true)
          it.listeners().subscribeToMessageUpdates(activeChat,this)
          it.listeners().subscribeToConnectivityUpdates(this)
        }
        wake=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"$packageName:AutoPoll").apply { acquire() }
        // A crash after SUBMITTING is ambiguous even when no acknowledgement was persisted.
        journal.uncertainRecords(ledgerAccount).forEach {
          val r=it.copy(state=VoteState.UNKNOWN,reason="RECOVERING_UNCERTAIN_RESULT")
          journal.save(ledgerAccount,r); watch(r.snapshot.poll); verify(r)
        }
        scanHistory()
        queue.post(heartbeat)
        queue.postDelayed(scanner,RecoverySchedule.intervalMillis(config.scanSeconds))
      }
    }
    return START_STICKY
  }
  private val heartbeat=object: Runnable {
    override fun run() {
      config.heartbeat=System.currentTimeMillis()
      showNotification(if(tdlib?.isConnected == true) "Connected · ${if(config.dryRun) "Dry Run" else "Live Voting"}" else "Waiting for network")
      journal.uncertainRecords(ledgerAccount).forEach { verify(it) }
      queue.postDelayed(this,60_000L)
    }
  }
  private val scanner=object: Runnable {
    override fun run() {
      scanHistory()
      queue.postDelayed(this,RecoverySchedule.intervalMillis(config.scanSeconds))
    }
  }
  override fun onConnectionStateChanged(newState: Int,oldState: Int) { queue.post { if(tdlib?.isConnected==true) scanHistory() } }
  override fun onNewMessage(message: TdApi.Message) {
    val received=SystemClock.elapsedRealtimeNanos()
    queue.post { receive(message.chatId,message.id,message.content,received,false) }
  }
  override fun onMessageContentChanged(chatId: Long,messageId: Long,newContent: TdApi.MessageContent) {
    val received=SystemClock.elapsedRealtimeNanos()
    queue.post { receive(chatId,messageId,newContent,received,false) }
  }
  private fun snapshot(chat: Long,message: Long,p: TdApi.Poll)=PollSnapshot(chat,message,p.id,
    p.options.map { it.text.text },p.isClosed,p.options.indices.filter { p.options[it].isChosen },
    p.options.any { it.isBeingChosen },p.type is TdApi.PollTypeRegular && !p.allowsMultipleAnswers && p.voteRestrictionReason == null && (p.optionOrder.isEmpty() || p.optionOrder.toList() == p.options.indices.toList()), p.options.map { it.id })
  private fun receive(chat: Long,message: Long,content: TdApi.MessageContent,received: Long,recovery: Boolean) {
    if(ledgerAccount==0L || chat!=activeChat || !config.enabled || config.account!=activeAccount || config.chat!=activeChat) return
    val p=(content as? TdApi.MessagePoll)?.poll ?: return
    val snap=snapshot(chat,message,p); val key=VoteJournal.key(ledgerAccount,snap)
    val old=journal.get(key)
    if(!coordinator.tryStart(key,old?.state)) {
      if(old!=null && old.state in uncertain && !coordinator.isActive(key)) verify(old)
      return
    }
    val parsed=SmartPollParser.parse(snap.texts)
    val reject=VoteProtocol.rejection(snap)
    val decision=if(reject==null) engine.propose(parsed,journal.proposalBag(ledgerAccount),config.mode,config.fixed,config.nextFree) else null
    if(decision!=null && config.mode==Mode.SHUFFLE) journal.seedBag(ledgerAccount,decision.slot,decision.nextBag)
    var r=VoteRecord(key,snap,decision?.slot?.ordinal ?: -1,decision?.nextBag ?: emptyList(),VoteState.DETECTED,System.currentTimeMillis(),0,if(recovery) "HISTORY_RECOVERY" else "EVENT",mode=config.mode)
    journal.save(ledgerAccount,r)
    if(reject!=null || decision==null) {
      journal.save(ledgerAccount,r.copy(state=VoteProtocol.skippedState(reject),reason=reject ?: "NO_ELIGIBLE_SLOT"))
      coordinator.finish(key);return
    }
    r=r.copy(state=VoteState.VALIDATED);journal.save(ledgerAccount,r)
    if(config.dryRun) { journal.save(ledgerAccount,r.copy(state=VoteState.SKIPPED,reason="DRY_RUN",latencyMs=(SystemClock.elapsedRealtimeNanos()-received)/1_000_000));coordinator.finish(key);return }
    // Durability barrier before refresh and any possible send. Never replay this state after a crash.
    r=r.copy(state=VoteState.SUBMITTING);journal.save(ledgerAccount,r)
    val reservation=r
    tdlib!!.client().send(TdApi.GetMessage(chat,message)) { result -> queue.post {
      val current=(result as? TdApi.Message)?.let { m -> (m.content as? TdApi.MessagePoll)?.let { snapshot(m.chatId,m.id,it.poll) } }
      val why=if(current==null) "REFRESH_FAILED_NO_SEND" else VoteProtocol.revalidate(reservation.snapshot,current,reservation.index)
      if(why!=null || !config.enabled || config.dryRun || config.account!=activeAccount || config.chat!=activeChat) {
        journal.save(ledgerAccount,reservation.copy(state=VoteProtocol.skippedState(why),reason=why ?: "MONITORING_CHANGED"));coordinator.finish(key);return@post
      }
      val sending=reservation.copy(latencyMs=(SystemClock.elapsedRealtimeNanos()-received)/1_000_000,submittedAt=System.currentTimeMillis())
      journal.save(ledgerAccount,sending)
      watch(p.id)
      tdlib!!.client().send(TdApi.SetPollAnswer(chat,message,intArrayOf(reservation.index))) { answer -> queue.post {
        // Even an error can be ambiguous after network loss. Reconcile by reading; never resend.
        val latest=journal.get(key)!!
        if(latest.state!=VoteState.VERIFIED) {
          val pending=latest.copy(state=if(answer is TdApi.Error) VoteState.UNKNOWN else VoteState.PENDING,
            reason=if(answer is TdApi.Error) "TDLIB_ERROR_${answer.code}" else "AWAITING_CHOSEN_OPTION")
          journal.save(ledgerAccount,pending);verify(pending)
        }
        coordinator.finish(key)
      } }
    } }
  }
  private fun watch(poll: Long) { if(watched.add(poll)) tdlib?.listeners()?.addPollListener(poll,pollListener) }
  private fun onPollUpdate(poll: TdApi.Poll) {
    journal.uncertainRecords(ledgerAccount).filter { it.snapshot.poll==poll.id }.forEach {
      reconcile(it,snapshot(it.snapshot.chat,it.snapshot.message,poll))
    }
  }
  private fun reconcile(r: VoteRecord,live: PollSnapshot) {
    val latest=journal.get(r.key) ?: return
    if(latest.state !in uncertain) return
    if(VoteProtocol.verified(live,r.snapshot,r.index)) {
      journal.save(ledgerAccount,latest.copy(state=VoteState.VERIFIED,reason="CHOSEN_OPTION_CONFIRMED",verifiedAt=System.currentTimeMillis()),true)
      tdlib?.listeners()?.removePollListener(r.snapshot.poll,pollListener);watched.remove(r.snapshot.poll)
    } else journal.save(ledgerAccount,latest.copy(state=VoteState.UNKNOWN,reason="RESULT_NOT_CONFIRMED_NO_RESEND"))
  }
  private fun verify(r: VoteRecord) {
    val client=tdlib?.client() ?: return
    val now=SystemClock.elapsedRealtime()
    val previous=verifying[r.key]
    if(previous!=null && now-previous<30_000L) return
    verifying[r.key]=now
    client.send(TdApi.GetMessage(r.snapshot.chat,r.snapshot.message)) { result -> queue.post {
      if(verifying[r.key]==now) verifying.remove(r.key)
      val m=result as? TdApi.Message
      val p=m?.content as? TdApi.MessagePoll
      if(m!=null && p!=null) reconcile(r,snapshot(m.chatId,m.id,p.poll))
    } }
  }
  private var scanning=false
  private var scanAgain=false
  private fun scanHistory() {
    if(!config.enabled) return
    if(scanning) { scanAgain=true;return }
    scanning=true
    // Bounded catch-up only; event listeners are the primary detection path.
    tdlib?.client()?.send(TdApi.GetChatHistory(activeChat,0,0,100,false)) { result -> queue.post {
      scanning=false
      (result as? TdApi.Messages)?.messages?.filterNotNull()?.reversed()?.forEach { m ->
        // Never vote in old shifts when enabling a new target. Catch up at most the last day.
        if(System.currentTimeMillis()/1000-m.date<=86400) receive(m.chatId,m.id,m.content,SystemClock.elapsedRealtimeNanos(),true)
      }
      if(scanAgain) { scanAgain=false;scanHistory() }
    } }
  }
  private fun showNotification(text: String) {
    val nm=getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
    if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(NotificationChannel("autopoll","AutoPoll monitoring",android.app.NotificationManager.IMPORTANCE_LOW))
    val open=PendingIntent.getActivity(this,0,Intent(this,AutoPollSettingsActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val builder=if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,"autopoll") else Notification.Builder(this)
    val n=builder.setSmallIcon(android.R.drawable.ic_menu_recent_history).setContentTitle("AutoPoll").setContentText(text).setContentIntent(open).setOngoing(true).build()
    startForeground(47073,n)
  }
  override fun onDestroy() {
    queue.post {
      queue.removeCallbacksAndMessages(null)
      tdlib?.let { t ->
        t.listeners().unsubscribeFromMessageUpdates(activeChat,this)
        t.listeners().unsubscribeFromConnectivityUpdates(this)
        watched.forEach { t.listeners().removePollListener(it,pollListener) }
        t.setAutoPollActive(false)
      }
      wake?.let { if(it.isHeld) it.release() }; config.heartbeat=0
      journal.close();worker.quitSafely()
    }
    super.onDestroy()
  }
  companion object {
    val uncertain=setOf(VoteState.SUBMITTING,VoteState.PENDING,VoteState.UNKNOWN)
    fun start(context: Context) {
      val i=Intent(context,AutoPollService::class.java)
      if(Build.VERSION.SDK_INT>=26) context.startForegroundService(i) else context.startService(i)
    }
  }
}
