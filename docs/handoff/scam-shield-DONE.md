# Scam shield v2 — done

Branch: `codex/scam-shield`

Worktree: `/Users/akash/IQOO Hackathon/saathi-codex-scam`

## Built

Pure, stateless Kotlin in `app/src/main/java/com/saathi/app/scam/ScamShield.kt`. No Android types in its API; uses the existing `Say` translations. No message retention, logging, network access, new dependencies, or automatic actions.

Priorities 1–4 are implemented: APK/XAPK file or install-message warnings; package installer transitions from chat/browser/files; requests to use remote-control apps and Play Store context during an active call; UPI collect/PIN-to-receive tricks. Also implemented OTP requests, KYC blocking threats, electricity payment threats, prize/refund lures, paid task/job offers, held-parcel payment demands, arrest threats, loan intimidation, and suspicious links.

IDs: `apk`, `installer`, `remote`, `upi`, `otp`, `kyc`, `electricity`, `prize`, `job`, `parcel`, `arrest`, `loan`, `link`.

APK and installer always STOP. Remote, UPI, OTP, arrest and loan threats normally STOP; other categories CAUTION. `isSavedFamily = true` caps everything except APK/installer at CAUTION. This flag defaults to false. Do not infer family status from a notification's display name.

## Exact integration API

```kotlin
package com.saathi.app.scam

data class MsgEvent(
    val app: String,
    val sender: String,
    val text: String,
    val isSavedFamily: Boolean = false,
)
data class ScreenEvent(
    val pkg: String,
    val prevPkg: String?,
    val texts: List<String>,
    val clickedText: String?,
    val isSavedFamily: Boolean = false,
)
enum class Level { CAUTION, STOP }
data class Warning(val id: String, val level: Level, val say: Say, val safeAction: String?)

ScamShield.onMessage(e: MsgEvent): Warning?
ScamShield.onScreen(e: ScreenEvent): Warning?
```

### Notifications

In Claude's notification callback, before offering to open a notification:

```kotlin
val warning = ScamShield.onMessage(
    MsgEvent(
        app = notificationPackageName,
        sender = senderName,
        text = messageBody,
        isSavedFamily = senderMatchesVerifiedFamilyContact,
    )
)
```

The variables above are adapter inputs, not new service APIs. `app` is the exact Android package name, not a display name such as “WhatsApp”. Pass one incoming message at a time; avoid combining notification history or unrelated messages. `sender` is accepted for the specified interface and is not inspected or retained.

### Accessibility events

In Claude's accessibility handler, after reading the tree and before advancing a guide or prompting a risky tap:

```kotlin
val warning = ScamShield.onScreen(
    ScreenEvent(
        pkg = screen.pkg,
        prevPkg = previousExternalPackage,
        texts = screen.elements.map { it.label }.filter { it.isNotBlank() },
        clickedText = justClickedLabel,
        isSavedFamily = currentChatIsVerifiedFamily,
    )
)
```

Use `justClickedLabel` only for the current `TYPE_VIEW_CLICKED`; otherwise pass null. `texts` should preserve individual message/label boundaries. Prefer complete message bubbles where available. Never concatenate all screen text: independent PIN and received-money labels must not create a warning together.

Keep the last *different external* foreground package for installer transitions; do not replace it with Saathi's overlay or repeatedly overwrite it with the installer itself. The detector is stateless.

For the Play Store rule, pass a recognized dialer as `prevPkg` **only when Claude knows a call is currently active** and the visible screen is the remote app's detail page. A previous dialer visit alone is insufficient. The supplied event has no call-state or page-type field, so Claude must gate this context. Do not supply this dialer override on search results or recommendations.

For each non-null result, show/speak `warning.say` using the existing language selector. Suspend the current risky guide prompt while the warning is visible. Deduplicate repeat events in the integration without retaining message contents. `safeAction` is `"back"` only for the installer warning; offer Back to the user. The detector does not execute Back or any other action. Never automatically tap Send, Pay, Install or Call.

The existing `MessageScam` was left unchanged. Route relevant notifications through this detector instead of showing both detectors' warnings; the old detector does not apply the family cap or these precision checks.

### Package coverage

Messaging: WhatsApp/personal and business, Telegram main/web, Google/Samsung/Android/Vivo SMS, Messenger, Signal. Browser/file-manager/dialer allowlists are explicit in `ScamShield.kt`; installer package names use the handoff's `contains("packageinstaller")` rule. UPI screen checks cover Google Pay, PhonePe, Paytm and BHIM. Message checks for non-APK categories also work for other notification packages. APK message checks require a recognized messaging package; APK URLs in other notifications receive link CAUTION.

## Validation

Executed in this worktree:

```sh
source ~/dev/android-env.sh
./gradlew testDebugUnitTest assembleDebug --offline
```

Passed: **27 JVM tests, 0 failures/errors/skips**, including 12 new test methods with table-driven cases. Every warning category has positive and look-alike negative messages in English, Hindi, Telugu and Hinglish. Additional cases cover all named remote apps, installer origins, family caps, priority, UPI payment screens, safety advice, link host boundaries, IPv4 validity, click labels and independent message boundaries. The test-only tree parser checks a sample and the existing Messages/Chrome home fixtures (no warnings).

Both merged debug manifests were inspected: no `android.permission.INTERNET`. No phone/device commands, HackTracker changes, clean, refresh-dependencies, service/guide wiring or out-of-scope source edits.

## Limits — integration should preserve these

- This is a phrase detector, not a guarantee that a message is safe. Unknown spellings, scripts, dialects, obfuscation, new domains and unsupported package variants can be missed. No claim of measured production precision.
- APK mentions are deliberately STOP even in educational messages or from family, per the handoff. “Install this app” also receives the required conservative APK warning without proving that an attachment exists.
- Advice/negation suppresses non-file rules within a sentence. A malicious request mixed with advice in that same sentence can be missed. Quoted scam examples and reported threats can still warn. Separate sentences are checked independently.
- The detector cannot identify a real Play Store detail page or infer active calls from these inputs. Integration gating described above is required. It cannot verify the sender or saved-family identity.
- Rules avoid combining unrelated accessibility labels. A harmful phrase split across multiple nodes may be missed. Only join nodes when the integration knows they belong to the same message.
- Lookalike-bank detection covers the specified hyphenated bank/lure forms, not every deceptive domain. IPv4 and common shorteners are recognized; there is no network reputation lookup or URL expansion. A short link warning does not mean the sender is fraudulent.
- Existing fixtures are benign home screens, not live scam captures. Phone behavior and wiring remain for Claude to validate.
