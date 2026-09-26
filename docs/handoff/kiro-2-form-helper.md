# Kiro track 2: Form helper (paper forms via camera + online forms on screen)

Read `docs/handoff/00-README.md` first. Branch: `kiro/form-helper`. Package: `com.saathi.app.forms`
(tests in `app/src/test/java/com/saathi/app/forms/`). Start after the app-maps engine is in (or in parallel if you can).

## Why
Elderly people get stuck on forms: bank, pension, gas connection, hospital registration, online sign-ups. Saathi
should say, box by box, what to write, using what it knows about them. It must never handle secrets.

## 1. The person's profile (the only data forms may use)
`FormProfile` with: full name, father's/husband's name, date of birth, gender, address line 1/2, city, district,
state, PIN code, mobile number, email, occupation, nominee name/relation.
- Stored on the phone only, in app-private SharedPreferences (`saathi_profile`). Saathi's Settings screen will show it
  behind the screen lock (Claude wires the UI).
- **Never stored or filled:** Aadhaar number, PAN, bank account or IFSC, card numbers, OTP, PIN, passwords, signatures.
  For those, the instruction is "Write this yourself; don't tell anyone" (Aadhaar/PAN: "copy it from your card").
- Provide `FormProfile.load(ctx)` / `save(ctx)`. Keep the core model a pure data class for tests.

## 2. Paper forms (camera → OCR lines → what to write where)
Input: ML Kit OCR lines as `List<OcrLine(text: String, box: Box)>` (Claude converts from ML Kit), plus the photo size.
Output:
```kotlin
data class PaperField(val key: FieldKey?, val label: String, val labelBox: Box, val writeBox: Box,
                      val value: String?, val say: Say, val sensitive: Boolean)
object PaperForm { fun analyse(lines: List<OcrLine>, w: Int, h: Int, p: FormProfile): List<PaperField> }
```
- Detect field labels in English and Hindi (Devanagari): Name / नाम, Father's Name / पिता का नाम, Date of Birth /
  जन्म तिथि (DD/MM/YYYY boxes), Address / पता, PIN / पिन कोड, Mobile / मोबाइल, Signature / हस्ताक्षर, Aadhaar /
  आधार, PAN, Account No. / खाता संख्या, IFSC, Nominee / नामांकित…
  - Telugu print can't be recognised by ML Kit: skip it and say so.
- **writeBox:** the blank area to the right of the label on the same line (or the line below if nothing is to the
  right), clipped to the page. Character-box rows (□□□□) count as blank.
- **say:** "Write your name here: Ramesh Kumar", "Write your date of birth: 12 / 05 / 1956", "Sign here", "Write your
  Aadhaar number yourself. Don't tell anyone." In EN/HI/TE.
- Order fields top-to-bottom, left-to-right. Skip already-filled boxes (handwriting OCR gives text in the write area).

Claude draws the boxes on the frozen photo (`ui/PhotoGlow.kt` exists) and walks field by field with Next / Back.

## 3. Online forms (the accessibility tree of any app or website)
Input: the screen's input fields as `List<FieldNode(label: String?, hint: String?, resId: String?, box: Box,
password: Boolean, inputType: Int?)>`.
Output:
```kotlin
data class FillPlan(val node: FieldNode, val key: FieldKey?, val value: String?, val say: Say, val sensitive: Boolean)
object OnlineForm { fun plan(fields: List<FieldNode>, p: FormProfile): List<FillPlan> }
```
- Map labels/hints/ids to profile keys: "Full name", "First name"/"Last name" (split the name), "DOB", "dd/mm/yyyy",
  "Pincode", "Mobile", "Email", "Address", "City", "State"…, in English and Hindi.
- **sensitive = true** (never filled, glow only + "type this yourself"): password fields, OTP, PIN, CVV, card,
  account, IFSC, Aadhaar, PAN, UPI ID, anything with `password == true` or number fields named like those.
- Date formats follow the hint ("DD-MM-YYYY", "MM/DD/YYYY", "YYYY-MM-DD").
- Claude glows each field in order. "Do it" types non-sensitive values with `ACTION_SET_TEXT`. Saathi never presses
  Submit.

## 4. Tests
- Paper: build realistic OCR line sets (a bank account-opening form, a pension life-certificate, a hospital
  registration form; English and Hindi labels). Assert field detection, write-box geometry, values, sensitive flags,
  order.
- Online: IRCTC-like registration, a hospital appointment form, a Google-account-like form. Assert mapping, date
  formatting, the name split, sensitive detection. Test that a password or OTP is never given a value.

## Deliver
`docs/handoff/form-helper-DONE.md` with the integration calls. Due **04:00**.
