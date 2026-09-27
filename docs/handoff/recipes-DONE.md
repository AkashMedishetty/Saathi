# Recipes: typing and tolerant replay

Branch `codex/e2e`; merged main first at `8556a69`. Implementation commit: `0071bb1`.
Only Recipes.kt and its new test were changed, plus this note. Guide.kt and the phone were untouched.

## Service integration (owner to wire)

After debouncing `TYPE_VIEW_TEXT_CHANGED`, call:

```kotlin
Recipes.onText(stableFieldLabel, finalText, packageName, isPassword = source.isPassword)
```

The requested `Recipes.onText(label: String?, text: String, pkg: String)` also exists.
**Only use that overload after excluding password nodes.** Three strings cannot identify Android's password
flag when a field has an innocent label. The four-argument overload rejects flagged password fields and removes
any prior captured value for the same field. Do not log event text before filtering.

Use a stable, unique hint/content description as the label; never use the changing entered value.
Apply the same password exclusion/stable-label rule to input `onClick` events. Keep existing
`startRecording`, `stopRecording(context)`, `find(context, goal)` and `toFlow(recipe)` calls.

## Behavior

- Last safe text per `(package, trimmed case-insensitive label)` replaces earlier text at that field's original
  position. Input click steps become `role="input", fill=text`. Title and body remain separate. Empty or unsafe
  edits remove the earlier field value rather than leaving a partial secret behind.
- Redactor and ActionPolicy check labels/values; numeric-code checks also reject bare short numbers and
  embedded 4–8 digit codes, including Hindi/Telugu digits. Password, OTP/PIN, card/Aadhaar/PAN and account fields
  are rejected. Saved fills are rechecked on load and before replay. No text is logged here.
- Optional JSON `f` persists the fill; old tap-only entries omit it and continue to load without a fill.
- Targets remain ordered: escaped whole-label match; number/badge-stripped prefix with a word boundary;
  first up to three significant words with Unicode boundaries. All are case-insensitive. Empty stripped labels
  never create a match-everything target.
- Name lookup stems words and canonicalizes note-taking verbs: `take notes`, `make a note`, `notes` match.
  Multiword normalized names retain the 0.75 overlap bar; more specific names win equal-score ties.

## Verification and limits

`source ~/dev/android-env.sh` then `./gradlew testDebugUnitTest assembleDebug`: **PASS**.
**1,115 JVM tests**, including **21 Recipes tests**, zero failures/errors/skips. `git diff --check` passes.
Tests cover last-value replacement, title/body replay fills, cancellation, secret rejection and partial-value
removal, password metadata, legacy no-fill entries, target order/boundaries, and name-score thresholds.

No device/storage round-trip was run; Android JSON/SharedPreferences are platform stubs in JVM tests.
The service hook above remains unwired as requested. Identical labels in one app cannot identify separate
fields with this API. Missing labels are skipped. Clearing a field is not replayed. Numeric notes such as years
may be conservatively rejected; input is limited to 10,000 characters and labels to 60. Label fallbacks cannot
prove that a changed control has the same meaning; existing action-policy checks must remain active at replay.
