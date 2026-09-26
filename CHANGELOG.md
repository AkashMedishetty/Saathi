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
