# Kiro track 3: The form-helper screens (paper-form camera flow + profile editor)

Read `docs/handoff/00-README.md` again (same rules). New worktree from the latest `main` (your form helper is merged):
`git worktree add ../saathi-kiro-formui -b kiro/form-ui main`.

**Disk is nearly full (≈1 GB).** Before building, delete your old worktree's build outputs
(`../saathi-kiro-forms/app/build`), and keep your new `app/build` small. Never run `gradlew clean` in main.

## Build
1. **`ui/FormActivity.kt`** (new): "Help me fill this paper form".
   - Camera preview (CameraX, as in `ui/ReadActivity.kt`; read it, don't edit it). A big shutter and the same visual
     language (`ui/Ui.kt`, `ui/Theme.kt`: paper card, pine buttons, marigold accent, Tiro/Hind fonts; big targets
     ≥56 dp; nothing tacky).
   - After the shot: **detect dark or blank frames** (mean luminance < 25 or very low variance) → "It's too dark, hold
     it in the light and try again". Rotate the bitmap upright, then run ML Kit OCR with rotation 0: Latin + Devanagari
     recognisers, the same deps ReadActivity uses.
   - Then `FormNodes.ocrLines` → `PaperForm.analyse(...)`. Show the frozen photo with the current field's `writeBox`
     glowing. Use `ui/PhotoGlow.kt`, or improve it in a new file if needed.
   - Show a card with: the instruction (`say`), the **value in large text** so they can copy it (never for
     `sensitive`), "Field 3 of 11", and **Back / Next / Read again / Done**. Speak each instruction with
     `service/Speaker` in the chosen language (`Prefs.lang`).
   - No fields found → `PaperForm.nothingFound()` + "Try again".
   - Profile empty → a gentle prompt: "Tell me your details once in Settings → My details, then I can help with
     forms." Include a button that opens the profile editor.
   - Public entry: `FormActivity.start(ctx)`. Register it in the manifest (portrait, no INTERNET).
2. **`ui/ProfileActivity.kt`** (new): "My details" editor for `FormProfile`.
   - Wrap in the screen lock the way `SettingsActivity` does (`withScreenLock { }`; copy the pattern, don't edit
     SettingsActivity).
   - Large labelled fields with EN/HI/TE labels, a date picker for DOB, and gender chips.
   - Show the line "Saathi never stores Aadhaar, PAN or bank numbers" if the sanitiser empties something.
   - Public entry `ProfileActivity.start(ctx)`.
3. **Unit tests** for the dark-frame check (pure function on a luminance array) and any pure helpers.

## Integration notes (Claude does these)
Claude will:
- add a "Fill a form" chip in ReadActivity;
- route "help me fill this form" / "फ़ॉर्म भरो" / "ఫారం నింపు" (paper) to `FormActivity.start`;
- add a "My details" row in SettingsActivity.

Write the exact calls in `docs/handoff/form-ui-DONE.md`. Due **01:30**. Claude tests on the phone.
