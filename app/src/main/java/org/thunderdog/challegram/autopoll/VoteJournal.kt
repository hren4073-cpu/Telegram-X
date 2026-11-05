package org.thunderdog.challegram.autopoll

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import autopoll.*
import org.json.JSONArray
import org.json.JSONObject

data class VoteRecord(val key: String, val snapshot: PollSnapshot, val index: Int, val nextBag: List<Slot>,
                      val state: VoteState, val detectedAt: Long, val latencyMs: Long, val reason: String,
                      val mode: Mode = Mode.SHUFFLE, val submittedAt: Long = 0, val verifiedAt: Long = 0)

/** One durable ledger and bag transaction; FULL sync ensures SUBMITTING reaches disk before send. */
class VoteJournal(context: Context) : SQLiteOpenHelper(context, "autopoll.db", null, 1) {
  override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE votes (id TEXT PRIMARY KEY, account INTEGER NOT NULL, state TEXT NOT NULL, payload TEXT NOT NULL)")
    db.execSQL("CREATE TABLE bags (account INTEGER PRIMARY KEY, value TEXT NOT NULL)")
  }
  override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
  fun bag(account: Long): List<Slot> = readableDatabase.rawQuery("SELECT value FROM bags WHERE account=?",arrayOf("$account")).use {
    if (it.moveToFirst()) decodeBag(JSONArray(it.getString(0))) else emptyList()
  }
  fun proposalBag(account: Long): List<Slot> {
    val pending=uncertainRecords(account).filter { it.mode==Mode.SHUFFLE }.mapNotNull { Slot.entries.getOrNull(it.index) }
    return BagReservations.available(bag(account),pending)
  }
  fun seedBag(account: Long, slot: Slot, nextBag: List<Slot>) {
    if(bag(account).isNotEmpty()) return
    val values=JSONArray((listOf(slot)+nextBag).map { it.name }).toString()
    check(writableDatabase.insertWithOnConflict("bags",null,ContentValues().apply {
      put("account",account);put("value",values)
    },SQLiteDatabase.CONFLICT_REPLACE) != -1L)
  }
  fun records(account: Long): List<VoteRecord> = readableDatabase.rawQuery("SELECT payload FROM votes WHERE account=? ORDER BY rowid DESC LIMIT 100", arrayOf("$account")).use {
    val rows=mutableListOf<VoteRecord>(); while(it.moveToNext()) rows+=decode(JSONObject(it.getString(0))); rows
  }
  fun uncertainRecords(account: Long): List<VoteRecord> = readableDatabase.rawQuery("SELECT payload FROM votes WHERE account=? AND state IN ('SUBMITTING','PENDING','UNKNOWN')",arrayOf("$account")).use {
    val rows=mutableListOf<VoteRecord>(); while(it.moveToNext()) rows+=decode(JSONObject(it.getString(0))); rows
  }
  fun get(key: String): VoteRecord? = readableDatabase.rawQuery("SELECT payload FROM votes WHERE id=?", arrayOf(key)).use {
    if(it.moveToFirst()) decode(JSONObject(it.getString(0))) else null
  }
  fun save(account: Long, record: VoteRecord, commitBag: Boolean = false) {
    val db=writableDatabase; db.beginTransaction()
    try {
      val v=ContentValues().apply { put("id",record.key);put("account",account);put("state",record.state.name);put("payload",encode(record).toString()) }
      check(db.insertWithOnConflict("votes",null,v,SQLiteDatabase.CONFLICT_REPLACE) != -1L)
      if(commitBag && record.mode==Mode.SHUFFLE) {
        val current=bag(account)
        val chosen=Slot.entries.getOrNull(record.index)
        val remaining=if(chosen!=null) BagReservations.afterVerification(current,chosen) else current
        check(db.insertWithOnConflict("bags",null,ContentValues().apply { put("account",account);put("value",JSONArray(remaining.map { it.name }).toString()) },SQLiteDatabase.CONFLICT_REPLACE) != -1L)
      }
      db.setTransactionSuccessful()
    } finally { db.endTransaction() }
  }
  companion object {
    fun key(account: Long,p: PollSnapshot)=VoteProtocol.key(account,p)
    private fun decodeBag(a: JSONArray)= (0 until a.length()).map { Slot.valueOf(a.getString(it)) }
    private fun encode(r: VoteRecord)=JSONObject().apply {
      put("key",r.key);put("chat",r.snapshot.chat);put("message",r.snapshot.message);put("poll",r.snapshot.poll)
      put("optionIds",JSONArray(r.snapshot.optionIds));put("texts",JSONArray(r.snapshot.texts));put("index",r.index);put("bag",JSONArray(r.nextBag.map { it.name }))
      put("state",r.state.name);put("detected",r.detectedAt);put("latency",r.latencyMs);put("reason",r.reason)
      put("mode",r.mode.name);put("submitted",r.submittedAt);put("verified",r.verifiedAt)
    }
    private fun decode(j: JSONObject): VoteRecord {
      val texts=j.getJSONArray("texts")
      return VoteRecord(j.getString("key"),PollSnapshot(j.getLong("chat"),j.getLong("message"),j.getLong("poll"),(0 until texts.length()).map { texts.getString(it) },false,emptyList(),false,true,j.getJSONArray("optionIds").let { a -> (0 until a.length()).map { a.getString(it) } }),
        j.getInt("index"),decodeBag(j.getJSONArray("bag")),VoteState.valueOf(j.getString("state")),j.getLong("detected"),j.getLong("latency"),j.getString("reason"),
        Mode.valueOf(j.optString("mode","SHUFFLE")),j.optLong("submitted",0),j.optLong("verified",0))
    }
  }
}
