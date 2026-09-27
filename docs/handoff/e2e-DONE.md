# E2E quick demo alignment — part 1

Branch `codex/e2e`; merged main first at `0643e9c`. No adb/device calls or protected-source edits.

## Commands (live runs belong to Claude/Akash)

```sh
scripts/e2e/run.sh                 # unchanged 53-case full suite
scripts/e2e/run.sh --quick         # 14 plans; one Hotstar alternative skips → 13 runnable cases
scripts/e2e/run.sh --quick --list   # offline: prints 14 validated names
scripts/e2e/selftest.sh --dry-run   # offline: 155 checks pass
```

Runs print ✅/❌/⏭, scenario name, elapsed seconds and reason, followed by passed/failed/skipped totals and the
absolute evidence path. Same table: `shots/e2e/<timestamp>-<pid>/summary.md`. Executed cases save their step log,
result, logcat/replay, last 15 logs and screenshot. Preconditions skip before scenario actions or cleanup.
Exit 0 means no failures (inspect skip counts), 1 means failed cases, 2 means invalid input.

Quick stops execution at **330 seconds**, with a **355-second** outer command/evidence budget. Budget exhaustion
is FAIL, never a manufactured pass. This is a runner bound under six minutes, not measured phone success;
host OS/filesystem stalls remain outside the shell timer. No live rerun was performed here.

## Changed quick cases

- **01:** Search glow → human tap → any of `map_yt_search_2`, `map_yt_search_3`, `submit_yt_search` passes.
  Stops there; it does not claim completed playback.
- **02:** `[choice] choose_video` → ordinary `say EN WhatsApp` → `map_wa_video_call_3`, bounds top < 500.
  Never taps Call.
- **03b:** optionally accepts and taps one `nag_.*` glow before `map_hs_watch_0`; continues the search to the
  signed-out login wall. Repeated/unexpected popups still fail instead of being ignored.
- **06:** handles entry at font steps 0/1/2, taps the result, requires `[settle] settings_font: slider`.
- **09:** “where is my aadhaar card” → `[docs] AADHAAR → true send=false`, Photos focus.
- **10:** “send my aadhaar card to my son” → `[docs] AADHAAR → true send=true`, pick or Send glow. No send tap.
- **11:** active YouTube search → “i'm lost” → `[where]`.
- **12:** launches `com.vivo.notes` using MAIN/LAUNCHER and package restriction, checks focus, says
  “type buy milk and bread”, requires `[write] com.vivo.notes`. Does not press Do it.
- **13:** exact Tatkal goal; at most one `[coach] start` across the full 20-second window.
  Zero is allowed by this assertion; it proves absence of a restart loop, not booking or coach dispatch.

Logs were read from current Guide source, including docs, shareImageFlow, writeHere, map nag, settle and coach.
New DSL: single-level `if show key=<ERE>` / `endif`, and `expect at-most 1 log "<ERE>" for N`.
Every conditional tap retains the existing fresh-target and consequential-action checks.

## Evidence and validation

**155 local checks pass**, including 67 distributed plan parses, real 04:26 YouTube skip-ahead replay,
real Hotstar popup bounds, synthetic popup/no-popup continuations, all three font entry states, all demo
highlights, and count tests for zero/one/two starts, exact deadline and truncated observation. An adb sentinel
fails on any attempted device invocation in selftest. Real traces live in `scripts/e2e/testdata/phone-042621/`.
The real Hotstar trace contains only popup arrival; continuation is tested synthetically, not claimed as a
recorded phone success. Bash syntax and `git diff --check` pass. No Android build needed for this scripts part.

Operator prerequisites: a saved/indexable Aadhaar photo; Photos; registered WhatsApp and configured son contact
with the appropriate chat reachable; Notes reopening an editable note; Hotstar signed out/on Home for 03b.
Missing document/account/editor state remains a meaningful failure. No credentials or document contents are
inserted by these cases. Existing healed-restart handling, package SKIP, post-check cleanup, and login-wall
planner rejection remain. Full-suite cases 07–09 still need calibrated `--phone-choice X,Y`.
