package com.saathi.app.service

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.saathi.app.guide.MessageScam

/**
 * Reads incoming messages so Saathi can read them aloud and warn about scams.
 * Kept in RAM only (last 20), never written to disk or memory, never sent anywhere.
 * Grant: Settings › Notification access › Saathi (or Saathi's own Settings screen).
 */
class MessageListener : NotificationListenerService() {

    data class Msg(val app: String, val sender: String, val text: String, val at: Long)

    companion object {
        private val MESSAGING = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.messaging", "com.android.mms", "com.vivo.message",
            "com.samsung.android.messaging", "org.telegram.messenger", "com.truecaller",
        )
        private val recent = ArrayDeque<Msg>()
        @Synchronized fun latest(n: Int = 3): List<Msg> = recent.takeLast(n).reversed()
        @Synchronized private fun keep(m: Msg) {
            if (recent.any { it.sender == m.sender && it.text == m.text }) return
            recent.addLast(m); while (recent.size > 20) recent.removeFirst()
        }
        @Synchronized fun clear() = recent.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName !in MESSAGING) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val sender = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(40) ?: return
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.take(500) ?: return
        if (text.isBlank() || Regex("^\\d+ new messages?$", RegexOption.IGNORE_CASE).matches(text)) return
        val app = com.saathi.app.guide.AppLauncher.labelOf(this, sbn.packageName)
        keep(Msg(app, sender, text, System.currentTimeMillis()))
        if (!com.saathi.app.guide.Prefs.scamGuard(this)) return
        MessageScam.check(text)?.let { hit ->
            Handler(Looper.getMainLooper()).post { SaathiService.instance?.guide?.messageAlert(sender, app, hit) }
        }
    }
}
