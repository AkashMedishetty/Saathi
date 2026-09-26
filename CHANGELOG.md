# Changelog

## Sat 26 Sep
- 12:18 P0-1 skeleton: Gradle project (AGP 8.13.2, Kotlin 2.4, compileSdk 36), manifest without INTERNET,
  accessibility service that draws the marigold glow on a fixed rect, placeholder home, device scripts.
- 12:19 P0-1 verified on the iQOO 15 (I2501, Android 16, SM8850 / canoe, IR blaster present): service connects,
  glow draws over the launcher. APK permissions: no INTERNET.
- 12:22 Models pushed to the iQOO (Gemma 4 E2B GPU, FastVLM sm8850, Qwen 0.5B).
- 12:31 **NPU verified on the iQOO 15**: FastVLM-0.5B (sm8850) on the Hexagon NPU via LiteRT-LM, load 182 ms, reply ~450 ms
  (vision encoder on NPU too). Gemma 4 E2B on the Adreno GPU: load 4.5 s, reply 690 ms.
  Fix: LiteRT-LM 0.17.1 SIGBUS'd (its runtime calls `get_hooks`, absent from every released dispatch .so) → pinned 0.16.1.
  Debug probe: `adb shell am start -n com.saathi.app/.ui.MainActivity --es probe NPU [--es model text] [--ez vis false]`.
- 12:32 Scripts: enable/disable-service only add/remove Saathi's own entry, never touching HackTracker.
- 12:50 **P0-2 Engine verified on the iQOO** (EN + HI): `say.sh "make the text bigger"` → Settings opens → glow on OriginOS's
  real row "Display, brightness & eye protection" (label quoted in the card) → "Do it" taps it → scroll hint + "Scroll for me"
  → "Font size and weight" → OriginOS's class-less font slider (detected via rangeInfo/SET_PROGRESS) → "I'm done".
  ScreenReader · Guide ladder 0–10 (throttle 200 ms, settle 450 ms, instant glow-clear on tap) · compact draggable card
  (top/bottom, remembered) · Siri-style edge aura while thinking · living orb bubble · Speaker · safe Do it · scam guard ·
  Memory · say.sh (`--doit --stop --dump --aura`).
- 12:58 **P0-3 Skills**: 24 skills in 6 groups (family, watch, everyday, learn, fix, money safety) + "open any app";
  EN/HI/TE keywords, slots (people/family, times incl. बजे/రాత్రి, places, HI/TE search-phrase cleanup).
  RoutingTest green (50 phrases EN/HI/TE + slots). Verified on the iQOO: "हनुमान चालीसा लगाओ यूट्यूब पर" (HI) → YouTube
  opens → glow on the search box → Hindi card.
- 13:00 **P0-4 LLM**: LlmManager (lazy load, crash guard, 3-min idle unload; NPU→GPU→CPU; Gemma 4 E2B on GPU loads in ~5 s,
  decides in ~750 ms). Planner with a knowledge pack + few-shot + learned labels; guardrails in code (on-screen ids only,
  never PIN fields, risky taps glow-only, HI/TE speech from templates). Verified: "turn on dark mode" (no script) →
  Gemma picks "Display, brightness & eye protection" then "Dark mode". Explain/"where am I". Siri-style aura rebuilt as a
  blurred edge mask + rotating sweep (smooth); card placement hysteresis (no top/bottom jumping).
- 13:08 **P0-5 Voice + Home + Settings**: Ask sheet (edge aura, voice-reactive orb, live words, EN/हिं/తె chips, typing,
  vertical suggestions); speech fallback chain on-device → default offline (en-IN, en-US) → default (the iQOO had no
  en-IN offline pack: error 12). Home: serif greeting, living orb, helper status, Today (task/reminders/people/learned),
  24 skills as big rows by group, privacy footer. Settings: name, language, family contact behind the screen lock,
  speech rate, text size, dim/scam/teach toggles, Test brain + Test NPU, memory + Forget everything.
  Bubble hides on the lock screen. Offline speech packs: download from Settings (user's call).
- 13:12 **P2-a**: "I'm lost" / "where am I" → screen explained + card with Take me home · Go back · Ask family.
  **Family help (secured)**: redacted message (no 3+ digit numbers/amounts/emails/links; money apps → generic text) prefilled
  to the registered family contact on WhatsApp (SMS fallback); Saathi never sends; unit-tested. Voice: "ask my son for help",
  "बेटे से पूछो". **Caregiver first-run setup**: welcome + language, name + voice speed preview, family contact (locked
  later behind the screen lock), helper + mic checklist.
- 13:18 **Read this + magnifier + medicine strip** (camera): CameraX preview, torch, magnifier zoom, offline ML Kit OCR
  (Latin + Devanagari), **FastVLM-0.5B on the Hexagon NPU explains the photo (1.8 s on the iQOO)**, Gemma/text fallback,
  scam wording check on paper. Medicine mode: biggest printed line = name → "Is it X?" → Morning/Afternoon/Night → the guide
  sets a daily alarm + Today reminder. APK permissions re-checked: still no INTERNET.
- 13:20 **Phone School** (4 weeks · 14 lessons, progress from Memory: New → Practising → "You can do this!", next lesson on
  Home › Today). **TV remote over the iQOO's IR blaster** (Samsung + LG codes, six huge buttons; voice: "TV volume up",
  "टीवी बंद करो"). **On-call scam alarm** (banking/UPI/remote-access app opened during a call → stop card + speech).
  Tests: 4 suites green (routing EN/HI/TE, slots, family-help redaction, IR patterns).
