package org.thunderdog.challegram.autopoll

import android.Manifest
import android.app.*
import android.content.*
import android.os.*
import android.widget.*
import android.text.*
import autopoll.*
import org.drinkless.tdlib.TdApi
import org.thunderdog.challegram.telegram.TdlibManager

/** A small settings screen; Telegram X retains its normal login, account and chat interfaces. */
class AutoPollSettingsActivity : Activity() {
  private lateinit var config: AutoPollConfig
  private lateinit var layout: LinearLayout
  private val main=Handler(Looper.getMainLooper())
  override fun onCreate(state: Bundle?) {
    super.onCreate(state); title="AutoPoll Settings";config=AutoPollConfig(this)
    if(!config.enabled && intent.hasExtra("account")) config.account=intent.getIntExtra("account",config.account)
    render()
  }
  private fun text(s: String)=TextView(this).apply { text=s; setPadding(12,12,12,12);this@AutoPollSettingsActivity.layout.addView(this) }
  private fun button(s: String,act: ()->Unit)=Button(this).apply { text=s;setOnClickListener { act() };this@AutoPollSettingsActivity.layout.addView(this) }
  private fun toggle(s: String,value: Boolean,act: (Boolean)->Unit)=Switch(this).apply { text=s;isChecked=value;this@AutoPollSettingsActivity.layout.addView(this);setOnCheckedChangeListener { _,v->act(v) } }
  private fun render() {
    layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(20,20,20,20) }
    setContentView(ScrollView(this).apply { addView(layout) })
    val t=TdlibManager.getTdlib(config.account)
    text("Account: ${t.myUserId()} · local ${config.account}")
    text("Target Chat: ${if(config.chat==0L) "Not selected" else t.chatTitle(config.chat).ifBlank { config.chatTitle }}\nChat ID: ${config.chat}")
    button("Choose target chat") { if(config.enabled) Toast.makeText(this,"Stop monitoring before changing target",Toast.LENGTH_LONG).show() else chooseChat() }
    toggle("AutoPoll",config.enabled) { v->
      if(v && config.chat==0L) { Toast.makeText(this,"Select a target chat first",Toast.LENGTH_LONG).show();render();return@toggle }
      config.enabled=v
      if(v) {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
          requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),47)
        try { AutoPollService.start(this) } catch(e: RuntimeException) { config.enabled=false;Toast.makeText(this,"Could not start service: ${e.javaClass.simpleName}",Toast.LENGTH_LONG).show() }
      } else stopService(Intent(this,AutoPollService::class.java))
      render()
    }
    toggle("Dry Run (no votes sent)",config.dryRun) { v->
      if(!v) AlertDialog.Builder(this).setTitle("Enable Live Voting?").setMessage("AutoPoll will submit real answers in the selected chat for this account.")
        .setPositiveButton("Enable") { _,_->config.dryRun=false;render() }.setNegativeButton("Cancel") { _,_->render() }.setOnCancelListener { render() }.show()
      else { config.dryRun=true;render() }
    }
    text("Live Voting: ${if(config.dryRun) "OFF" else "ON"}\nSmart Reserved Filter: ON · strict five-option template")
    button("Mode: ${config.mode}") {
      AlertDialog.Builder(this).setItems(Mode.entries.map { it.name }.toTypedArray()) { _,i->config.mode=Mode.entries[i];render() }.show()
    }
    button("Fixed slot: ${config.fixed.hour}:00") {
      AlertDialog.Builder(this).setItems(Slot.entries.map { "${it.hour}:00" }.toTypedArray()) { _,i->config.fixed=Slot.entries[i];render() }.show()
    }
    toggle("Next Free Priority",config.nextFree) { config.nextFree=it }
    button("Recovery scan: ${config.scanSeconds} s") {
      val picker=NumberPicker(this).apply { minValue=90;maxValue=120;value=config.scanSeconds;wrapSelectorWheel=false }
      AlertDialog.Builder(this).setTitle("Recovery interval (seconds)").setView(picker)
        .setPositiveButton("Save") { _,_->config.scanSeconds=picker.value;render() }
        .setNegativeButton("Cancel",null).show()
    }
    val alive=config.heartbeat>0 && System.currentTimeMillis()-config.heartbeat<125_000
    text("Background Service: ${if(alive) "RUNNING" else if(config.enabled) "STOPPED / STARTING" else "OFF"}\nConnection: ${t.connectionStateText()}")
    button("Vote History / Diagnostics") { history() }
    button("Android battery settings") { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    text("Monitoring uses a foreground service and CPU wake lock. Android/OEM restrictions, force-stop and offline periods can interrupt it. UNKNOWN results are never sent again automatically; new polls remain independent. History recovery is limited to the latest 100 messages from the last 24 hours.")
  }
  private fun chooseChat() {
    val t=TdlibManager.getTdlib(config.account)
    val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    val search=EditText(this).apply { hint="Search main list and archive" };box.addView(search)
    val list=ListView(this);box.addView(list)
    val ids=linkedSetOf<Long>(); var generation=0
    val dialog=AlertDialog.Builder(this).setTitle("Target Chat").setView(box).setNegativeButton("Cancel",null).create()
    fun display() {
      val rows=ids.toList();list.adapter=ArrayAdapter(this,android.R.layout.simple_list_item_1,rows.map { "${t.chatTitle(it)}\n$it" })
      list.setOnItemClickListener { _,_,i,_->
        if(config.enabled) return@setOnItemClickListener
        val account=t.myUserId()
        t.client().send(TdApi.GetChat(rows[i])) { result -> main.post {
          val chat=result as? TdApi.Chat
          if(account==0L || account!=t.myUserId() || chat==null || chat.type is TdApi.ChatTypeSecret) {
            Toast.makeText(this,"Could not select chat. Check the connection.",Toast.LENGTH_LONG).show()
          } else {
            config.chat=chat.id
            config.chatTitle=t.chatTitle(chat)
            dialog.dismiss();render()
          }
        } }
      }
    }
    fun collect(chatList: TdApi.ChatList, current: Int, next: ()->Unit) {
      t.client().send(TdApi.GetChats(chatList,Int.MAX_VALUE)) { result -> main.post {
        if(current==generation) {
          (result as? TdApi.Chats)?.chatIds?.forEach { ids.add(it) }
          display();next()
        }
      } }
    }
    fun loadList(chatList: TdApi.ChatList, round: Int, current: Int, done: ()->Unit) {
      if(round>=50) { done();return }
      t.client().send(TdApi.LoadChats(chatList,100)) { result -> main.post {
        if(current!=generation) return@post
        if(result is TdApi.Error || round==49) done()
        else loadList(chatList,round+1,current,done)
      } }
    }
    fun load(q: String) {
      val current=++generation;ids.clear();display()
      val handler=org.drinkless.tdlib.Client.ResultHandler { result -> main.post {
        if(current==generation) { (result as? TdApi.Chats)?.chatIds?.forEach { ids.add(it) }; display() }
      } }
      if(q.isBlank()) {
        val mainList=TdApi.ChatListMain();val archive=TdApi.ChatListArchive()
        loadList(mainList,0,current) {
          collect(mainList,current) {
            loadList(archive,0,current) { collect(archive,current) {} }
          }
        }
      } else t.client().send(TdApi.SearchChats(q,null,100),handler)
    }
    search.addTextChangedListener(object: TextWatcher {
      override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
      override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { load(s.toString()) }
      override fun afterTextChanged(s: Editable?) {}
    })
    dialog.show();load("")
  }
  private fun history() {
    val db=VoteJournal(this)
    val history=db.use { it.records(TdlibManager.getTdlib(config.account).myUserId()) }.take(100).joinToString("\n\n") { r->
      val p=SmartPollParser.parse(r.snapshot.texts)
      "Poll ID: ${r.snapshot.poll}\nChat ID: ${r.snapshot.chat}\nDetected At: ${java.util.Date(r.detectedAt)}\nAvailable: ${p.available}\nReserved: ${p.reserved}\nSelected: ${Slot.entries.getOrNull(r.index)}\nSubmission/Verification: ${r.state}\nSubmitted At: ${if(r.submittedAt>0) java.util.Date(r.submittedAt) else "-"}\nVerified At: ${if(r.verifiedAt>0) java.util.Date(r.verifiedAt) else "-"}\nLatency event to send: ${r.latencyMs} ms\nNetwork confirmation: ${if(r.verifiedAt>0 && r.submittedAt>0) "${r.verifiedAt-r.submittedAt} ms" else "-"}\n${r.reason}"
    }
    AlertDialog.Builder(this).setTitle("Vote History / Diagnostics").setMessage(history.ifEmpty { "No polls detected" }).setPositiveButton("Close",null).show()
  }
}
