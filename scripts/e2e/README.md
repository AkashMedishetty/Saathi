# Saathi E2E runner

Only Claude/Akash run live mode. No installation, service toggling or device setup happens automatically.

```sh
scripts/e2e/run.sh                    # all 49 scenarios
scripts/e2e/run.sh --quick            # eight demo checks, bounded below four minutes
scripts/e2e/run.sh '4[1-7]-*'          # spoken replies and demo routing regressions
scripts/e2e/run.sh --quick --list     # list/validate offline
scripts/e2e/selftest.sh --dry-run     # entirely local; adb is never invoked
```

Quick covers YouTube search EN (through the matching results glow), the video-choice card, Hotstar missing → spoken yes → Install glow, SMS/APK warnings, bigger letters HI, Spotify learning entry and a one-minute reminder. It does not tap Install/Call/Send. Execution stops at 210 seconds; in-flight command/evidence work has a 235-second outer budget. Remaining scenarios are FAIL/not-run on exhaustion, never a fabricated PASS. Eight successful scenarios normally need much less time; phone performance has not been measured here. A hung host filesystem/OS is outside the shell timer guarantee.

## Lifecycle and evidence

At case entry the runner only checks service health and clears logcat. It does **not** send HOME, stop a task or force-stop an app. Each scenario runs sequentially. After its assertions finish or fail, the runner captures logs, audits service health, and saves its screenshot. Only then does it send stop/HOME and force-stop the case's explicit allowlisted apps. `steps.log` marks this cleanup boundary. Explicit `home`/`back` lines inside stress scenarios are intentional assertions' actions, not implicit resets.

The first scenario therefore starts from the operator's current app state; prepare a neutral screen and cancel unrelated tasks before starting. Cleanup does not uninstall, clear data, log out, or re-enable anything. A disabled service fails the run; the existing `scripts/enable-service.sh` is the only recovery route, invoked by the operator.

Every run prints a Markdown table with ✅/❌, scenario name, elapsed seconds, and reason; then totals and the absolute evidence directory. Exit 0 means all selected assertions passed, 1 means at least one failed, and 2 means invalid CLI/scenario/log input. Per-case files include `result.txt` (failing source line), `steps.log`, `actions.txt`, `last15.log`, raw `logcat.log`, health/focus `meta.log`, combined `replay.log`, and `final.png`. Dry replay writes an explanatory `final.txt`, never a fake screenshot.

## Replay

```sh
scripts/e2e/run.sh --scenarios scripts/e2e/testdata/phone-223803 \
  --dry-run scripts/e2e/testdata/phone-223803/replay.log
```

This real trace proves only goal delivery and `yt_search` routing; it contains no completed YouTube interaction. The matching test scenario deliberately checks those facts only.

The unmodified `logcat.log` in that directory also parses. Running that instead of `replay.log` fails the final service-health assertion because raw logcat lacks the separately recorded enabled-state evidence. That is an evidence gap, not a parser failure or proof the service was disabled.

Supported log formats: epoch (including Android's leading indentation), threadtime with midnight rollover, and relative fractional seconds in clearly synthetic test fixtures. Missing timestamps are rejected. Additional timestamped records use:

```text
1790442524.000 I E2E: enabled=1
1790442525.000 I E2E: focus=mCurrentFocus com.android.vending/Activity
1790442527.000 I E2E: end
```

Each positive assertion consumes its match. A new action fences off old logs. Negative assertions need observation through their entire deadline. Focus checks require saved focus evidence. Each scenario needs a final enabled=1 observation after the checked events; any `[service] destroyed` in its saved trace fails even if the service reconnects.

## Scenario language

One command per line. Full-line `#` comments; `apps:` also supports an inline comment. No shell evaluation or variable expansion.

- `name: text`, `apps: allowed.package ...` (force-stopped **after** the case).
- `say EN|HI|TE text`: normal goal broadcast, including replies `yes`, `WhatsApp` and `done teaching`.
- `cmd stop|doit|dump|scam_sms|scam_apk|brain_load|brain_unload|overlay_on|overlay_off`; `cmd eval text`.
- `tap-glow`, `doit`, `back`, `home`, `wait seconds`, `screenshot safe-name`.
- `start-activity android.settings.SETTINGS`.
- `open-url https://httpbin.org/forms/post`: the sole allowed URL, opened in Chrome for the form test.
- `expect show key=<ERE> within <seconds>`.
- `expect log "<ERE>" within <seconds>`, `expect focus "<ERE>" within <seconds>`.
- `expect not log "<ERE>" for <seconds>`, `expect enabled`.

Deadlines are integers from 0 to 300. EREs use `grep -E` syntax; backslashes are preserved. Null or stale glow bounds fail. Consequential Send/Pay/Call/Install/etc. targets are refused. `doit` refuses `noAct=true`; person-style Settings search/result taps allow that flag only on the named Settings navigation targets. Other manual-only targets remain refused.

Legacy video phone-path scenarios 07–09 use `tap-choice phone`; they require `--phone-choice X,Y`, calibrated from the second button on a current screenshot. There are no guessed device coordinates. Quick mode and the new spoken-WhatsApp scenario need no coordinate calibration.

## Known limits

- These scripts have not been rerun on the phone by Codex. Passing selftests validate runner behavior, not app behavior.
- YouTube search/subscriptions and Settings ringtone/font assertions accept the corresponding current numeric map keys as well as legacy keys. Other legacy cases may still need key updates after observation. No test substitutes a broad “any show” for a specific expected step.
- Choice tests need WhatsApp installed/choice-enabled; Telugu message needs registration and a saved contact. Hotstar cases need it absent. Browser answers and the form page need connectivity.
- Teach-once regression checks recording/save routing. The current app logs `saved null` when there were no taps; this dispatch test can match that and does not claim a recipe was persisted. Actual nonempty recording and replay still need physical interactions.
- The cab regression proves the current-screen route, not recognition of a real shared location or a successful booking.
- The form test requires a nonzero field-count log; missing instrumentation produces failure. It never fills/submits the remote form.
- Demo-fix step 9 (drag placement and Telugu clipping) still needs visual/manual inspection. Log matching cannot honestly prove pixels are unclipped or that a drag stayed put.
- A new scenario stops on its first failure. Failed transport queries are not proof of a disabled service. Command timeouts return 124 internally and are reported as failed steps.
