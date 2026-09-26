# Form helper: DONE (Kiro, branch `kiro/form-helper`)

Package `com.saathi.app.forms` (10 files) with tests in `app/src/test/java/com/saathi/app/forms/` (49 tests, 5 classes).
The whole suite passes on the branch: `./gradlew testDebugUnitTest assembleDebug` runs 64 tests with 0 failures, and the
merged manifest has no INTERNET permission.
No new dependencies. No files outside `forms/` were touched.

## What I built
| File | What it does |
|---|---|
| `Model.kt` | `Box`, `OcrLine` / `OcrWord`, `FieldNode`, `PaperField`, `FillPlan` (the shapes the handout asked for). |
| `FieldKey.kt` | What a box asks for. Each key has a `sensitive` flag and a spoken name in EN / HI / TE. |
| `FormProfile.kt` | The profile data class, plus `load(ctx)` / `save(ctx)` in the `saathi_profile` SharedPreferences. It has no field for any secret, and `sanitized()` runs on every load, save and use. It empties any field that looks like an Aadhaar, card or account number (9+ digits), a PAN or an IFSC. |
| `Labels.kt` | Maps label text to a key (EN + HI, plus some Telugu words for online forms). The rules are ordered, and the order is the safety logic. |
| `Dates.kt` | Date layouts read from the hint or the printed boxes: `DD-MM-YYYY`, `MM/DD/YYYY`, `YYYY-MM-DD`, `DD-MMM-YYYY`, `D D M M Y Y Y Y`, `दिन/माह/वर्ष`. Also computes age. |
| `PaperForm.kt` | Turns OCR lines into `List<PaperField>` in reading order. |
| `OnlineForm.kt` | Turns `FieldNode`s into `List<FillPlan>` in reading order. |
| `LabelFinder.kt` | Pure. Finds a field's visible label from the text left of it or above it (web forms rarely set `labeledBy`). |
| `FormNodes.kt` | Thin Android adapters: ML Kit `Text` → `OcrLine`s, and the accessibility tree → `(FieldNode, AccessibilityNodeInfo)` pairs. Not unit-tested. |
| `Speech.kt` | Every sentence, in EN / HI / TE (internal). |

How the label rules are ordered:
- Secrets come first, so "Mobile OTP" is an OTP.
- "Name as per Aadhaar" and "Name on card" are names.
- "Aadhaar-linked mobile" is a mobile number.
- "PIN code" is postal. A bare "PIN" is a PIN code only if the form also asks for an address; anywhere else it is a secret PIN.
- Other people's details have no key, so they are never filled from the profile: "Mother's name", "Emergency contact", "Bank name", "Nominee's address".

What `PaperForm` handles:
- **Several fields on one line:** it cuts a line at blank runs (`____`, `□□□□`) and at colons, so "Name: ____ Age: ____" gives two fields.
- **Handwriting between labels:** in "Name: Ramesh Age:", it recognises "Ramesh" as the name's (already filled) answer.
- **Where to write:** the blank to the right of the label on the same row, up to the next label. If the label runs to the edge, the box is on the line below. For an address it also covers the ruled lines under it. Everything is clipped to the photo.
- **Already filled:** boxes with writing to the right or right below are skipped.
- **Gender printed as options** ("Male / Female / Other", "M / F"): it glows the one to tick.
- **Sections:** under "Nominee details", Name and Relationship use the nominee's details and everything else says it is about the nominee. "Joint holder / Witness / Guardian" sections say the box is about someone else. "For office use only" is skipped. "Signature of Applicant" switches back to the applicant.
- **Titles and declarations** ("STATE BANK OF INDIA", "I hereby declare…") are not treated as fields.
- **Nothing found:** `PaperForm.nothingFound()` says so and explains that Telugu print can't be read yet.

## Integration calls (for Claude)

### 1. Profile (Settings, behind the screen lock)
```kotlin
val p = FormProfile.load(ctx)                      // always sanitized
p.copy(fullName = …, dob = LocalDate.of(1956, 5, 12), gender = Gender.MALE, …).save(ctx)
```
Fields:
- fullName, surname (optional; set it when the family name comes first, which is common in Telugu names)
- fatherName, spouseName
- dob: `LocalDate`
- gender: `Gender.MALE / FEMALE / OTHER`
- address1, address2, city, district, state, pinCode (6 digits)
- mobile (10 digits; "+91 …" is normalised)
- email, occupation, nomineeName, nomineeRelation

`save` stores the sanitized copy. If a value in the UI comes back empty after a save, show "Saathi doesn't store numbers like Aadhaar, PAN or bank accounts".

`SettingsActivity` already has `withScreenLock { }`; wrap the profile editor in it.

### 2. Paper form (`ReadActivity`, a new "Fill a form" mode)
```kotlin
val lines = FormNodes.ocrLines(text)            // text = the ML Kit Text you already chose (Devanagari recogniser for HI)
val fields = PaperForm.analyse(lines, bmp.width, bmp.height, FormProfile.load(this))
if (fields.isEmpty()) speak(PaperForm.nothingFound().pick(lang))
else show(0)

fun show(i: Int) {                               // Next = i+1, Back = i-1
    val f = fields[i]
    photoGlow.show(bmp, listOf(Rect(f.writeBox.l, f.writeBox.t, f.writeBox.r, f.writeBox.b)))
    speak(f.say.pick(lang))                      // f.value is also the text to show large on screen (null for secrets)
}
```
- **Coordinates:** boxes are in the pixel space of the OCR input. If you use `InputImage.fromBitmap(bmp, rotation)` with a non-zero rotation, ML Kit's boxes are in the rotated frame. Rotate `bmp` first, then OCR it with rotation 0, and pass that bitmap to `PhotoGlow`.
- **Showing values:** show `f.value` in big text on the card so they can copy it. Never show anything for `f.sensitive`.

### 3. Online form (`Guide`: "help me fill this form" / "फ़ॉर्म भरो")
```kotlin
val pairs = FormNodes.fields(svc.rootInActiveWindow)
val plan = OnlineForm.plan(pairs.map { it.first }, FormProfile.load(svc))   // reading order, top to bottom
// per step:
val node = pairs.first { it.first === step.node }.second
glow(step.node.box)                                // screen coords (getBoundsInScreen)
speak(step.say.pick(lang))
// "Do it for me":
if (!step.sensitive && step.value != null && !node.isPassword) {
    node.performAction(ACTION_FOCUS)
    node.performAction(ACTION_SET_TEXT, Bundle().apply { putCharSequence(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, step.value) })
}
```
- **Matching the node:** use `===` (identity). `FieldNode` is a data class, so two identical fields would compare equal.
- **Before typing:** re-read the screen and re-find the node (Guide trap #11). Check `isPassword` on the fresh node again; it's cheap, and it's a second guard on top of the planner's.
- **Moving on:** step to the next field when the person taps it or when the current field's text changes.
- **Submit:** Saathi never presses Submit / Next / Pay. Say the last field's line, then stop.

## What's tested (JVM)
- **Paper, English bank account-opening form:**
  - all 15 fields in order, their values, and EN / HI / TE words;
  - DOB boxes read "12 / 05 / 1956";
  - Gender points at the right option (including "Female" vs the "male" inside it);
  - an already-filled Occupation is skipped;
  - the nominee section uses the nominee's details and never the applicant's;
  - bank-use boxes are skipped;
  - write-box geometry: blank to the right, stops at the next label, the address goes below over the ruled lines, and every box stays on the page and off its own label;
  - the order of the input lines doesn't change the output.
- **Paper, Hindi pension life certificate:** 11 fields, Hindi words, a handwritten mobile number skipped, an unknown PPO number, and the secret account number and signature.
- **Paper, hospital registration:**
  - a mixed Hindi/English line;
  - "Name: Ramesh Age: ____ Sex: M / F";
  - a label at the edge with handwriting below;
  - someone else's number and IDs Saathi doesn't have;
  - exact OCR-word geometry.
- **Paper, other:** clipping to the photo, titles and declarations that are not fields, and empty or unreadable input.
- **Online:**
  - IRCTC-like registration (16 fields, first/middle/last split, DOB from the hint, captcha, username, password);
  - hospital appointment (OTP field, a long hint that mentions OTP but belongs to the mobile field, an appointment date that is not filled as the DOB, optional Aadhaar, Aadhaar-linked mobile);
  - Google-like sign-up (password caught by inputType even without the password flag);
  - card form (only the name is filled);
  - date formats, bare PIN postal vs secret, Hindi / Telugu labels, unlabelled fields, reading order;
  - `LabelFinder` on a Chrome-style tree fixture, parsed from the handoff's dump format.
- **Safety:**
  - 400 random online forms and a paper form with every label, run against a profile with secrets typed into every box: a sensitive field never has a value, and no secret ever appears in a value or in any spoken sentence;
  - I checked that this test fails when the final guard in `OnlineForm.plan` is removed.
- **Profile:** sanitize, mobile / PIN / email validation, name split (with titles, a single name, and the family name first), map round-trip, and junk in storage.

## What is NOT reliable (please read)
- **No real photos or real form trees.** No form fixtures existed on main, so all paper tests use synthetic OCR lines modelled on ML Kit's line output, and online tests use hand-built fields plus one hand-written tree.
  - On a real photo, ML Kit may read box rows as letters (`口`, `O`, `0`), drop faint ruled lines, merge two columns into one line, or split a label from its colon. The thresholds (row overlap 50%, minimum room to the right 2.5 line heights) are not tuned on real photos.
  - First thing to do on the phone: photograph one real form and dump `FormNodes.ocrLines` to logcat.
- **Handwriting detection is heuristic:**
  - If OCR misses faint handwriting, the box looks blank and Saathi asks for it again. This is harmless.
  - A printed hint next to a label that isn't in parentheses may look "filled", and that field is skipped.
- **Box geometry without OCR words** is proportional to character count. Pass ML Kit's elements (the adapter does) for exact positions.
- **Telugu print** can't be read (ML Kit has no Telugu recogniser). Saathi says so, and Telugu labels only work on online forms.
- **Online forms:**
  - Split Day / Month / Year boxes, dropdowns, spinners and date pickers are not filled; they get "type it yourself" or "not sure". This is deliberate: "Day" could be any date.
  - `ACTION_SET_TEXT` works on normal EditTexts. Some JavaScript web forms (React) don't register a value set without key events, so check the field after "Do it" on the phone.
  - `LabelFinder` and the `labeledBy` / `contentDescription` label choice in `FormNodes.fields` are untested against a real Chrome form tree. Capturing one (for example an IRCTC or hospital page) would close this gap.
  - "Street/Lane" and "Flat No." both map to address line 1.
- **Names:** a first/last split without a saved `surname` assumes the family name comes last.
- **Words:** the Hindi and Telugu sentences are mine and have not been reviewed by a native speaker. Hindi avoids अपना/अपनी so the gender agreement is always right.
- **Sections** depend on the heading text ("Nominee details", "For office use only"). A form with no headings treats every box as the applicant's, except labels that name the nominee or another person themselves.
