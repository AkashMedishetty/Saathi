# Paste this into a new Claude Code chat (working directory: /Users/akash/IQOO Hackathon/saathi-live)

We're at the iQOO Hackathon 2026 (Hyderabad, 30 h, started Sat 26 Sep 11:00). You're my build partner on **Saathi**,
an on-device Android companion that teaches elderly people to use their own phone (glow on the next tap in any app,
voice in EN/HI/TE, memory, scam guard, family help). **Plan B: every line of app code is written during the event,
in `saathi-live/`.** Commit after every green checkpoint.

## Read first
1. `/Users/akash/IQOO Hackathon/SAATHI_PLAYBOOK.md`: spec, state machine, decision ladder, data model, verified
   LLM/NPU APIs, build plan, test protocol, and the traps table (§15). Read §15 before touching each subsystem.
2. `/Users/akash/IQOO Hackathon/AI_KICKOFF_PROMPT.md`: working rules (§ "Rules for how you work").
3. Reference only (the prep prototype; don't copy code wholesale, rewrite it better):
   `/Users/akash/IQOO Hackathon/saathi/app/src/main/java/com/saathi/app/` (guide/, service/, llm/, ui/) and
   `app/src/test/.../RoutingTest.kt`.

## Where we are (12:20 Sat)
- **P0-1 skeleton is done and verified on the phone**: Gradle (AGP 8.13.2, Kotlin 2.4, compileSdk 36, target 35),
  manifest with no INTERNET, a11y xml, `SaathiService` + `GlowView` (glow on a fixed rect), a placeholder `MainActivity`,
  fonts/icons/NPU dispatch .so copied in. Dependencies so far are only core/appcompat/material/lifecycle/coroutines;
  add MediaPipe, LiteRT-LM and `qnn-runtime:2.47.0` in P0-4.
- Device: **iQOO 15, model I2501, Android 16, `ro.soc.model=SM8850` (confirmed), platform `canoe`, IR blaster = true**.
  adb serial `10BFAU1534000XR`. Models have NOT been pushed yet (`scripts/push-model.sh`).
- Scripts: `scripts/install.sh` (build + install + re-enable service + open), `enable-service.sh`, `shot.sh <name>`,
  `device-info.sh`, `push-model.sh`, `logs.sh`. Still to write: `say.sh` (with the debug broadcast in P0-2), `demo-reset.sh`.
- Build: `source ~/dev/android-env.sh`. Offline builds failed on two small jars (error_prone_annotations, listenablefuture),
  so build online. Never `clean`, never `--refresh-dependencies`.

## Decisions made in this session (they override the playbook)
- **UI/UX: top-notch, clean, Apple "new Siri"-level.** Think: a luminous, fluid, animated edge-glow / orb when Saathi
  listens or thinks; generous whitespace; very large, calm type; one clear primary action per screen; soft spring motion;
  it must still be easy for elderly eyes and hands (big targets, high contrast, no tiny chrome). Keep the marigold glow as
  our signature and the Tiro/Hind fonts for Indic scripts; reinterpret the §10 paper palette so it feels premium and modern
  rather than templated. Propose the visual direction (with a quick mockup screenshot on the phone) before building Home in P0-5.
- **No Paisa Pay / mock UPI app for now.** Money safety and the scam demo come in the later phases.
- The IR blaster exists, so P3 IR remote is possible later.

## Next
1. NPU check early (timebox 45 min): push the models, then a quick `LiteRtEngine(FastVLM, NPU)` test from code. Report ✓ + ms or the exact error.
2. **P0-2 Engine**: ScreenReader · Guide loop (throttle 350 ms, settle 650 ms, ladder steps 0–8) · Overlay card + bubble ·
   Speaker · safe "Do it" · debug broadcast + `say.sh`. State the definition of done, prove it on the iQOO with screenshots, commit.
3. Then P0-3 Skills (+ RoutingTest) → P0-4 LLM → P0-5 Voice + Home (the Siri-grade UI) → eval 1.
Keep `CHANGELOG.md` updated with time + what works.
