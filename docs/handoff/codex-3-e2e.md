# Codex track 3: Automated end-to-end test runner + scenarios (so "run all tests" is one command)

Read `docs/handoff/00-README.md` again. Worktree from the latest `main`:
`git worktree add ../saathi-codex-e2e -b codex/e2e main`. You write **scripts only** (`scripts/e2e/`); you don't run them
on the phone (only Claude or Akash do). **Never** add anything that touches HackTracker, never `uiautomator`, and
re-enable Saathi only via `scripts/enable-service.sh`.

## How Saathi can be driven from the laptop (debug build)
- **Give a request:** `adb shell am broadcast -a com.saathi.GOAL -p com.saathi.app --es lang EN|HI|TE --es goal "'<text>'"`.
  See `scripts/regress.sh` for quoting; Hindi/Telugu work.
- **Commands:** `--es cmd <name>` with:
  - `stop` (cancel the task), `doit` (press "Do it for me"), `dump` (tree → logcat tag `SaathiDump`);
  - `scam_sms`, `scam_apk` (demo triggers);
  - `eval --es goal …` (decision only, logcat tag `SaathiEval`);
  - `brain_load` / `brain_unload`, `overlay_on` / `overlay_off`.
- **Everything Saathi does is logged** on logcat tag `SaathiLog`, lines like:
  - `[goal] "…"`, `[understand] "…" → Intent2(…)`
  - `[show] key=<step> el="<label>" role=… bounds=[l,t][r,b] … text="…"`
  - `[finish] "…"`, `[alert] shield <id> STOP in <pkg>`
  - `[begin] …`, `[respond] …`, `[lookup] q=… a=…`, `[reminder] set …`, `[routine] due …`
  - `[policy] glow only|blocked: …`, `[service] destroyed|connected`
- **Tapping like a person:** tap the centre of the last `[show]` bounds with `adb shell input tap x y` (see `tapshow` in the
  notes below). Screenshot: `adb shell screencap -p > file.png`. Focused app: `adb shell dumpsys window | grep mCurrentFocus`.
- **Is Saathi still enabled?** `adb shell settings get secure enabled_accessibility_services | grep -c saathi`.

## Build
1. **`scripts/e2e/run.sh [pattern]`** runs every `scripts/e2e/scenarios/*.scn` matching the pattern. Per scenario:
   - **reset:** `stop`, HOME, optional `force-stop` of the listed apps, `adb logcat -c`;
   - **run** the steps;
   - **record:** pass/fail, the failing step, the last 15 `SaathiLog` lines and a screenshot, into
     `shots/e2e/<timestamp>/<scenario>/`;
   - **also check:** after each scenario, Saathi must still be enabled and `[service] destroyed` must not appear (else FAIL
     with reason "Saathi switched off").
   - **end:** a summary table (✅/❌, time, reason) and `shots/e2e/<timestamp>/summary.md`.
2. **A tiny scenario language** (one command per line, `#` comments):
   ```
   name: YouTube search (EN)
   apps: com.google.android.youtube          # force-stopped before
   say EN search for old telugu songs on youtube
   expect show key=search within 12
   tap-glow
   expect show key=type within 8
   doit
   expect show key=(go|pick) within 10
   tap-glow
   expect show key=pick within 12
   tap-glow
   expect log "\[finish\]|key=ad_" within 15
   ```
   - **Actions:** `say <LANG> <text>`, `cmd <name>`, `tap-glow`, `doit`, `back`, `home`, `wait <s>`,
     `start-activity <action>` (e.g. `android.settings.SETTINGS`, used to open Settings *as the person would*),
     `screenshot <name>`.
   - **Checks:** `expect show key=<regex> within <s>`, `expect log "<regex>" within <s>`,
     `expect focus "<regex>" within <s>`, `expect not log "<regex>" for <s>`, `expect enabled`.
3. **Scenarios: ≥ 40**, from `docs/DEBUG2-TEST-SHEET.md` sections A–D and the plan in `docs/PLAN-DEMO2.md`:
   - YouTube search/subscriptions (EN/HI/TE);
   - "video call my son" (expect the choice card `key=choose_video`), then the phone path (`tap` the second button: take
     coordinates from the `[show]`/screenshot as documented);
   - call my son; reminders (1 minute → `[routine] due` within 75 s); scam_sms / scam_apk alerts;
   - "watch my serial on hotstar" (not installed → `[missing]`, then Install glow `key=install`);
   - learn mode ("teach me how to use Spotify" → `key=open_app|find_app`);
   - Settings ringtone after `start-activity android.settings.SETTINGS` (expect `key=open_search`, then `type`, then
     `result`);
   - "read this letter" (`focus ReadActivity`); greetings / questions (`[respond]`);
   - Instagram / WhatsApp (they'll show a wall until logged in; accept `key=wall_`);
   - the Learn-section phrases;
   - stress: 10 back-to-back requests, with HOME/BACK mid-task.
4. **`--dry-run <logfile>`** parses a saved logcat file instead of the phone, so the parser and `expect` logic are
   testable. Add a few sample logs under `scripts/e2e/testdata/` and a `scripts/e2e/selftest.sh` that runs the dry-run
   cases.

Keep it POSIX bash + standard macOS tools (grep -E, sed, awk). Deliver `docs/handoff/e2e-DONE.md`. Due **00:30**.
