# App maps: DONE (Kiro, branch `kiro/app-maps`)

Package `com.saathi.app.maps`:
- pure Kotlin, no Android types;
- one file per app in `maps/apps/`, and `SystemApps.kt` for Phone, Messages, Clock, Instagram and Camera.

**15 apps, 44 routes.** Tests in `app/src/test/java/com/saathi/app/maps/` (63 tests) read the real dumps in
`fixtures/trees/` wherever one exists. Committed app by app.

**Final gate:** `./gradlew testDebugUnitTest assembleDebug` passes, with 1071 tests and 0 failures, and the merged
manifest has no INTERNET permission. No new dependencies, and no files outside `maps/`.

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
| Google Maps | directions to X (search → type → the suggestion naming X → Directions → Start) · cab via Maps (… → Directions → ride tab → Uber) | **No dump; UNVERIFIED** (ids `search_omnibox_text_box` / `search_omnibox_edit_text`, labels) |
| Uber | book a cab to X (Where to? → type → the place → ride **risky** → Choose/Confirm **risky** → Confirm pickup **risky** → Wait while finding a driver) | **No dump; UNVERIFIED** |
| Chrome | search X · new tab · dismiss popups/cookie banners (**reject / only-necessary first**, accept only if it's the only way) · the notifications prompt → "No thanks" | notifications prompt: **real dump, verified**. New-tab page (`search_box_text`), `url_bar`, tab switcher, banners: unverified |
| Phone (vivo dialer + Google dialer) | call a contact (Contacts tab → search → type → the contact → Call **risky**) | Dial tab: **real dump, verified** (package assumed `com.android.contacts`, the dump doesn't say). Contact search / detail: unverified |
| Messages (Google + vivo) | read latest SMS (first-run "Continue" handled) · block a number | first-run screen: **real dump, verified**. List / thread: unverified |
| Clock (vivo + Google) | turn off the X o'clock alarm (the switch **inside that alarm's card**) · show how to add an alarm | alarm list: **real dump, verified** (package assumed `com.android.BBKClock`). Edit screen: unverified |
| Instagram | open profile · reels · post a photo (Share **risky**) | **No dump; UNVERIFIED** (owner logs in) |
| Camera (vivo + Google) | take a photo · selfie (switch to front first, then the shutter) | **No dump; UNVERIFIED** |

**Checkpoint 1** (engine + YouTube + Settings + Photos) was committed at 22:23. The rest followed app by app.
`RegistryTest` checks the whole registry, so a typo fails a test instead of breaking on stage. It checks that:
- every step's screen exists;
- every sentence exists in EN / HI / TE, in Devanagari / Telugu script;
- every `{slot}` used in speech is declared, and `fill` slots are declared;
- no step is both risky and a typing step;
- ids and packages are unique.

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
val slots = MapSlots.of(route, goal, Prefs.family(ctx))
// slots: "query" (hanuman chalisa), "term" (ringtone), "contact" (Rahul / the family contact for "my son"),
//        "text" (what to write), "place" (Charminar), "app" + "developer" (whatsapp / WhatsApp LLC), "time" (8:00)
// Topic routes carry presets (settings_ringtone → term = "ringtone").
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

- **Play Store hand-over:** after the `Open` glow, when the foreground package becomes the new app, say
  `AppMaps.fillIn(route.doneSay, slots)`, offer `route.next`, then start your learn-app guide for that package.
- **App hops:** some routes deliberately end where the next app begins (YouTube/Photos share → WhatsApp,
  WhatsApp location → Maps, Maps cab → Uber). `next` returns `Unknown` once you're in the other app. Say `doneSay`,
  then (optionally) start the next route: `wa_*`, `maps_directions` or `uber_cab` with the same slots.
- **Order vs Skills:** see "Overlaps" above.
- **Speech with brackets:** `d.say` is final. When filling `route.doneSay` or `route.next` yourself, use
  `AppMaps.fillIn(say, slots)`. It fills the `{slots}` and drops `[optional]` parts whose slot is unknown.

## What each app's evidence is
- **Real phone dumps** (verified):
  - YouTube: home, search, typed, results with an ad, playlist results, subscriptions, You.
  - The Chrome notifications prompt.
  - The vivo dialer.
  - The Google Messages first-run screen.
  - The vivo Clock alarm list.
- **Hand-built trees** in the same format, from each app's known labels and ids (everything else). These prove the
  engine logic and the route order, **not** that the labels match this phone. First job on the phone: capture one
  dump per new screen with `scripts/capture-tree.sh` into `fixtures/trees/<app>/`. The tests pick them up, and a
  wrong label shows as a failing assert, not a wrong glow.

## Not reliable yet
- **No dump at all:**
  - YouTube's watch page (like / share) and History.
  - Settings.
  - Photos, Play Store, Spotify, Docs, WhatsApp, Maps, Uber, Instagram, Camera.
  - Phone contact search / detail, the Messages list / thread, the Clock edit screen.
- **Package names inferred:**
  - vivo dialer = `com.android.contacts`, vivo Clock = `com.android.BBKClock` (the dumps don't record the package).
  - `alsoPkgs` covers the Google variants. If `AppMaps.mapFor(pkg)` returns null on the phone, add the real package
    to that map's `alsoPkgs`.
- **No "selected" state in the dumps**, so YouTube's tabs are told apart by their content. The Subscriptions tab is
  recognised by its empty state or by "Manage" / "All subscriptions". A full subscriptions feed isn't dumped yet.
- **Settings are English-only:** the search term is English ("ringtone"), and so are the result-row labels.
  Settings in Hindi would need Hindi terms.
- **The selfie route** relies on the camera's switch button changing its label to "Switch to rear camera".
- **Split date boxes and pickers**, and Uber's map pin, are not handled.
- **Everything is guidance.** No route taps a `risky` target, and the Settings routes must stay glow-only (trap #45).
