# Kiro track 1: App maps (deterministic navigation for the top apps)

Read `docs/handoff/00-README.md` first. Branch: `kiro/app-maps`. Package: `com.saathi.app.maps`
(`app/src/main/java/com/saathi/app/maps/`, tests in `app/src/test/java/com/saathi/app/maps/`).

## Why
Today, unknown steps go to a small on-device LLM that guesses from the screen and often wanders (loops in the wrong
app, types a whole sentence into search). The popular apps' screens are stable and every button has a resource-id and
label in the accessibility tree. We want **maps**: known screens + known routes → instant, correct, explainable
guidance. The LLM will only choose a route and fill slots.

## What to build

### 1. The model (pure Kotlin, no Android types)
```kotlin
data class Box(val l: Int, val t: Int, val r: Int, val b: Int)
data class Node(val resId: String?, val text: String?, val desc: String?, val cls: String,
                val clickable: Boolean, val scrollable: Boolean, val checkable: Boolean, val box: Box, val depth: Int)
// label = desc ?: text

/** How to recognise a screen: all `must` selectors present, none of `mustNot`. */
data class ScreenDef(val id: String, val must: List<Sel>, val mustNot: List<Sel> = emptyList())

/** Selects one node: prefer resource-id, fall back to label regex, optionally role/clickable. Lists = try in order. */
data class Sel(val resId: String? = null, val label: Regex? = null, val clickable: Boolean? = null)

data class MapStep(
    val on: String,                 // ScreenDef id where this step applies
    val target: List<Sel>,          // what to glow (first match wins)
    val say: Say,                   // EN/HI/TE instruction, e.g. "Tap Subscriptions at the bottom."
    val why: Say? = null,           // teach mode: one line on why/what it is
    val fill: String? = null,       // slot name to type (e.g. "query"), only for inputs
    val risky: Boolean = false,     // Send / Call / Pay / Install: glow only, Saathi never taps it
    val scrollHint: Say? = null,    // if the target isn't visible on that screen
)
data class Route(val id: String, val pkg: String, val goals: List<Regex>, val slots: List<String>,
                 val steps: List<MapStep>, val done: List<Sel>, val doneSay: Say, val next: List<Say> = emptyList())
data class AppMap(val pkg: String, val name: String, val screens: List<ScreenDef>, val routes: List<Route>)
```
(`Say` = `Map<Lang, String>` from `guide/Lang.kt`. You may import `com.saathi.app.guide.Say`, `say` and `Lang`.)

### 2. The engine
```kotlin
object AppMaps {
    fun route(goal: String): Route?                                           // EN/HI/TE goal → best route (regex; no LLM)
    fun routeById(id: String): Route?
    fun screenOf(pkg: String, nodes: List<Node>): String?                     // which ScreenDef matches
    fun next(r: Route, pkg: String, nodes: List<Node>, doneSteps: Int): Decision
}
sealed interface Decision {
    data class Glow(val step: Int, val node: Node, val say: Say, val why: Say?, val fill: String?, val risky: Boolean) : Decision
    data class Scroll(val step: Int, val hint: Say) : Decision        // on the right screen, target off-screen
    data class WrongScreen(val expect: String, val backHint: Say) : Decision  // e.g. an old sub-page: "Tap back"
    data object Done : Decision
    data object Unknown : Decision                                     // not a mapped screen → Claude falls back to the planner
}
```
Rules:
- The **latest** step whose screen matches wins. People skip ahead or already stand halfway.
- Never go backwards to an earlier step once a later one has matched, unless its screen matches *and* the later one
  is impossible.
- Prefer resource-ids. Labels must also work in Hindi/Telugu UI where you can (use `desc` / ids).
- Choose the visible, on-screen, largest clickable match; ignore zero-size or off-screen boxes (negative coordinates
  happen on vivo).

### 3. Maps (in `maps/apps/*.kt`, one file per app), with routes
- **YouTube** `com.google.android.youtube`: search for X (search icon → type → first real result, skipping ads and
  "Sponsored"); subscriptions; library/history; like a video; share a video to WhatsApp (stops at Send: risky).
- **WhatsApp** `com.whatsapp`: video call a contact; voice call; message a contact (type → Send risky); send a photo;
  start/stop a status view; open settings → chat backup. *Fixtures arrive after the owner registers WhatsApp; start
  from your knowledge of WhatsApp's resource-ids and mark unverified routes in the DONE note.*
- **Google Photos** `com.google.android.apps.photos`: open latest photo; edit → crop; edit → brightness (Adjust → Light
  or Brightness); save a copy; share.
- **Settings (vivo OriginOS)** `com.android.settings`: ringtone; font size; brightness; wallpaper; Wi-Fi; Bluetooth;
  dark mode; language; screen timeout; storage. Use the Settings search bar route as the generic fallback
  (search → type slot → the result row, never the history chip).
- **Phone / Contacts / Messages** (Google Dialer `com.google.android.dialer`, vivo dialer if present, Google Messages
  `com.google.android.apps.messaging`): call a contact; read latest SMS; block a number.
- **Chrome** `com.android.chrome`: search; open a new tab; clear the cookies banner (tap "Accept" / "Reject",
  whichever exists: prefer reject non-essential).
- **Google Maps** `com.google.android.apps.maps`: directions to X (search → X → Directions → Start).
- **Play Store** `com.android.vending`: find app X → Install (risky: glow only).
- **Camera** (vivo `com.android.camera`), **Clock** (`com.android.deskclock` / vivo), **Instagram**
  `com.instagram.android` (open profile; view reels; post a photo: stops at Share, risky).

Each route needs good goal regexes in EN/HI/TE: "show my subscriptions", "सब्सक्रिप्शन दिखाओ",
"సబ్‌స్క్రిప్షన్లు చూపించు", "how do I crop a photo", "फोटो काटना", etc. Include vague forms.

### 4. Tests (the most important part)
- Parse the fixtures (`fixtures/trees/<app>/<screen>.txt`, format in the README).
- For every route: walk its steps across the fixture screens and assert the right node is chosen, the scroll/wrong
  screen decisions, and `Done`.
- Goal-matching tests: at least 5 phrasings per route across EN/HI/TE, and no cross-matches (e.g. "video call my
  son" ≠ "watch a video").

## Integration notes you must write (Claude wires it)
Claude will:
- call `AppMaps.route(goal)` before the LLM;
- convert live `AccessibilityNodeInfo` → your `Node`;
- call `next()` on each screen change;
- render `Glow` / `Scroll` / `WrongScreen` with Saathi's overlay.

Tell Claude anything else you need, e.g. slot extraction for `query`/`contact`, which already exists in
`guide/Slots.kt` `SlotExtractor`.

## Timeline
- Fixtures for YouTube / Photos / Settings / Dialer / Messages / Chrome / Maps / Play Store / Clock / Camera are in
  `fixtures/trees/` from ~22:00. WhatsApp and Instagram come once the owner logs in.
- Please have the engine + YouTube + Settings + Photos done by **01:00** (Claude integrates those first), the rest by
  **03:00**.
