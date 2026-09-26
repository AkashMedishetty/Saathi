# Guardrails and grounded answers — done

Branch: `codex/guardrails`

Base: latest local `main` at creation, `990b788`.

Worktree: `/Users/akash/IQOO Hackathon/saathi-codex-guard`

## Delivered

Six pure Kotlin source files in `com.saathi.app.policy`: `ActionPolicy`, `Grounded`, `AnswerCheck`, `Redactor`, `LoopGuard`, and shared translated `Words`. Tests are only in the matching test package. No Guide/service wiring, dependency changes, network code, phone access, or tiers work.

This is a conservative decision layer. It does not guarantee that arbitrary source material is true, recognize every app or credential, or enforce safety until all execution and output paths are wired through it.

## 1. ActionPolicy: integration contract

The handoff's `Mode`, `Kind`, `ActionRequest`, and `Verdict` interfaces are implemented unchanged:

```kotlin
ActionPolicy.check(r: ActionRequest): Verdict
```

Build each request from the **freshly re-read target**, including its content-description and hint in `targetLabel`, its role (`"input"` for editable fields), and its password flag. Never use a model's description instead of the real target label. `screenText` is the current screen's text. Null/blank identity must not become an invented safe label. Do not log the request or fill text.

```kotlin
val request = ActionRequest(
    kind = kind, // map as below
    pkg = fresh.pkg,
    targetLabel = el?.label,
    targetRole = el?.role,
    isPassword = el?.password ?: false,
    fillText = if (kind == Kind.TYPE) t.fill else null,
    screenText = fresh.allText,
    mode = mode,
    prevPkg = previousExternalPackage,
)
val verdict = ActionPolicy.check(request)
```

In that adapter, `kind`, `mode` and `previousExternalPackage` are integration-owned values. Map `t.fill == "__BACK__"` to BACK before interpreting other fill values; editable target plus fill to TYPE; a scroll/slider operation to SCROLL; ordinary target clicks to TAP. Use HOME explicitly. Unknown global actions are GLOBAL and Block. TEACH always prevents execution, including TYPE, launch and navigation.

### Exact Guide entry points to cover

| Current function | Required gate |
| --- | --- |
| `show(t: Target, practiced: Boolean)` / `showStep(...)` | Check TEACH before presenting a target; Block must not become a glow that encourages the blocked action. |
| `scheduleAuto(t: Target)` | Check AUTO before scheduling; recheck immediately before execution. |
| `doItForMe()` / `performStep(t: Target)` | Check DO_IT_ONCE on the fresh target, including the existing `awaitingConfirm` branch. |
| `act(t: Target, fresh: Screen?, el: UiElement?)` | Final mandatory gate before focus, set-text, scroll, `clickUp`, gesture tap, or global Back. A missing fresh screen means no execution. |
| `start(goalText, autoMode)` | Gate `IntentRouter.systemAction` before `performGlobalAction`. Classify only known Back/Home; other globals remain Block. |
| `goBack()` / `goHome()` | Check BACK/HOME against the current foreground screen before changing apps. Banking policy blocks these too, as required. |
| `begin(...)`, `resumeTask()`, private `pause()` launch, `lookUp(...)`, `askFamilyNow()`, `missingApp(...)` | Gate every `startActivity`/launch path, including prefilled share intents. |
| `runTool(c: Coach.Call)` | Gate OPEN/LOOKUP launches and any actions added later. SAY/ASK/DONE use AnswerCheck below. |
| Other launch helpers called by Guide/skills | Put the same gate at the executor boundary; checking only `act` leaves direct `startActivity` calls uncovered. |

For LAUNCH, check both the current app context **and** a second request whose `pkg` is the resolved destination. Do not pass an unresolved intent as an ordinary app launch. The API does not carry intent action, URI, extras or coordinates: the adapter must describe the real effect. For example, `ACTION_CALL` must be classified with a Call label (manual only), never merely “Phone”. A prefilled external intent needs its payload checked/redacted separately.

### Handling verdicts and confirmations

- Allow: execute only in DO_IT_ONCE/AUTO on that fresh target.
- GlowOnly: point and speak its `Say`; no automated action, even after “Do it”.
- Block: speak its `Say`; no automated action or instruction to perform that blocked action.
- Confirm: ask `ask`. Claude owns a one-use approval tied to exact package, target identity/role, action, payload identity and screen generation. Re-read and recheck on acceptance. Execute a Confirm action only when that same request has fresh explicit approval. A changed target, screen, payload, declined prompt or expired approval must ask again. Never reinterpret Block/GlowOnly as Confirm.

**README precedence:** Send, Pay, Install and Call are always GlowOnly (or Block in a stricter context), including DO_IT_ONCE. They never become automated actions after confirmation. Other listed consequential actions return Confirm in DO_IT_ONCE/AUTO. `Send feedback`, `Sender`, `Payment history`, `Installed`, and `Call history` have explicit near-miss coverage.

Known financial apps block every Kind/Mode. IRCTC payment screens include the actual `cris.org.in.prs.ima` package and browser text mentioning IRCTC. Settings TAP is always manual; Settings TYPE is permitted only for identified search inputs. Sensitive TYPE is blocked first. Dangerous permission contexts block actions, with Back/Home available outside financial contexts. Installer transitions from a known previous app other than Play Store fail closed.

## 2. Grounded and AnswerCheck: integration contract

```kotlin
Grounded.extract(question: String, page: List<String>): Answer?
// Answer(text: String, kind: String, confidence: Float)

AnswerCheck.verify(question: String, answer: String, source: String?): Check
AnswerCheck.verify(question: String, answer: String, source: String?, expectedLanguage: Lang): Check
// Check(ok: Boolean, reasons: List<String>, fallback: Say?)
```

Use the explicit `expectedLanguage` overload with `Prefs.lang`: the three-argument form infers from question script or an explicit language request; it cannot infer the user's saved preference from Hinglish.

In `Guide.lookUp(goalText, q)`, retain individual real labels while waiting for search results. Try Grounded **before** invoking the model:

```kotlin
val page = screen.elements.map { it.label }.filter { it.isNotBlank() }
val source = page.joinToString("\n")
val grounded = Grounded.extract(goalText, page)
// If grounded is null, the existing model may propose an answer using this same source.
val candidate: String = grounded?.text ?: modelCandidate
val checked = AnswerCheck.verify(goalText, candidate, source, lang)
val output = if (checked.ok) candidate else checked.fallback!!.pick(lang)
val spoken = Redactor.forSpeech(output, lang)
```

`modelCandidate` above means the existing model result, not a new implemented function. If both candidates are absent, use the existing translated “results opened” fallback. Never invent a value to fill a missing card.

Gate before `current = Target(...)`, `overlay.showCard`, speech, logging or `Conversation.remember`. Retain source excerpts exactly; `confidence = 0.9f` is a rule-match marker, not a calibrated truth probability. `Answer.text` is a literal source excerpt, not translated UI copy. If its script differs from the selected language, the verifier refuses it. Do not translate with a model and skip verification.

For `Guide.answerQuestion(q)`, check the result of `Conversation.answer(...)` with `source = null` unless actual supporting text was supplied. Unsupported output is replaced with the returned `Say`; do this before remembering or displaying it. No-source how-to output is limited to reviewed general instructions for app search, messages and upma. Other free model advice fails closed. Medical, legal and investment requests use the fixed kind doctor/adviser fallback; current facts require a source.

For `runTool` SAY/ASK/DONE and `endCoach(...)`, model-generated factual output needs the same verification with the actual current source. Deterministic, reviewed `Say` instructions do not need to masquerade as sourced facts. LOOKUP must retain its real source rather than treating later model output as evidence. Apply equivalent gates to other model-backed explanation/read/recall output; unverified model text is never itself a source.

### Source checks

Whole normalized excerpts must occur contiguously in the source. A model cannot rearrange names, scores or newline fragments. Only an exact match to `Grounded.extract(question, source.lines())` may combine nonadjacent deterministic card fields. Supported formatting includes digit scripts, comma-separated amounts, degree notation and common English written dates. Explicit currency mismatches fail. There are narrowly enumerated transliteration aliases for Narendra Modi; no name is generated from that table without source support.

Reasons are stable diagnostic strings, not text to speak: `empty_or_too_long`, `garbled`, `wrong_script`, `medical`, `legal_or_investment`, `private_information`, `needs_current_source`, `no_supported_offline_advice`, `unusable_source`, `no_content`, `unsupported_claim`, `unsupported_currency`.

### Actual Google fixtures

| Capture | Result |
| --- | --- |
| q1 rain/Hyderabad | Literal Hyderabad card: `Now Cloudy 26°`, `Precipitation`, `10%`. Does not substitute the differing AI Overview rain percentages. |
| q2 gold | Launcher capture; null. |
| q3 cricket | Three historical match cards on different September dates; null because there is no unambiguous single/live answer. |
| q4 prime minister | Launcher capture; null. |
| q5 upma | Recipe/search links, not a supported answer card; null. |
| q6 currency | Launcher capture; null. |
| q7 sunrise | Literal `6:06 am` (original narrow space preserved), Sunday 27 September 2026 (IST), Hyderabad. Date and place stay attached. Explicit “today/tomorrow” is refused without a trusted date input. |
| q8 Diwali | Launcher capture; null. |

The thin q2/q4/q6/q8 trees are not merely short answers: their content is the launcher. Price/currency/date/knowledge-panel and single-score shapes have explicitly synthetic unit tests. Those tests are not represented as device evidence or present-day facts.

## 3. Redactor: all sinks, before serialization

```kotlin
Redactor.forLog(s: String): String
Redactor.forMemory(s: String): String
Redactor.forSpeech(s: String): String // English replacement phrase
Redactor.forSpeech(s: String, lang: Lang): String
```

- Wrap raw user/source/model strings **before** `Log.i` and `DebugLog.i`; `Guide.start` currently has both.
- At `Conversation.remember(user, saathi)`, redact each value for memory and omit the entry if either becomes empty.
- At `Memory.saveTask`, `note`, `journal`, `addReminder`, `learnLabel`, and any persisted user/source string, call `forMemory` before JSON serialization. An empty result means skip storing it, not store the original.
- At `Speaker.say(text, lang)`, apply the language-aware speech redactor to protect all speech paths, including `readMessages`, repeat and coach output. Apply it to user-visible private-source excerpts too if those should not be shown.
- Do not log whole `ActionRequest`, model prompts, notification bodies or loop keys as a shortcut. Safe diagnostic reasons/IDs are sufficient.

Recognized secret-bearing lines are masked in logs/speech. PAN, IFSC and 12–19 digit sequences are masked even without labels. Labels cover EN/HI/TE OTP/PIN/CVV/password/account/card/Aadhaar variants. Standalone numeric strings of three or more digits are conservatively masked. Memory drops the entire entry when it detects private material; it does not retain a partially masked secret-bearing record.

## 4. LoopGuard: task lifetime and watchdog

```kotlin
LoopGuard(maxSteps: Int = 25)
// Testing overload: LoopGuard(maxSteps = 25, nowMs = { monotonicMilliseconds })
fun onScreen(fingerprint: String, pkg: String)
fun onAction(key: String)
fun verdict(): LoopVerdict // Ok, Repeating(say), Stuck(say), OutOfSteps(say)
```

Create one guard at `Guide.begin(...)` or `startCoach(...)` for a new task. Share it across scripted, model and coach execution. Do not recreate it every tick. Dispose it when the task ends/stops; any deliberate retry/new budget requires user choice.

In `Guide.tick()`, call `onScreen` and `verdict` **before** the `screen.signature == lastSig` early return. Use a stable fingerprint of nonprivate semantic screen state, excluding clocks, animations, cursor movement, OTP/password values and notification counters. Include the actual package separately. Never pass raw text as a fingerprint.

Before every automated action, check `verdict()`. If Ok, execute only after ActionPolicy allows/approves it, then record one `onAction(stableActionKey)` for that attempt. Keys must identify the action/target, never typed text, OTPs, or model-generated random IDs. Check the resulting verdict and stop scheduling on non-Ok. At most `maxSteps` attempts occur; the third same-screen action triggers Repeating and prevents a fourth.

In `startWatchdog()` (or a task-only periodic timer), check `verdict` at least once per second even when there are no accessibility events. Otherwise the 40-second timer cannot surface a warning on a silent screen. Repeated identical screens and actions do not reset progress time. A package change counts as a screen change. A→B→A→B is latched as Repeating.

For every non-Ok result, stop automatic scheduling and show/speak its translated choices. Never automatically call someone. Repeating and OutOfSteps remain latched; Stuck clears only with new screen progress. Use a monotonic clock; the default is `System.nanoTime` in milliseconds. Deliberate user pauses may therefore surface a Stuck choice on resume; this API has no pause method.

## Validation

```sh
source ~/dev/android-env.sh
./gradlew testDebugUnitTest assembleDebug --offline
```

Final result: **BUILD SUCCESSFUL, 932 tests, zero failures/errors/skips**.

- ActionPolicy: 846 parameterized cases + 5 edge tests = **851**.
- Grounded: 9 tests, including all eight real text fixtures.
- AnswerCheck: 9 tests with multiple positive/adversarial cases each.
- Redactor: 34 parameterized private-message cases + 3 safe/edge tests.
- LoopGuard: 11 tests with an injected clock; no sleeps.
- Existing tests: 15.

Both merged debug manifests were inspected and contain no INTERNET permission. `git diff --check` is clean. An initial full build failed with the disk nearly full; after free space became available, the full command succeeded. No clean, refresh-dependencies, deletion of team outputs, device command or HackTracker changes were used.

## Honest gaps

1. **Not yet wired.** Existing direct Guide/skill/service paths can bypass this layer until Claude adds the executor/output gates above. Confirmation identity and call/intent semantics are integration responsibilities.
2. **App classification is finite.** Known financial packages plus bank-name/package and screen markers cover the tested cases. Unknown bank packages, embedded payment pages without recognizable text, OEM variants and misleading/missing accessibility labels can evade classification. Screen markers may also overblock harmless mentions of UPI. There is no installed-app category database or network lookup.
3. **Permissions and labels are text rules.** EN/HI/TE examples are covered, not every inflection or content-description. Unlabelled taps are manual; entirely misleading labels remain a gap. Arbitrary GLOBAL is blocked. The Settings search whitelist is deliberately narrow.
4. **Grounding is not truth checking.** A source can be stale, wrong, malicious or irrelevant. The extractor preserves context and refuses known ambiguity, but cannot validate publication time, origin, the current date or actual match status. It requires recognized English card shapes and a Search Results marker; unsupported layouts/languages return null.
5. **Strict paraphrase and language rejection.** Source verification does not perform semantic entailment or general named-entity recognition. Valid paraphrases and most translated answers fail closed. Script detection is a character-ratio heuristic, not full language identification; Latin-script non-English text is not distinguished from English. Arbitrary transliteration and spelled-out numbers are unsupported.
6. **Offline coverage is intentionally small.** Only exact reviewed advice is permitted. Other benign questions often receive the fallback. Medical/legal/investment classification uses keywords and can miss indirect wording, though unsupported no-source advice still fails closed.
7. **Redaction needs recognizable context.** Unlabelled arbitrary password words, short codes embedded in ordinary prose, word-spelled digits, exotic separators and unknown labels may escape. Whole-line secret masking and numeric-only masking also hide some harmless text/amounts. Prevent sensitive fields from entering prompts and persistent state in the first place; redaction is a secondary defense.
8. **Loops depend on a stable fingerprint and a watchdog.** Dynamic text can falsely look like progress; similar screens can collide. Same-action counts reset on screen change; three-screen cycles are limited by the step budget rather than the two-screen rule. No device behavior or actual wiring was tested, per the hard rules.
