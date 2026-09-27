# Saathi Pro cloud brain

Branch `codex/e2e`; merged main first at `b28cdc5`. Added only `ProBrain.kt`, `ProBrainTest.kt`, and this note.
No Guide/Planner/manifest/build changes, new dependencies, adb, or provider/network calls.

## Exact integration

```kotlin
data class ProConfig(
    val baseUrl: String, val apiKey: String, val model: String, val timeoutMs: Int = 15000
)

suspend fun plan(
    cfg: ProConfig, goal: String, history: List<String>, answers: List<String>,
    appLabel: String, screenList: String, lang: String
): String?

suspend fun explain(cfg: ProConfig, question: String, context: String): String?
suspend fun explain(cfg: ProConfig, question: String, context: String, appLabel: String): String?
```

Functions are members of the `ProBrain` object. Example calls:

```kotlin
val openRouter = ProConfig(
    baseUrl = "https://openrouter.ai/api/v1",
    apiKey = apiKeyFromSecureSettings,
    model = selectedOpenRouterModel
)
val groq = ProConfig(
    baseUrl = "https://api.groq.com/openai/v1",
    apiKey = groqKeyFromSecureSettings,
    model = selectedGroqModel
)

val raw = ProBrain.plan(
    openRouter, goal, history, answers,
    appLabel = screen.pkg, // Prefer the actual package; optional display label can accompany it.
    screenList = screen.forPrompt(), lang = lang.name
)
val decision = raw?.let { Planner.parse(it, screen, lang) }
// Before execution: obtain a fresh screen, apply Planner.guard and ActionPolicy, honor noAct.
// On null: keep the local fallback; do not retry with unredacted data.

val answer = ProBrain.explain(
    groq, "What does this export option do?",
    context = "App: ${screen.pkg}\n${screen.forPrompt()}"
)
// Or pass the foreground package separately:
val other = ProBrain.explain(groq, question, screen.forPrompt(), appLabel = screen.pkg)
```

Three-argument `explain` requires an `App:`, `App open:` or `Package:` line in context. Missing app identity
returns null because the money-app gate cannot otherwise be applied. Supply the real foreground identity;
do not substitute a generic safe app label for a bank screen. Flavor permission, explicit cloud opt-in,
settings, key storage, and wiring remain with the owner.

## Protocol, privacy and failures

- POSTs to `<baseUrl>/chat/completions`, with `model`, system/user messages, `temperature=0.2`, `max_tokens=200`.
  Base URL must be HTTPS, without credentials, query or fragment. Trailing slash is accepted.
- Goal, history, answers, screen labels, language, explanation question/context and app label are redacted
  before JSON serialization. Uses Redactor plus phone/email and short numeric-code masks; Indic digits are
  normalized. Recognizable PEM/OpenSSH private-key blocks are removed, including incomplete blocks. Screen IDs and roles are retained separately from their redacted labels.
- CallGuard and ActionPolicy block known money apps/payment screens before serialization/network access.
  Google Pay/GPay, Amazon Pay, BHIM and PayZapp display labels are also recognized. CallGuard additionally
  excludes its remote-access/IRCTC entries. Pattern-based identity checks require accurate caller metadata.
- Returns exactly the planner's two-line action/SAY format, or null. Strips nested `<think>` blocks, fences
  and `Line 1:`/`Line 2:` prefixes. Unclosed/malformed reasoning, invalid actions, missing SAY, tool/refusal/error
  responses and token-truncated replies are rejected. Explanations are capped at three sentences/800 characters.
- HttpURLConnection runs on Dispatchers.IO, using default TLS verification and bearer authorization.
  No redirects, retries, logs or persistence. Key stays out of request-inspection objects and config `toString`.
  Blank key/URL/model, invalid timeout, HTTP errors, malformed JSON and transport exceptions return null.
  Connect/read timeout each uses `timeoutMs`; this is not a hard total deadline across DNS/connect/streaming.
  Responses over 1 MiB and prepared user context over 100,000 characters are rejected.

## Offline validation and limits

`source ~/dev/android-env.sh` then `./gradlew testDebugUnitTest assembleDebug`: PASS.
**1,149 JVM tests**, including **14 ProBrain tests**, zero failures/errors/skips. `git diff --check`: PASS.
Tests cover provider URLs, headers/body, all request fields, redaction, money refusal before serialization,
blank-key suspend calls, planner-compatible outputs, malformed responses, thinking/fence cleanup, explanations,
timeouts, HTTP errors/redirects, bounded responses, UTF-8 transport and disconnects. Transport uses an in-memory
HttpURLConnection fake; no socket is opened.

Android's JVM runner stubs org.json. Tests inject a serialization-only codec using Gson already present in the
existing transitive test classpath; production uses only org.json. The Android JSON adapter and real provider
behavior have not been exercised on a phone or endpoint. No model identifiers are hard-coded: use a valid
model selected for the provider/account. Reasoning-heavy models may exhaust the 200-token cap and return null.

Privacy masks can over-redact years/ports and cannot identify arbitrary unlabelled secrets without recognizable context. Keep password/private-key contents out of supplied context/history regardless;
cloud opt-in is not permission to send secrets. The client validates syntax, not whether an action is correct
for the current screen; local policy and fresh target checks must remain authoritative. Reject stale responses
when the task/screen has changed. No fallback silently sends the original unredacted request.
