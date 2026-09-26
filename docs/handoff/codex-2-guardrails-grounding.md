# Codex track 2: The guardrail layer + grounded answers (architecture + safety; the hard one)

Read `docs/handoff/00-README.md` again (same rules). New worktree from the latest `main`:
`git worktree add ../saathi-codex-guard -b codex/guardrails main`. Package: `com.saathi.app.policy`
(tests in `app/src/test/java/com/saathi/app/policy/`). Pure Kotlin; Claude wires it into the Guide.

## Why
Today, safety rules are scattered across the codebase: `Planner.guard`/`isRisky`, `CallGuard`, `CONFIRM_KEYS` in
`Guide.kt`, scam rules, ad-hoc checks. General answers come from a small on-device model that sometimes makes things
up. Judges and users need one thing to be true: **Saathi never does anything harmful, never says anything made up, and
never gets stuck.** Build that as one reviewed, heavily tested layer that every action and every answer passes through.

## 1. `ActionPolicy`: every tap/type/scroll/back/launch goes through it
```kotlin
enum class Mode { TEACH, DO_IT_ONCE, AUTO }            // TEACH = glow only; DO_IT_ONCE = person pressed "Do it"; AUTO = "do it all for me"
enum class Kind { TAP, TYPE, SCROLL, BACK, HOME, LAUNCH, GLOBAL }
data class ActionRequest(val kind: Kind, val pkg: String, val targetLabel: String?, val targetRole: String?,
                         val isPassword: Boolean, val fillText: String?, val screenText: String, val mode: Mode,
                         val prevPkg: String? = null)
sealed interface Verdict {
    data object Allow : Verdict
    data class Confirm(val ask: Say) : Verdict        // ask the person first ("Shall I press Send?")
    data class GlowOnly(val say: Say) : Verdict       // point, but the person must tap it themselves
    data class Block(val say: Say) : Verdict          // never, explain calmly
}
object ActionPolicy { fun check(r: ActionRequest): Verdict }
```
Rules (each with tests, including tricky look-alikes):
- **Money/UPI/banking apps** (PhonePe, GPay, Paytm, BHIM, bank apps, IRCTC payment pages) → Block every action by
  Saathi.
- **Send / Pay / Transfer / Install / Uninstall / Delete / Call / Buy / Confirm order / Book / Submit / Grant
  permission / Allow / Accept terms** → never Allow in AUTO without Confirm. DO_IT_ONCE → Confirm. **Install from
  unknown sources, "Allow from this source", Device admin, Accessibility for another app, Screen share / cast
  permission** → Block (scam patterns).
- **Password/OTP/PIN/CVV/card/Aadhaar/PAN fields** (isPassword, or labels/hints like those in EN/HI/TE) → Block TYPE,
  GlowOnly TAP.
- **Inside `com.android.settings`**: Saathi taps nothing itself (GlowOnly). TYPE is allowed only into the Settings
  search box (trap #45: the phone treats automated taps there as a hijack).
- **Label matching must be robust**: "Send" vs "Send feedback" / "Sender", "Pay" vs "Payment history", "Install" vs
  "Installed", "Call" vs "Call history", Hindi/Telugu labels (भेजें, भुगतान, ఇన్‌స్టాల్, పంపు), icons with only a
  content-description.
- **At least 150 test cases.**

## 2. `AnswerCheck` + `Grounded`: no made-up answers
```kotlin
object Grounded { fun extract(question: String, page: List<String>): Answer? }   // rules first: no model
data class Answer(val text: String, val kind: String, val confidence: Float)
object AnswerCheck { fun verify(question: String, answer: String, source: String?): Check }
data class Check(val ok: Boolean, val reasons: List<String>, val fallback: Say?)
```
- **Fixtures:** real Google results pages in `fixtures/trees/google/q*.txt` (+ `.png`): weather in Hyderabad, gold
  price, India cricket score, "who is the prime minister of India", how to make upma, 1 dollar in rupees, sunrise time,
  Diwali 2026 date. Some captures are thin (118 nodes) because the page was still loading; say so and work with what's
  there. Tree format is in the README; the page text is in `t=` / `d=`.
- `Grounded.extract`: pull the answer card for weather (temperature, condition, rain chance), prices, scores,
  currency, times/dates and knowledge-panel facts. Return null when unsure.
- `AnswerCheck.verify`, for model answers:
  - reject when the answer has **numbers, dates, names or amounts that don't appear in the source text**, allowing for
    formatting (₹1,240 vs 1240, "12°" vs "12 degrees") and transliteration where feasible;
  - reject a wrong language or script;
  - reject garbage (see `llm/Templates.garbled`).
- **Offline or no source** (general questions answered from the model's own knowledge): allow only safe, general how-to
  and advice. Medical dose / diagnosis, legal and investment questions → a fixed, kind "ask your doctor / someone you
  trust" line (EN/HI/TE). Anything factual that needs today's data → "I need the internet for that."

## 3. `Redactor`: privacy
`Redactor.forLog(s)`, `forMemory(s)`, `forSpeech(s)`:
- mask OTPs, card numbers, account/IFSC, Aadhaar (12 digits, spaced or not), PAN, UPI PINs and CVVs;
- speech never reads an OTP aloud;
- memory never stores those at all.

Tests with realistic Indian SMS texts in EN/HI/TE.

## 4. `LoopGuard`: never stuck, never looping
```kotlin
class LoopGuard(val maxSteps: Int = 25) {
    fun onScreen(fingerprint: String, pkg: String)
    fun onAction(key: String)
    fun verdict(): LoopVerdict   // Ok | Repeating(say) | Stuck(say) | OutOfSteps(say)
}
```
It detects:
- the same action on the same screen 3×;
- an A→B→A→B ping-pong;
- 40 s with no progress;
- the step budget running out.

Each case gets a calm message offering choices ("Let's try another way, or shall I call your son?").

## Deliver
`docs/handoff/guardrails-DONE.md` with:
- the exact integration points (which Guide functions call which check, with signatures);
- the test counts;
- known gaps, honestly.

`./gradlew testDebugUnitTest assembleDebug` must pass. Due **01:30**.

(The older "tiers" handout, `codex-2-tiers-offline-understanding.md`, is **later**, after the demo.)
