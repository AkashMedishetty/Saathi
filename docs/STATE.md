# Saathi: resume here (written Sat 26 Sep 2026, 22:10, by Claude before the 01:50 session reset)

Repo: `/Users/akash/IQOO Hackathon/saathi-live` (branch `main`). **Demo 2: Sun 27 Sep, 09:30.**
Phone: iQOO 15 over adb (`source scripts/env.sh`). **Never touch HackTracker.** No INTERNET permission.
Never `gradlew clean`. **Disk is nearly full**: delete finished worktrees' `app/build`.

## Read first
- `docs/PLAN-DEMO2.md`: the analysis, the pivot (Saathi explains + navigates what Android already does) and the
  decisions: TV coach dropped; video call asks WhatsApp/phone.
- `docs/handoff/*`: the agents' tasks and their DONE notes (integration calls).
- `SAATHI_PLAYBOOK.md`: traps #44 (models in the `:brain` process) and #45 (Settings / obscured taps).

## Done tonight (all on main)
- **Halo pointer:** 4 windows around the target, no dimming. Taps reach apps (fixed the YouTube search "does
  nothing"). The card avoids the target, the keyboard and the typed box (`updateIme` runs every tick).
- **Settings:** Saathi never opens it or taps in it. The person opens it via the glowing icon or a swipe-up hint.
  Self-heal re-adds only Saathi's own a11y entry (`scripts/grant-heal.sh`, demo phone only).
- **YouTube search end to end:** self-described rows aren't containers; real video/playlist rows; ads are never
  targets; targets need ≥60 px visible.
- **Acts, not chats:**
  - `respond()`: greeting → one line; app mentioned → "Do you want to use X?"; question → Google results + grounded
    answer.
  - The video offer is only for real-world how-tos.
  - App names in Telugu/Hindi script are recognised.
- **Video call:** a two-button choice (WhatsApp | Phone); the phone path = dialer + glow.
- **Endings:** "Let me try" (practice) + "Something else" (mic).
- **Learn mode** (Learn section + "teach me / how do I"): the person opens the app from its icon; no deep-link
  shortcuts.
- **Streaming apps:** named ones beat the YouTube default. A missing app → guided Play Store install (Install
  glow-only, then Open, then an offer to teach it).
- **Scam shield v2 (Codex).** Demo triggers: `--es cmd scam_sms | scam_apk`.
- **Guardrails (Codex):**
  - ActionPolicy is the final gate in `act()`;
  - no "Do it for me" on manual-only buttons;
  - AnswerCheck/Grounded on answers;
  - Redactor on logs and memory.
- **Reader:**
  - dark-frame check; 3-part explanation;
  - "Ask about it" → answered only from the paper;
  - **TODO:** an OCR blur gate + AnswerCheck on the explanation itself (the "Oek" hallucination on blurry text).
- **Forms (Kiro):**
  - `FormActivity` (paper) + `ProfileActivity`;
  - "help me fill this form" → the online walk-through (`Guide.formHelp`) or the camera;
  - a reader chip; a Settings row.
- **One-time reminders** (EN/HI/TE, exact alarms). Models run in the `:brain` process.

## In flight (agents)
- **Kiro:** app maps (`docs/handoff/kiro-1-app-maps.md`) on branch `kiro/app-maps`, checkpoint ~00:15. Integrate:
  `AppMaps.route(goal)` before the LLM, and `next()` per screen as a tick step before the planner.
- **Codex:** the e2e runner (`docs/handoff/codex-3-e2e.md`) on branch `codex/e2e`: `scripts/e2e/run.sh`, 40+
  scenarios.

## Next steps
1. Reader blur gate + AnswerCheck on the explanation; strip markdown and preambles.
2. Integrate Kiro's app maps as they land.
3. Merge and run Codex's e2e; fix the failures. Run `scripts/regress.sh` (update R1/R3/R4: TV dropped) and
   `scripts/eval.sh` (was 97%).
4. After Akash logs in: the WhatsApp / Instagram / Uber flows. Capture fixtures with `scripts/capture-tree.sh`.
5. Tile leftovers: "Turn the TV volume up" → watch; "Back up my WhatsApp chats" → message.
6. The 09:30 demo script: side-by-side "Assistant vs Saathi" (`docs/PLAN-DEMO2.md` §2).

## Update ~23:40 (Sat 26 Sep 2026)
- **Merged Kiro's app maps:** engine + YouTube, Settings, Google Photos, Play Store journey, Spotify, Google Docs,
  WhatsApp. Integrated via `guide/MapBridge.kt` + `Guide.beginMap` / `mapTick`:
  - a map route runs before settingsTask/phoneHowTo and before any model;
  - "done" only after the last step (`doneNeedsLastStep` forced);
  - Play Store done → "Show me how to use it".
- **Merged Codex's e2e runner** (`scripts/e2e/run.sh`, 40 scenarios, selftest 63/63), committed on Codex's behalf.
  Run: `scripts/e2e/run.sh [pattern]`. Codex is back at 01:54: give it the e2e failures to fix.
- **Regression 3/5/9/10 pass on the phone.** R8 was fixed after the done-guard (YouTube glows Search as step 1).
- **Next:** run the full `scripts/e2e/run.sh` on the phone; fix the failures. Merge Kiro's remaining maps
  (Maps/cab, Chrome, Phone, Messages, Instagram, Clock) as they land.
