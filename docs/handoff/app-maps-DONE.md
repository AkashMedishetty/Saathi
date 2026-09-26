# App maps: DONE (Kiro, branch `kiro/app-maps`)

Package `com.saathi.app.maps` (pure Kotlin, no Android types) + one file per app in `maps/apps/`. Tests in
`app/src/test/java/com/saathi/app/maps/` read the real dumps in `fixtures/trees/`. Committed app by app; this note
is updated with each commit.

## Status
| App | Routes | Verified against real fixtures? |
|---|---|---|
| YouTube | search & play X · subscriptions · history · like · share to WhatsApp | home, search (empty + typed), results (ad + video, playlist), subscriptions, you: **yes**. Watch page (like/share), History page: **no fixture**, selectors from YouTube's labels |
| Settings (vivo + AOSP search page) | ringtone · font size · brightness · wallpaper · Wi-Fi · Bluetooth · dark mode · language · screen timeout · storage · any other setting (the person's words) | **No dump of Settings exists.** Tests use hand-built trees shaped like what Saathi saw on this phone. Every route = search bar → type → the result row (never the history chip) → Done on the result page |
| Google Photos | open latest · crop (→ Save copy) · brightness (Adjust → Brightness → slider → Save copy) · share to WhatsApp | **No dump.** Hand-built trees from Photos' labels. "Save copy" is only offered after the crop/brightness step (`needsReached`) |
| Play Store | install X (search → type → the listing by the **known developer**, never an ad or look-alike → **Install: risky, glow only** → Wait while installing → Open → `next`: "Want me to show you how to use X?") · update all · uninstall X (risky ×2) | **No dump.** Hand-built trees. When the app hands over (the foreground package becomes the new app after the Open glow), treat it as done: say `doneSay`, offer `next`, start your learn-app guide |
| Spotify | play X (Search → box → type → the result naming X, never an ad) · open a playlist · like a song · shuffle · download a playlist (says Premium is needed) | **No dump.** Hand-built trees from Spotify's labels |
| Google Docs | new document (+ → New document → type; naming via "Untitled document" is in the same line) · save as Word (.docx) (⋮ → Share & export → Save as Word, or Send a copy → Word → OK) · open recent | **No dump.** Hand-built trees |
| WhatsApp (+ Business) | video call · voice call (both **risky**, glow only) · message (types the `text` slot; **Send risky**) · send a photo (attach → Gallery → photo → **Send risky**) · chat backup · open a shared location (→ Google Maps) | **No dump; UNVERIFIED.** Built on WhatsApp's long-standing ids (`menuitem_search`, `search_input`, `conversations_row_contact_name`, `conversation_contact_name`, `entry`, `send`, `input_attach_button`). Re-check once the owner registers WhatsApp and a dump exists |

**Checkpoint 1 (engine + YouTube + Settings + Photos): done.** Full suite `testDebugUnitTest assembleDebug` passes
(1036 tests, 0 failures); merged manifest has no INTERNET.

## Overlaps with existing Skills (your call on the order)
`AppMaps.route()` also matches goals that today go to Skills: brightness, dark mode, Wi-Fi/Bluetooth *settings*,
font size (Skills' `font` flow). The map routes go through Settings search (works on every skin); the Skills open
the exact Settings page by intent. If you keep the Skills first for those, call `AppMaps.route()` only when no
Skill matched, or skip ids starting with `settings_` except `settings_ringtone / wallpaper / language / timeout /
storage / search`.

## Integration calls (for Claude)

### 1. Pick a route before the LLM
```kotlin
val route = AppMaps.route(goal)                       // null → your existing Skills/planner path
val slots = MapSlots.of(route, goal, Prefs.family(ctx)) // "query" → "hanuman chalisa", "term", "contact", "place", "app"
launch(route.pkg)                                     // open the app (or ask them to, for Settings: trap #45)
var reached = 0                                       // furthest step index returned so far
```

### 2. Convert the live tree (pre-order!) on each screen change
```kotlin
fun toNodes(root: AccessibilityNodeInfo): List<Node> {
    val out = ArrayList<Node>()
    fun walk(n: AccessibilityNodeInfo?, depth: Int) {
        if (n == null || !n.isVisibleToUser) return
        val r = Rect().also(n::getBoundsInScreen)
        out += Node(n.viewIdResourceName, n.text?.toString(), n.contentDescription?.toString(),
            n.className?.toString()?.substringAfterLast('.') ?: "", n.isClickable, n.isScrollable, n.isCheckable,
            Box(r.left, r.top, r.right, r.bottom), depth, editable = n.isEditable, checked = n.isChecked, selected = n.isSelected)
        for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
    }
    walk(root, 0); return out
}
```
The order matters: parent right before its children. The engine uses it for row text and for "what is drawn on top"
(a later clickable node overlapping a target covers it, e.g. YouTube's tab bar over a result row).

### 3. Decide and render
```kotlin
when (val d = AppMaps.next(route, pkg, nodes, reached, slots)) {
    is Decision.Glow -> { reached = max(reached, d.step); glow(d.box); say(d.say); d.why?.let(::teach)
                          // "Do it": d.fill != null → ACTION_SET_TEXT(d.fill) on the node at d.node.box; else tap
                          // — but NEVER when d.risky (Install / Send / Pay / Call / Request / Confirm…)
                        }
    is Decision.Scroll -> { reached = max(reached, d.step); say(d.hint) }           // "Scroll for me" = ACTION_SCROLL_FORWARD
    is Decision.WrongScreen -> { d.node?.let { glow(it.box) }; say(d.backHint) }   // glow-only back arrow
    is Decision.Wait -> say(d.say)                                                  // installing / loading: just wait
    Decision.Done -> { say(route.doneSay); route.next.firstOrNull()?.let(::offer) }
    Decision.Unknown -> planner()                                                   // not a mapped screen
}
```
- `d.box` is the **visible** part of the target (clipped to the screen and to bars drawn over it). Glow that, not
  `d.node.box`.
- To tap on "Do it", re-find the node on a fresh read by `d.node.resId` + `d.node.label` (+ box centre inside the
  fresh node), like trap #11.
- Speech: `d.say` already has the slots filled in.
- Settings: glow only (Saathi never taps inside Settings, trap #45); typing into the Settings search box is the
  one allowed action.

## Engine rules (tested)
- **Latest step wins**: the highest step whose screen matches and whose target is visible. People skip ahead.
- **Never back behind `reached`**: if the forward steps of this screen have no visible target → `Scroll` for the
  earliest of them, not an older step. Going back is only chosen when this screen has no step ≥ `reached`.
- **Targets**: resource-id first, then label (`desc`, else `text`; for an unlabelled *clickable* row, its
  children's text). A non-clickable match glows as its clickable row. Candidates must be on screen (no negative
  coordinates), ≥ 64 px each side, and ≥ 25 % uncovered; `LARGEST` or `TOP` (first result) wins.
- **Risky**: a step's `risky`, and any target labelled Install / Update / Uninstall / Send / Pay / Buy / Call /
  Video call / Request… / Confirm… / Book … / Delete / Post / Share, is `risky = true` even if a map forgot.
- **Screens**: all `must`, no `mustNot`; the most specific (most `must`) wins. Some screens use slots
  (`yt_results_for` = results for *our* query, vs `yt_results` for something else → "tap the search bar").
- **`needsReached`**: a step only counts once an earlier step was reached (Photos' "Save copy" is visible in the
  editor before the edit is done). **`doneNeedsLastStep`**: a page that merely contains the goal's word is not
  "done" until the last step was reached (Settings, Photos edits).

## Not reliable yet
- YouTube watch page (like / share) and History: no dump yet; selectors are YouTube's English labels.
- The dumps have no "selected" state, so YouTube's tabs are told apart by their content.
