package org.thunderdog.challegram.autopoll

import android.content.Context
import autopoll.*

class AutoPollConfig(context: Context) {
  private val p=context.getSharedPreferences("autopoll",Context.MODE_PRIVATE)
  var enabled: Boolean get()=p.getBoolean("enabled",false); set(v) { check(p.edit().putBoolean("enabled",v).commit()) }
  var account: Int get()=p.getInt("account",0); set(v) { check(p.edit().putInt("account",v).commit()) }
  var chat: Long get()=p.getLong("chat",0); set(v) { check(p.edit().putLong("chat",v).commit()) }
  var chatTitle: String get()=p.getString("chat_title","") ?: ""; set(v) { check(p.edit().putString("chat_title",v).commit()) }
  var dryRun: Boolean get()=p.getBoolean("dry",true); set(v) { check(p.edit().putBoolean("dry",v).commit()) }
  var mode: Mode get()=Mode.valueOf(p.getString("mode","SHUFFLE")!!); set(v) { check(p.edit().putString("mode",v.name).commit()) }
  var fixed: Slot get()=Slot.valueOf(p.getString("fixed","SLOT_09")!!); set(v) { check(p.edit().putString("fixed",v.name).commit()) }
  var nextFree: Boolean get()=p.getBoolean("next",true); set(v) { check(p.edit().putBoolean("next",v).commit()) }
  var scanSeconds: Int get()=p.getInt("scan_seconds",90).coerceIn(90,120); set(v) { check(p.edit().putInt("scan_seconds",v.coerceIn(90,120)).commit()) }
  var heartbeat: Long get()=p.getLong("heartbeat",0); set(v) { p.edit().putLong("heartbeat",v).apply() }
}
