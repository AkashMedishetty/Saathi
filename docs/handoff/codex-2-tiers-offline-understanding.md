> **LATER (after demo 2).** Not tonight.

# Codex track 2: Device tiers + understanding without any AI model (for older phones)

Read `docs/handoff/00-README.md` again (same rules). Branch: `codex/tiers` (new worktree from the latest `main`).
Package: `com.saathi.app.tier` (tests in `app/src/test/java/com/saathi/app/tier/`).

## Why
Judges ask: "most phones don't have this NPU/GPU, so what then?". Saathi must run on three tiers and we must quote
real numbers:
- **Tier 1**, flagship Snapdragon with a matching NPU build: Gemma 3 1B on the NPU + Gemma 4 on the GPU.
- **Tier 2**, ≥ 8 GB RAM with any GPU: Gemma 4 E2B (or Gemma 3 1B) on the GPU.
- **Tier 3**, older or cheaper phones: **no LLM at all**. Everything must still work from rules: keyword skills, app
  maps, deep links, scam rules, reminders, OCR.

## Build
1. `object Tiers { fun detect(soc: String?, ramMb: Long, models: List<String>, sdk: Int): Tier }`. Pure; Claude passes
   `Build.SOC_MODEL`, `ActivityManager.MemoryInfo.totalMem` and the model file names.
   - Rules: an NPU build only counts if its file name contains this SoC (e.g. `sm8850`). Tier 2 needs a GPU `.litertlm`
     and ≥ 7.5 GB RAM (E4B needs ≥ 11 GB). Otherwise Tier 3.
   - Return which models to load and which features to switch on or off. Tests cover real device tables: SM8850 16 GB,
     SM8650 12 GB, Dimensity 8 GB, Helio 4 GB, Android 10.
2. `object RuleIntent { fun parse(goal: String): Intent2? }`. A **no-model** version of `guide/Understand.kt`'s
   `Intent2` (same fields: intent, query, app, device, person) using only rules:
   - intents: weather, lookup, watch, music, call, video_call, message, photo, alarm, reminder, directions, open_app,
     setting, question, book;
   - languages: English, Hindi (Devanagari + Hinglish), Telugu;
   - slot extraction: the query (strip verbs like play/watch/search/show me/लगाओ/చూపించు), the person
     (son/daughter/बेटा/కొడుకు or a name after "call/to"), the app (reuse `AppLauncher.latinAppNames`-style Indic name
     mapping; copy the table if needed).
3. **Measure it** with a JVM test that reads `scripts/eval-set.tsv` (utterance \t lang \t accepted answers separated by
   `|`, e.g. `intent:watch|skill:youtube`). Map your output to the same labels the way `Guide.decideOnly` does:
   - `intent:<intent>` for weather/lookup/watch/music/call/video_call/message/photo/alarm/directions/setting;
   - `question` for question;
   - `reminder` for one-time reminders, which `guide/Routines.kt` `Reminders.parse` already handles (call it).

   Print the accuracy and every miss. **Target ≥ 85%** without any model (Tier 1 with models is 97%). Don't overfit:
   also add 30 new held-out phrasings (10 per language) in `app/src/test/resources/tier3-heldout.tsv` and report that
   accuracy separately.

## Deliver
`docs/handoff/tiers-DONE.md` with:
- the accuracy on the eval set and on your held-out set (both numbers, honestly);
- the integration calls: where `Tiers.detect` runs, and how Claude swaps `Understand.parse` for `RuleIntent.parse` on
  Tier 3.

Keep it tight; credits are limited.
