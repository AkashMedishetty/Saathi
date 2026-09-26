# Codex track: Scam shield v2 (one focused task; limited credits, so keep it tight)

Read `docs/handoff/00-README.md` first. Branch: `codex/scam-shield`. Package: `com.saathi.app.scam`
(tests in `app/src/test/java/com/saathi/app/scam/`). You may also extend `guide/MessageScam.kt` rules.

## Goal
Warn elderly people **before** harm, with high precision (no false alarms on normal family messages). Pure logic +
tests; Claude wires it to accessibility events and notifications.

## Inputs Claude will give you
```kotlin
data class MsgEvent(val app: String, val sender: String, val text: String)                  // a notification
data class ScreenEvent(val pkg: String, val prevPkg: String?, val texts: List<String>, val clickedText: String?)
// clickedText = label of what the person just tapped (TYPE_VIEW_CLICKED), if any
```
## Output
```kotlin
data class Warning(val id: String, val level: Level /* CAUTION or STOP */, val say: Say, val safeAction: String?)
object ScamShield {
    fun onMessage(e: MsgEvent): Warning?
    fun onScreen(e: ScreenEvent): Warning?
}
```

## Must detect (each with tests: positives AND look-alike negatives)
1. **APK files:**
   - A WhatsApp/Telegram/SMS notification or chat bubble mentioning a file ending in `.apk` or `.xapk`, "APK",
     "install this app", or common lures ("SBI YONO update.apk", "PM Kisan.apk", "wedding card.apk",
     "RTO challan.apk").
   - Level STOP: "Someone sent an app file. Don't open it. It can steal your money."
2. **Installer after a chat or browser:** `pkg` is a package installer (`com.android.packageinstaller`,
   `com.google.android.packageinstaller`, or any pkg containing "packageinstaller") **and** `prevPkg` is a messaging
   app, a browser or a file manager. Level STOP, `safeAction = "back"`.
3. **Remote-control apps:** AnyDesk, TeamViewer, QuickSupport, RustDesk, AirDroid, "screen share" asked in a message,
   or their Play Store page opened while a call is active (Claude passes `prevPkg` = dialer). STOP.
4. **UPI collect trick:** "enter your UPI PIN to receive money", "collect request", "₹… received, enter PIN". STOP:
   "You never need a PIN to receive money."
5. **OTP asks, KYC-block threats, electricity-cut threats, prizes/lottery/refunds, fake job "task" offers,
   customs/parcel held, digital-arrest / police / CBI calls, loan-app threats:** CAUTION or STOP. EN + Hinglish + Hindi
   + Telugu phrasings.
6. **Lookalike links:** shortened links (bit.ly, tinyurl…), IP-address links, `.apk` links, and bank-name lookalike
   domains (`sbi-kyc-update.in`, `hdfc-bank-verify.com`). CAUTION.

Messages from saved family (Claude passes a flag if needed) are CAUTION at most, except APK / installer (always STOP).

## Words
Every `say` in EN/HI/TE: calm, specific, with what to do next ("Don't open it. Delete the message. Call your son if
unsure."). No shouting, no jargon.

## Deliver
`docs/handoff/scam-shield-DONE.md` with the integration calls. Due **00:30**. If credits run low, finish 1–4 with tests
first.
