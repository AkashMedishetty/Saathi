# Demo 2: Sunday 09:30 (updated 03:25, everything below verified on the phone tonight)

**The one line:** Google Assistant *does* things for people who already know their phone. Saathi *teaches and
protects* people who don't: it points at the real buttons, explains every page, lets them do it with their own finger,
and brings them back when they get lost. On-device, in their language.

## Phone prep (08:45)
- [ ] Charged > 80 %, Wi-Fi on, volume high, Do Not Disturb off, screen timeout 10 min.
- [ ] `scripts/install.sh` (or just `scripts/enable-service.sh`), then `scripts/grant-heal.sh`.
- [ ] **Put the Settings icon on the home screen.** Leave Settings on its main page.
- [ ] WhatsApp chat list open once (clears old state). Family contact saved in Saathi Settings.
- [ ] Warm the brain: say "hello" once.
- [ ] Optional: uninstall JioHotstar for the install moment (item 7), else show the installed route.
- [ ] Laptop: `scripts/applog.sh 20` shows what Saathi decided, if a judge asks.

## Two apps on the phone
- **Saathi** (basic): everything on the phone, **no internet permission**. Tap **"Use this Saathi"** on its home screen to make it the active helper.
- **Saathi Pro**: same app plus the cloud brain (OpenRouter: Gemini 3.8 Flash → Qwen 3.8 Flash → Nemotron Ultra free) for power users.
  Tap **"Use Saathi Pro"** on its home screen to switch. One tap each way, no scripts. HackTracker is never touched.
- Pro demo line: "How can I access my VPS from this mobile through SSH?" → cloud picks JuiceSSH → Play Store Install (their tap) →
  "Set it up now" → each step planned by the cloud brain (AI monitor shows `CLOUD …`).
- Show the models working: after a mapped task (CPU · App map, 60 ms), ask something odd ("the writing on my phone is too tiny")
  → NPU understand + GPU rewrite; "write a birthday wish for my son" in Notes → GPU compose.

## The run (about 7 minutes)
| # | Say / do | What Saathi does | Why it matters |
|---|---|---|---|
| 1 | Home screen, Hindi: "अक्षर बड़े करो" | Glows the Settings icon → they tap → types "font size" (Do it) → glows the result → **glows the slider**: "slide the dot right, watch the words change" → after they move it: "Now it's 'Larger'. Is it good like this?" | Teaches the whole path and the last step; their finger, their choice |
| 2 | "Search for old Telugu songs on YouTube" | Opens YouTube at its start, glows search, types, skips the ad, plays. Ends: "Say pause, louder or close any time" | Deterministic app maps, not guessing |
| 3 | Mid-way: "what does the magnifying glass mean?" → "yes" | "The magnifying glass means search." (from the app map, not the model) → the task continues | Grounded answers, never loses the task |
| 4 | While it plays: "pause", "louder", "close this app" | Pauses, turns up, goes home | The everyday help Assistant doesn't offer mid-video |
| 5 | Tap into something unrelated (an ad), then "I'm lost" | "You're in Play Store, on the app's page. We were doing 'search… on YouTube'. Next: …" **[YouTube] [Go back]** | Knows where they are, brings them back |
| 6 | "Video call my son" | "WhatsApp or a normal phone video call?" → say "WhatsApp" → glows the camera button in the chat's top bar. **Never presses Call** | Their choice, their tap |
| 7 | "Take a screenshot and send it to my son on WhatsApp" | Takes it, opens WhatsApp's own Send-to with **that exact screenshot**, glows him, then the green arrow (theirs) | Multi-step, cross-app, no wrong photo |
| 8 | Laptop: `scam_apk` / `scam_sms` (or a second phone) | Red STOP card, spoken | Protects before harm |
| 9 | Optional: "Watch my serial on Hotstar" | Not installed → Play Store → glows Install (theirs). Installed → Search → type → Latest Episode → login page: "only you type your number/OTP; I'll wait" | The whole journey, safely |
| 10 | Optional: "Show my liked videos" | YouTube → You → Liked videos | Their own things, not a search |

**Hindi/Telugu anywhere:** "అబ్బాయికి నేను ఇంటికి చేరుకున్నాను అని మెసేజ్ పంపు" → WhatsApp, the words typed, Send glows.

## Architecture (60 s, during item 2)
- **Accessibility tree** (labels/ids, no screenshots) → **app maps** for 16 apps (known screens + routes, like app
  documentation) → the on-device planner only for unknown screens.
- **General rules, not scripts:** the Settings last step reads the page's own control (slider / switch / list);
  "where am I" names any app's page; popups are closed via their "Maybe later"; a filled search box → the keyboard's
  search key.
- **The halo is drawn around the target, never over it**, so taps stay the person's own. Saathi never presses Send, Pay,
  Install or Call, and never types OTP/PIN/passwords (login pages: it explains and waits; the model never runs there).
- **Models:** Gemma 3 1B on the Hexagon NPU (~0.4 s), Gemma 4 on the GPU, in a separate `:brain` process.
- **Survives the phone:** this vivo switches accessibility apps off whenever Settings opens; Saathi restores itself in
  ~50 ms and carries on (you'll see nothing).
- **No INTERNET permission.** Logs: test builds only, 2 days, on the phone.

## Backup triggers (laptop)
```
adb shell am broadcast -a com.saathi.GOAL -p com.saathi.app --es cmd scam_apk
adb shell am broadcast -a com.saathi.GOAL -p com.saathi.app --es cmd scam_sms
scripts/say.sh "अक्षर बड़े करो" HI
scripts/e2e/run.sh --quick
```

## Likely questions
- **"Isn't this just Assistant?"** Assistant needs you to know what to ask and does it for you. Saathi teaches, step
  by step, in their language, explains where they are when lost, and guards them from scams.
- **"Is it hardcoded per app?"** The top apps have maps (like documentation, deterministic, tested against real
  screens). Everything else uses general rules plus the on-device planner. Unknown screens never block: "where am I"
  and the ways back work in any app.
- **"Older phones?"** Maps, rules, scam shield, reminders and OCR need no model. The model tier adapts: NPU / GPU / none.
- **"Privacy?"** No internet permission; messages stay in RAM and are cleared after reading; secrets are never stored,
  typed or read aloud; the screenshot share reads only the newest screenshot, only when asked.
