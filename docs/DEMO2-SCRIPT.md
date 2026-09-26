# Demo 2: Sunday 09:30 (draft, 22:30; finalised after the overnight test runs)

**The one line:** Google Assistant *does* things for people who already know their phone. Saathi *teaches and protects*
people who don't, by explaining and guiding through what Android already has, on-device, in their language.

## Phone prep (08:45)
- [ ] Charged > 80 %, Wi-Fi on, volume high, Do Not Disturb off, screen timeout 10 min.
- [ ] Saathi enabled: Settings → Accessibility, or `scripts/enable-service.sh`; then `scripts/grant-heal.sh`.
- [ ] **Leave Settings on its main page.** Don't leave it on the Accessibility page (that's what drops Saathi,
      trap #45).
- [ ] WhatsApp + Instagram logged in. A family contact saved (Saathi Settings → family).
- [ ] "My details" filled (Settings → My details) with a demo name/address/DOB, for the form helper.
- [ ] Warm the brain: ask "hello" once (loads Gemma).
- [ ] Props: a printed **form** (a bank or hospital form), a printed **scam letter** ("KYC blocked, share OTP"), a
      second phone to send the WhatsApp APK / scam SMS (or use the laptop triggers below).
- [ ] Hotstar **not** installed (for the guided-install moment); Spotify installed.

## The run (≈6 min): side by side
| # | Say / do | Assistant would… | Saathi does | Why it matters |
|---|---|---|---|---|
| 1 | "How do I use Spotify" (Telugu: "స్పాటిఫై ఎలా వాడాలి") | open Spotify | **Learn mode:** "Let's find Spotify: tap its icon" (glow on the real icon), then each step: search, type, play. Then "Let me try": the person does it, and the glow comes only if they're stuck | Teaching, not doing |
| 2 | "Watch my serial on Hotstar" | "Hotstar isn't installed" | "Shall I help you get it?" → Play Store → glow **Install** (their tap) → "installing, please wait" → Open → "Want me to show you how to use it?" | The whole journey, guided |
| 3 | "Help me fill this form" (camera on the paper form) | nothing | Box by box on the photo: "Write your name here: Ramesh Kumar", "Date of birth: 12 / 05 / 1956", "Aadhaar: write it yourself, don't tell anyone" | Real-world help, privacy-safe |
| 4 | Second phone sends "SBI YONO update.apk" on WhatsApp | nothing | Red STOP card, spoken: "Someone sent an app file. Don't open it." Then the paper scam letter through "read this" | Protects before harm |
| 5 | "Video call my son" | calls on the default app | "WhatsApp or a normal phone video call?" (two big buttons) → it guides and **never presses Call itself** | Their choice, their tap |
| 6 | Online form in Chrome: "help me fill this form" | autofill (sometimes wrong) | Each box glows with what goes there; "Do it" fills name/DOB; **never OTP/password; never Submit** | Guardrails you can see |

**If time allows:** a reminder in Hindi set at the start ("10 मिनट में दवा की याद दिलाना") goes off during the demo.

## Architecture (60 s, while item 3 or 4 runs)
- **Accessibility tree** (labels/ids, no screenshots) → **app maps** (known screens + routes for the top apps) →
  planner only as a last resort.
- **The halo is drawn around the target, never over it**, so taps stay the person's own (Android's "obscured touch"
  rules).
- **Understanding:** Gemma 3 1B on the **Hexagon NPU** (~0.4 s) plus Gemma 4 on the GPU for the hard ones, in a
  separate `:brain` process. 97 % on a 103-phrase EN/HI/TE test set.
- **One safety layer** every action passes (850+ tests):
  - never touches money apps;
  - Send/Pay/Install/Call are the person's own tap;
  - OTP/PIN never typed;
  - answers checked against their source (no made-up numbers).
- **No INTERNET permission.** Everything runs on the phone.

## Backup triggers (laptop, if a prop fails)
```
adb shell am broadcast -a com.saathi.GOAL -p com.saathi.app --es cmd scam_apk
adb shell am broadcast -a com.saathi.GOAL -p com.saathi.app --es cmd scam_sms
adb shell am broadcast -a com.saathi.GOAL -p com.saathi.app --es lang TE --es goal "'స్పాటిఫై ఎలా వాడాలి'"
```
If Saathi ever switches off: Settings → Accessibility → Saathi. The self-heal usually restores it within a second.

## Likely questions
- **"Isn't this just Assistant?"** Assistant needs you to know what to ask and does it for you. Saathi teaches the
  person, step by step, in their language, then lets them do it themselves, and guards them from scams on the way.
- **"Older phones?"**
  - The app maps, rules, scam shield, reminders and OCR need no AI model.
  - The model tier adapts to the chip: NPU / GPU / none. (Phase 2 measures accuracy with no model.)
- **"Privacy?"** No internet permission; messages stay in RAM and are cleared after reading; secrets are never stored,
  typed or read aloud.
- **"Why not a bigger cloud model?"** Elderly users, patchy networks and privacy: sub-second on the NPU, offline.
