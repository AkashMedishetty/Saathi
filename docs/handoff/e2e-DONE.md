# E2E runner — plumbing fixed, quick suite and demo regressions added

Branch: `codex/e2e`

Worktree: `/Users/akash/IQOO Hackathon/saathi-codex-e2e`

Merged local `main` first, fast-forwarding to `7e96170` (includes Kiro's demo fixes).
Implementation commit: `bf86ca8`. This note follows that commit as requested.

## Exact commands

From the repository/worktree root, **Claude or Akash** run:

```sh
# Run all 49 scenarios:
scripts/e2e/run.sh

# Run just the eight demos:
scripts/e2e/run.sh --quick

# List/validate without any device access:
scripts/e2e/run.sh --list
scripts/e2e/run.sh --quick --list

# Entirely local verification:
scripts/e2e/selftest.sh --dry-run
```

**Run all** and **run quick** each print a Markdown table with ✅/❌, scenario name, elapsed seconds and reason, followed by passed/failed totals and the absolute evidence directory. They write the same table to `shots/e2e/<timestamp>-<pid>/summary.md`. A failure records its scenario source line and reason in that scenario's `result.txt`. `steps.log` shows completed steps and the cleanup boundary; `last15.log`, raw logs, replay log and a screenshot support diagnosis.

Exit codes: 0 = all selected assertions passed; 1 = scenario failure; 2 = invalid options/scenario/replay input. Failed or skipped-for-budget cases never count as PASS.

The two **list** commands print only selected scenario names, one per line (49 or 8), after validating syntax, regexes and reset allowlists. They do not resolve or invoke adb. The **selftest** prints each local check's PASS line and finishes with `92 total local checks passed, including real saved phone logs and local command plumbing.`

Full-suite video phone-path scenarios 07–09 require an operator-calibrated `--phone-choice X,Y` from the second button on a current screenshot. Without it those three cases fail explicitly. No coordinates are guessed. Quick and the new spoken-WhatsApp case do not need that flag. All-suite app/account prerequisites still apply; the command does not silently skip them.

## Priority plumbing fixes

- `device()` now delegates to `command.sh`'s isolated `bounded_command` helper. It closes stdin, so a command cannot consume subsequent `.scn` lines; captures the actual command's `wait` result; and reaps the watchdog without replacing that result. The watchdog cancels its sleep promptly, and real timeout returns 124. Local tests cover success, failure status 7, stdin EOF and timeout, without running adb.
- `logs.awk` trims leading whitespace before reading epoch timestamps. The actual `1790442524.726 ... SaathiLog:` lines now parse; goal → map route → halo occur at relative 0/41/84 ms in the raw capture.
- No implicit stop/HOME/force-stop runs at case entry or while its assertions are pending. Entry only checks health and clears logcat. Cleanup runs **after** the case's checks finish/fail and logs/health/screenshot are captured. Explicit HOME/BACK commands in stress scenarios remain intentional test steps.
- Primary step failures are preserved if final log capture also fails. A failed health query is distinguished from evidence that the service is disabled.
- Stale/null targets and consequential taps remain refused. Manual Settings search/result taps are permitted for the identified navigation keys; `doit` still refuses manual-only targets.

## Actual failed-run regression

Unmodified source evidence was copied from:

`shots/e2e/20260926-223803-23714/01-youtube-search-en/`

into `scripts/e2e/testdata/phone-223803/`.

Reproduce the successful **goal-delivery and map-routing** check, entirely offline:

```sh
scripts/e2e/run.sh \
  --scenarios scripts/e2e/testdata/phone-223803 \
  --dry-run scripts/e2e/testdata/phone-223803/replay.log
```

This prints one passing scenario. It does not claim that the trace contains a completed YouTube search; it does not.

Using `--dry-run scripts/e2e/testdata/phone-223803/logcat.log` instead correctly parses and matches the goal/route, then fails for missing enabled-state evidence. Raw logcat alone does not contain the separate health observations in `meta.log`. Both outcomes are tested. Synthetic fixtures remain clearly separate from this actual phone evidence.

## Quick mode: eight demos, bounded below four minutes

1. YouTube search EN through typed query and matching results glow.
2. Video-call choice card.
3. Hotstar missing → normal spoken `yes` → Play Store focus → Install glow.
4. `scam_sms` KYC warning.
5. `scam_apk` STOP warning.
6. Settings “अक्षर बड़े करो” (HI).
7. Learn Spotify entry.
8. One-minute reminder, waiting up to 75 seconds for `[routine] due`.

The eight plans have 189 seconds of declared expectation deadlines in total. Actual successful assertions return early. The runner stops beginning/continuing checks at 210 seconds and limits remaining transport/evidence commands to an outer 235-second budget, leaving a margin under four minutes. Exhausted cases are marked FAIL/not run. This is a bounded runner budget, **not a measured successful phone run**; OS/filesystem stalls are outside a shell timer's control.

## New demo-fix coverage

The full suite now contains **49 scenarios**; quick has eight separate short plans.

| Cases | Check |
| --- | --- |
| 41 | Hotstar's card accepts `say EN yes`; Play Store gets focus and Install glows. Case 17 now also sends the required reply. |
| 42 | `say EN WhatsApp` answers the choice; WhatsApp gets focus and a relevant target/wall. |
| 43 | Mid-task question emits `[aside] mid-task question`; spoken yes returns to search guidance. |
| 44 | `watch me: ...` emits `[teach] recording`; `done teaching` emits `[teach] saved`. |
| 45 | Liked videos emits `[route] own things`, with no new `yt_search` route. |
| 46 | Uber request while WhatsApp is foreground emits `[route] about this screen`. |
| 47 | The specified Telugu sentence reaches type/Send guidance, including equivalent mapped message steps. |
| 48 | Screenshot request continues into a photo-sharing goal/guide, without Send. |
| 49 | Chrome's form produces a positive `online form: N boxes` count and returns/retains Chrome focus. |

Every spoken reply is an ordinary goal broadcast; there are no hidden yes/choice commands. Current `[choice] choose_video` and legacy `[show] key=choose_video` are both understood. YouTube and Settings cases accept their verified numeric map keys alongside the corresponding legacy step names.

## Validation and limits

Passed locally:

- `scripts/e2e/selftest.sh --dry-run`: **92 checks** (57 scenario parses: 49 full + 8 quick; 35 replay/helper/selection checks).
- `/bin/bash -n scripts/e2e/run.sh scripts/e2e/selftest.sh scripts/e2e/command.sh`.
- Full offline `--list`: validates and returns all 49 cases.
- `git diff --check`; no authored changes under `app/src`.

No phone commands were executed. The command helper was tested with local shell executables and sleep only; dry-run selftests install a fail-on-use adb sentinel. No new dependencies, service toggling, clean, or app source edits. Android builds were not rerun for this scripts-only revision.

Honest gaps:

- Live runner behavior still needs Claude/Akash's rerun. Local tests cannot certify device latency, account state, labels, permission handling or app success.
- First-case state is operator-owned now that reset is strictly after a case. Start from a neutral state. Some older scenarios may still need additional map-key or route updates after observation.
- Choice needs WhatsApp available/choice-enabled; Hotstar must be absent; Telugu messaging needs the expected logged-in contact/chat state. Internet-dependent browser/form cases need connectivity.
- Case 44 is a command-routing regression: current code logs `saved null` for an empty recording, so that event alone is **not** proof of a persisted recipe or working replay.
- Cab routing is not a successful booking or proof that a shared location was understood. Question checks validate events, not factual correctness.
- Demo-fix step 9 (drag placement and Telugu clipping) remains a manual visual check. Screenshots support review, but a log-based script cannot prove that text is unclipped or the card stayed where it was dragged.
- Legacy calibrated taps fail if the card's current location differs. No Send, Pay, Install or Call target is clicked by these scenarios.

Further usage and the DSL are in `scripts/e2e/README.md`; coverage is in `scripts/e2e/SCENARIOS.md`.
