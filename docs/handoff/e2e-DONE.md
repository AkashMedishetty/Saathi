# E2E runner: healed restarts, preconditions, tonight's routes and map tests

Branch: `codex/e2e` · Worktree: `/Users/akash/IQOO Hackathon/saathi-codex-e2e`

Merged `main` first, fast-forward to **38e53a0**. Implementation: **44a04e7**.
Authored changes are confined to `scripts/e2e/**`, `app/src/test/**`, and this handoff.
No adb/device commands executed; no `app/src/main` edits, service changes, or dependency changes.

## Exact commands and output

From the worktree root, **Claude/Akash** run live:

```sh
scripts/e2e/run.sh                       # all 53 scenarios
scripts/e2e/run.sh --quick               # 9 plans, 8 runnable demos: one Hotstar plan skips
scripts/e2e/run.sh '5[0-3]-*'             # installed Hotstar, playlist, media, grounded aside
scripts/e2e/run.sh '42-*'                 # spoken WhatsApp choice, top-bar call glow
scripts/e2e/run.sh '47-*'                 # Telugu body fill, Send glow only
```

Both all/quick print a Markdown table with **✅ / ❌ / ⏭**, scenario name, seconds, and reason.
A recovered service restart reads **PASS (healed)**. A precondition mismatch reads, for example,
**SKIP: requires missing in.startv.hotstar (actual: installed)**.
The final line prints `N passed, N failed, N skipped. Evidence: <absolute directory>`.
The table is saved to `shots/e2e/<timestamp>-<pid>/summary.md`.

Exit codes: **0** = no failures (skips are counted separately), **1** = at least one failed case,
**2** = invalid CLI/scenario/replay input. Budget-exhausted cases remain FAIL, not SKIP.
Quick retains the 210-second execution / 235-second transport budget; this is not a measured phone runtime.

Full-suite cases 07–09 still require a current operator-calibrated `--phone-choice X,Y` for the second choice
button. Missing calibration fails explicitly. Other scenarios use logged glow bounds; new call/Send cases never tap them.

Local-only commands, safe without a device:

```sh
scripts/e2e/run.sh --list
scripts/e2e/run.sh --quick --list
scripts/e2e/selftest.sh --dry-run
scripts/e2e/run.sh --scenarios scripts/e2e/testdata/phone-024209 \
  --dry-run scripts/e2e/testdata/phone-024209/replay.log
source ~/dev/android-env.sh
./gradlew testDebugUnitTest assembleDebug
```

List prints 53 or 9 validated scenario names. Selftest finishes with **128 total local checks passed**.
The saved Settings replay prints **1 passed, 0 failed, 0 skipped** with **healed**.

## Runner changes

- Every `[service] destroyed` must reconnect within **2,000 ms inclusive**. The actual vivo restart is 75 ms.
  Timely reconnects add `healed`; late/missing reconnects fail. A later heal cannot erase an earlier long outage.
  A replay truncated before the deadline fails for insufficient observation. Live audits wait out a pending
  reconnect window. Enabled-state sampling also tolerates a short interruption; query errors remain failures.
- `requires-installed <pkg>` / `requires-missing <pkg>` precede actions/checks. They use read-only package
  inspection, validate package names against the existing app allowlist, and **SKIP before any scenario action,
  log clear, health check, screenshot or cleanup** on mismatch. They never install/uninstall anything.
  Query failures are FAIL. Dry replay requires initial timestamped package evidence, for example:
  `1790457164.000 I E2E: package=in.startv.hotstar installed`.
- Skips retain the reason and package metadata/replay; executed cases retain steps, actions, logs, screenshot,
  and final result. Stop/HOME/force-stop still occur only after checks and evidence capture.
- `expect bounds top < 500` verifies the fresh target without tapping. Numeric WhatsApp Call/Send keys are
  explicitly blocked from runner taps/Do it, including when their labels are localized.
- The whole-case audit rejects any `[plan]` after `[wall] ... setup=true`, even outside the scenario's explicit
  `expect not log` observation window. This invariant also applies across later goals in that same case.

## Scenario integration

| Case | Assertions and required preparation |
| --- | --- |
| Quick 03; full 17/41 | Hotstar **missing** → spoken yes → Play Store/Install glow. Installed app now causes SKIP. |
| Quick 03b; full 50 | Hotstar **installed**, signed out, Home ready → `map_hs_watch_0`, tap → `_1`, Do it → `_2`, tap → `[wall] ... setup=true`; no `[plan]`. No login fields touched. |
| 51 | Exact playlist phrase → YouTube `_0`, `_2`, `_3`, result **`_5`**. After tapping the result: playlist page must show **`_4`, Play all**, or direct video must finish with `playing`. Stops at Play all if a playlist opens. |
| 52 | Video starts, then normal spoken pause/louder/close goals each emit their `[media]` line. Close must focus the vivo/Android launcher. |
| 53 | Magnifying-glass question gets `The magnifying glass means search.` with `(step's own explanation)`; yes restores `_0`. |
| 47 | Exact Telugu sentence → `map_wa_message_3`, Do it → `map_wa_message_4`. Send only glows. Requires WhatsApp registered, matching son contact/chat already open. |
| 42 | Video-call choice → normal spoken WhatsApp → `map_wa_video_call_3`; bounds top < 500. Requires choice enabled and matching son chat ready. No call is placed. |

YouTube result assertions in existing EN/HI/TE and quick scenarios were updated from step 4 to **step 5**.
All new log expectations were checked against the merged app source; replies remain ordinary goal broadcasts.
The other quick demos remain video-choice, scam SMS/APK, bigger letters HI, learn Spotify, and a one-minute reminder.

## Map tests

- New `HotstarMapTest`: real home/search/results fixtures identify `hs_home`, `hs_search`, `hs_results`;
  exact Search tab and bounds; search bar with query fill; hero Watch button ID/bounds; login fixture returns
  Unknown rather than a mapped fill/completion.
- Shared **test-only** fixture parser now preserves Compose IDs containing spaces, as present in the real
  Hotstar trees. A dedicated assertion checks this without modifying the fixture files.
- `WhatsAppMapTest`: a different chat title returns WrongScreen for message/video/voice routes. Low bubbles
  labelled both `Video call` and `Video call · No answer` cannot become the call target; the top-bar target is
  selected when present, and its absence yields Scroll rather than a bubble glow.

## Validation and honest gaps

Passed locally:

- **128** runner checks: 62 distributed scenario parses plus 66 replay/helper/selection checks. Includes the
  unmodified real 02:42 restart replay, exact two-second boundary, late/absent/truncated reconnect, enabled
  sampling during restart, both package states, skip reasons/no actions, bounds, wall/planner ordering, and
  execution of every new route scenario on explicitly synthetic evidence. A fail-on-use adb sentinel verifies
  that dry mode never invokes it.
- **1,091 JVM tests**, zero failures/errors/skips; `./gradlew testDebugUnitTest assembleDebug` succeeds.
- Bash syntax and `git diff --check` pass.

No live rerun was performed here. The real Settings trace proves recovery and the following font-result glow,
not completed font adjustment. Synthetic scenario passes verify runner logic, not successful phone behavior.

Package checks do not establish account, saved-contact, screen, connectivity, or playback state. New precise
WhatsApp cases require the correct chat already open; YouTube cases requiring step 0 need Home; Hotstar watch
expects a signed-out login wall. These conditions are documented, not silently accepted as another route.
Media checks prove logged command dispatch and launcher focus, not audible volume or actual pause timing.

Prior limits remain: older cases may need further map/key updates; teach-save logs can say `saved null` and do
not prove a persisted lesson; the Uber case proves routing, not booking; card drag/Telugu text clipping need
manual visual review. Only the operator may recover Saathi using `scripts/enable-service.sh`.
