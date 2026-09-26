# Saathi — a patient grandchild in every phone

An on-device Android companion that **teaches** elderly people to use their own phone. Ask it by voice in
English, हिंदी or తెలుగు ("video call my son", "अक्षर बड़े करो", "పాట పెట్టు") and it makes the right button
**glow in any app**, says what to tap, and taps only if you press **Do it for me**. It fades its help as you learn,
stops scams inside the guidance, reads papers and medicine strips, and brings in family help that can't be hijacked.

**Private by design: the APK has no INTERNET permission.** The brain runs on the iQOO 15's Snapdragon:
Gemma 4 E2B on the Adreno GPU (text), FastVLM-0.5B on the Hexagon NPU (camera photos).

Built during the iQOO Hackathon 2026 (Hyderabad, 26–27 Sep). See `CHANGELOG.md` for the timeline.

## What it does

| | |
|---|---|
| **Glow guidance in any app** | Reads the accessibility tree (labels, not pixels), picks the next step, draws a marigold spotlight + a compact card that moves out of the way; waits while you touch/scroll; pauses when you switch apps |
| **Voice, EN / HI / TE** | On-device speech with fallbacks; spoken replies (Hindi/Telugu from templates, never the model) |
| **24+ skills** | WhatsApp video call / message / photo, calls, YouTube, OTT, alarm & medicine reminder, maps, camera, torch, volume, Wi-Fi, Bluetooth, internet, battery, brightness, storage, backup, font size, TV remote, … + "open any app" |
| **On-device LLM** | Unscripted goals ("turn on dark mode") → Gemma 4 picks what to tap; guardrails in code |
| **Read this + magnifier** | Camera + offline OCR (Latin + Devanagari) + FastVLM on the NPU explains bills and letters |
| **Medicine strip → reminder** | Scans the strip, confirms the name, sets a daily alarm, shows it on Home |
| **Scam guard** | On screen, in SMS/WhatsApp notifications (RAM only), and an **on-call alarm** when a banking/remote-access app opens during a call |
| **Family help (secured)** | Redacted message prefilled only to the screen-lock-protected family contact; Saathi never sends |
| **Phone School + fading help** | 14 lessons in 4 weeks; after 3 successes Saathi lets you try first |
| **I'm lost** | "Where am I?" → the screen explained, with Home / Back / Ask family |

## Architecture

```
 voice / tap / bubble / a11y button
          │
   AskActivity (mic in a foreground Activity) ──► Guide.handleUtterance
                                                     │
            IntentRouter → Skills (scripted Flow) · open-any-app · LLM-only
                                                     │
 SaathiService (AccessibilityService) ── events ──► Guide.tick (throttle 200 ms, settle 450 ms)
   │                                                 │
   │   ScreenReader → Screen{elements} → ScamGuard → task app? (pause/resume) → done? → latest visible step
   │                                  → scroll hint → Planner (Gemma 4 GPU, guardrails) → heuristic
   ▼
 Overlay: GlowView (spotlight + aura) · compact draggable card · orb bubble · touch watcher     Speaker (TTS)
 Memory (JSON, app-private) · Prefs · LlmManager (lazy, crash-guarded, idle unload) · VisionBrain (FastVLM NPU)
```

Guardrails that no model output can bypass: only on-screen ids; never PIN/password fields; risky targets
(pay/install/share screen/OTP) glow but are never tapped; banking/UPI screens never go to the model; 4+ digit
numbers and emails are masked in prompts; taps happen only on "Do it", after a fresh re-find of the target.

## Build & run

```bash
source ~/dev/android-env.sh
./scripts/install.sh            # build, install, enable the service (keeps other a11y services), open
./scripts/push-model.sh         # Gemma 4 E2B (GPU), FastVLM sm8850 (NPU), Qwen 0.5B (CPU fallback)
./gradlew testDebugUnitTest     # routing (EN/HI/TE), slots, redaction, scam rules, IR patterns
./scripts/say.sh "video call my son" [HI|TE]   # debug: give a goal without touching the phone
./scripts/say.sh --doit | --stop | --dump | --ask [listen]
```

NPU note: LiteRT-LM **0.16.1** + the Qualcomm V81 dispatch + `qnn-runtime:2.47.0`. (0.17.x crashes calling a dispatch
`get_hooks` entry that no released dispatch library has.)

## Third-party components (thank you)

- **Tiro Devanagari Hindi, Tiro Telugu, Hind, Hind Guntur** — SIL Open Font License 1.1
- **Material Symbols** (icons) — Apache License 2.0, Google
- **LiteRT-LM** (`com.google.ai.edge.litertlm`) and **LiteRT** NPU dispatch library — Apache License 2.0, Google
- **MediaPipe LLM Inference** (`com.google.mediapipe:tasks-genai`) — Apache License 2.0, Google
- **Qualcomm AI Engine Direct / QNN runtime** (`com.qualcomm.qti:qnn-runtime`) — Qualcomm license terms
- **ML Kit Text Recognition** (Latin + Devanagari) — ML Kit Terms of Service, Google
- **CameraX, AndroidX, Material Components, Kotlin coroutines** — Apache License 2.0
- Models: **Gemma 4 E2B** (Gemma Terms of Use), **FastVLM-0.5B** (Apple, model license; LiteRT build by litert-community),
  **Qwen2.5-0.5B** (Apache 2.0) — downloaded separately, not in this repo.
