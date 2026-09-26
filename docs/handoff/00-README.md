# Saathi: rules for every agent (read first)

**Saathi** is an on-device Android companion that *teaches* elderly people in India to use their own phone. It:
- puts a glow on the next thing to tap in any app, and speaks each step in English, Hindi or Telugu;
- warns about scams;
- helps with papers and forms through the camera.

Everything runs on the phone:
- The accessibility tree is used for reading screens (labels and resource-ids, never screenshots).
- Gemma 3 1B runs on the Snapdragon NPU and Gemma 4 on the GPU, in a separate `:brain` process.
- ML Kit does text recognition (OCR).

The overall plan is in `docs/PLAN-DEMO2.md`. Demo 2 is **Sun 27 Sep, 09:30**.

## Hard rules (breaking any of these costs the team the hackathon)
1. **No INTERNET permission, no network code, no new dependencies** without asking Claude/Akash.
   - The merged manifest must stay free of `android.permission.INTERNET`.
2. **Never touch the phone.** Only Claude drives the one test phone over adb.
   - Never run `adb`, `uiautomator dump` (it suppresses other accessibility services) or anything that talks to the
     device.
   - Never disable, stop or modify the **HackTracker** app on it.
3. **Build hygiene:**
   - Never `./gradlew clean` and never `--refresh-dependencies`.
   - Build env: `source ~/dev/android-env.sh`.
   - Checks: `./gradlew testDebugUnitTest` (JVM unit tests) and `./gradlew assembleDebug`.
4. **Work in your own git worktree and branch**, e.g. `git worktree add ../saathi-<you> -b <you>/<track>`.
   - Only create or edit files in **your own package** plus the files your handout explicitly lists.
   - Do **not** edit `guide/Guide.kt`, `service/Overlay.kt`, `service/GlowView.kt`, `ui/ReadActivity.kt` or
     `service/SaathiService.kt`. Claude owns those and does the wiring, following your integration notes.
5. **Pure logic and JVM tests.**
   - Your code must be unit-testable on the JVM. Keep Android types out of the core logic.
   - `android.graphics.Rect` is a stub in JVM tests (all fields 0): use your own `data class Box(l, t, r, b)`.
   - Every behaviour gets a test in `app/src/test/java/com/saathi/app/<yourpkg>/`.
6. **Words for elderly people:**
   - Everything spoken or shown is a `Say` = `say(en, hi, te)` (see `guide/Lang.kt`).
   - Short, warm, plain words. Real Hindi (Devanagari) and real Telugu script; no transliteration.
   - One instruction per sentence.
7. **Safety:**
   - Saathi never types or stores passwords, OTPs, PINs, card numbers, CVV, Aadhaar or PAN numbers, or bank account
     numbers.
   - Saathi never presses Send, Pay, Install or Call by itself.
8. **No AI slop.**
   - No placeholder TODO logic, no fake data or demo stubs that pretend to work.
   - If something can't be done reliably, say so in your DONE note.

## Useful existing code
- `guide/ScreenReader.kt`:
  - `UiElement(id, label, role, bounds, …)` and `Screen(pkg, elements, allText)` are the live screen model.
  - Role is one of button / input / switch / slider / text.
- `guide/Flow.kt`: `Flow` / `Step` (a scripted guide), `rx(...)` for case-insensitive regex lists.
- `guide/Lang.kt`: `Lang` (EN/HI/TE), `say()`, `Prefs`.
- `guide/Skills.kt`: today's ~33 scripted skills (keyword → Flow).
- `guide/MessageScam.kt`, `guide/ScamGuard.kt`: today's scam rules.

## Screen fixtures
Claude captures real accessibility-tree dumps from the phone into `fixtures/trees/<app>/<screen>.txt`. Each line is
one node, indented two spaces per depth:

```
<Class> t=<text> d=<contentDescription> id=<resource-id without package> <flags C=clickable S=scrollable K=checkable F=focusable> ri=<range or null> acts=<action ids> Rect(<l>, <t> - <r>, <b>)
```

Example:
```
    ImageView t=null d=Search id=menu_item_1 CF ri=null acts=4,8,64,16,32 Rect(1260, 143 - 1440, 323)
```

Write a small parser for this format in your test sources.

## Delivering
Before you stop:
1. Commit to your branch.
2. Write `docs/handoff/<track>-DONE.md` covering:
   - what you built
   - the exact integration calls Claude should make (function signatures + where in the flow)
   - what's tested
   - what is **not** reliable
3. Make sure `./gradlew testDebugUnitTest assembleDebug` passes on your branch.
