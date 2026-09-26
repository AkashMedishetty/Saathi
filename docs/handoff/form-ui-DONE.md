# Form-helper screens: DONE (Kiro, branch `kiro/form-ui`)

New files only:
- `ui/FormActivity.kt`, `ui/ProfileActivity.kt`
- `forms/FormUi.kt` (the screens' pure logic, so it is JVM-tested)
- `forms/FormUiTest.kt` (15 tests)
- 2 manifest entries

Nothing existing was edited except the manifest.

Checks:
- `./gradlew testDebugUnitTest assembleDebug` passes: 91 tests, 0 failures.
- The merged manifest has no INTERNET permission.
- No new dependencies: CameraX and ML Kit Latin + Devanagari are already in the build.

After building I deleted my worktree's APK and native-lib copies (disk), so `app/build` is 67 MB.

## Integration calls (for Claude)
```kotlin
FormActivity.start(ctx)      // "help me fill this form" / "फ़ॉर्म भरो" / "ఫారం నింపు", and the "Fill a form" chip in ReadActivity
ProfileActivity.start(ctx)   // "My details" row in SettingsActivity (asks for the screen lock itself)
```
- **Contexts:** both work from an Activity or from the service; `FormActivity.start` always adds `FLAG_ACTIVITY_NEW_TASK`.
- **Screen lock:** `ProfileActivity` asks for it itself, so don't wrap it in Settings' `withScreenLock`, or the person unlocks twice.
- **Guide:** both set `SaathiService.ownUiOpen` in onResume / onPause (same as ReadActivity), so the Guide stays out.
- **Manifest (already added):** `.ui.FormActivity` (portrait, not exported) and `.ui.ProfileActivity` (not exported, `adjustResize`).
- **Suggested SettingsActivity row:** `row(R.drawable.ic_person, s("My details", "मेरी जानकारी", "నా వివరాలు"), s("For filling forms", "फ़ॉर्म भरने के लिए", "ఫారాలు నింపడానికి")) { ProfileActivity.start(this) }`

## FormActivity flow
1. **Profile check.** If the profile is empty (no name, DOB, mobile or address), the card says: "Tell me your details once in Settings, My details. Then I can help with forms."
   - It has two buttons: **Add my details** (opens ProfileActivity) and **Continue without them**.
   - When they come back after saving, it goes straight to the camera.
2. **Camera.** Full-screen preview, close, torch, the hint "Fit the whole page in the picture", and the ReadActivity shutter. Captured in `CAPTURE_MODE_MAXIMIZE_QUALITY`.
3. **Photo check.** The bitmap is rotated upright and scaled to at most 2048 px, then checked on a 160 px-wide copy:
   - Mean luminance < 25 is DARK: "It's too dark. Hold the paper in the light and try again." (**Try again** / **Close**)
   - A luminance spread < 8 is BLANK. It is not refused, because a sparse page is close to that line. It only changes the message when OCR then finds nothing: "I can't see any writing…" instead of `PaperForm.nothingFound()`.
4. **OCR.** Rotation 0, both the Latin and the Devanagari recogniser. For each one: `FormNodes.ocrLines` → `PaperForm.analyse`. The result with more fields wins.
   - No fields: `PaperForm.nothingFound()` + **Try again**.
5. **Walk-through, one box at a time.**
   - The photo is zoomed to the current field with `FormUi.viewport`: about 60% of the page width, in the view's aspect ratio, kept inside the photo. The write box glows via the existing `PhotoGlow`, which is unchanged.
   - The card shows "Field 3 of 11", the instruction (`say`), and the **value in large selectable text** to copy. The value is never shown for `sensitive` boxes.
   - Buttons: **Back** / **Next** (Next becomes **Done** on the last box), **Read again**, **Close**. All are ≥ 64 dp.
   - Speech: the first box is spoken as "I found 11 boxes. I'll show you one at a time. Write your name here: …". Each later box is spoken when it is shown.
   - After the last box: "That was the last box. Read the form once more before you hand it in." (**Done** / **Start again** / **New photo**)

## ProfileActivity ("My details")
- **Screen lock:** `createConfirmDeviceCredentialIntent`, the same pattern as SettingsActivity. If they cancel, the screen closes. If the phone has no lock, it opens directly.
- **Header:** the title, "I use these only to help you fill forms. They stay on this phone.", and a red line: "Never type Aadhaar, PAN, bank or card numbers here."
- **Fields:** large labelled boxes in the chosen language (EN / HI / TE), grouped as You / Address / Contact / Nominee, with the right keyboards (phone, number, email, names with capitals).
  - Date of birth is a button that opens a `DatePickerDialog`, which can't be set in the future.
  - Gender is three chips; tap the selected one again to clear it.
- **Save:** stores the sanitized profile.
  - If the sanitiser emptied something, the screen stays open and shows and speaks: "Saathi never stores Aadhaar, PAN or bank numbers, so I left that out."
  - A wrong mobile, PIN code or email gets: "Please check: PIN code, Mobile number, Email."
  - Otherwise it shows "Saved. Now I can help you with forms." and closes.
- **Forget my details:** clears the profile after a confirm dialog.

## Tests (`FormUiTest`, JVM)
- **Photo check:** dark frames (black, noisy night room, just under 25), flat frames (a wall, blown out, empty) and printed pages (normal and dim) are OK; Rec. 601 luma from ARGB.
- **Walk:** Back / Next stay in bounds; "Field 2 of 3" in EN / HI / TE; the big value is never shown for Aadhaar.
- **Viewport:** stays inside the photo, contains the label and the box, keeps the view's aspect ratio and ≥ 60% of the page, shifts in at the edges, and falls back to the whole photo.
- **My details:** when the profile counts as empty; every field round-trips; secrets are told apart from typos, with the exact message; "+91 98765 43210" is accepted; every sentence exists in all three languages.

## Not verified / not reliable
- **I have not run either screen.** I can't use the phone and there is no emulator here, so the layout, camera, TTS, date picker and screen-lock flow are unverified by me. Everything else is compiled and JVM-tested. Please check first:
  1. The photo (top 42%, below the top bar) and the card (bottom) don't overlap badly on the iQOO. With a long instruction the card scrolls, but it can cover the lower part of the photo.
  2. The glow lines up with the real box on a real form. This depends on PaperForm's geometry, which has only been tested on synthetic OCR.
  3. The screen-lock prompt comes back to ProfileActivity with `RESULT_OK`.
- **OCR runs twice** (Latin, then Devanagari) on a 2048 px image. On the iQOO I'd expect about 1 to 2 s in total. That's a guess, not measured. The aura shows while it runs.
- **The BLANK threshold (spread < 8) is not tuned on real photos**, which is why it never refuses a photo.
- **The profile check only counts name, DOB, mobile and address.** A profile with only an occupation is "empty".
- **The Hindi and Telugu screen words are mine** and have not been reviewed by a native speaker.
