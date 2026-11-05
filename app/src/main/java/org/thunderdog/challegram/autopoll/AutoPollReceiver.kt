package org.thunderdog.challegram.autopoll
import android.content.*

class AutoPollReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context,intent: Intent) {
    if(intent.action==Intent.ACTION_BOOT_COMPLETED || intent.action==Intent.ACTION_MY_PACKAGE_REPLACED) resumeIfEnabled(context)
  }
  companion object {
    fun resumeIfEnabled(context: Context) {
      val c=AutoPollConfig(context)
      if(c.enabled) try { AutoPollService.start(context) } catch(e: RuntimeException) {
        // Android may prohibit background starts. The next user launch can restart monitoring.
        c.heartbeat=0
      }
    }
  }
}
