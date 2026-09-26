# Saathi: analysis after eval 1, and the plan for demo 2 (Sun 27 Sep, 9:30)

Written Sat 26 Sep, 21:00. Evidence: the phone's field log, logcat, the Settings experiments and the eval runs.

## 1. What is actually wrong (root causes, not symptoms)

### A. The overlay design is the source of most "it broke" moments
- The glow is **one invisible window covering the whole screen** (`Overlay.ensureGlow`, `WLP.MATCH_PARENT` both ways),
  and by default it **dims the whole screen** (`Prefs.dim = true`, `GlowView.dim`).
- The window is not touchable, so taps pass through it. But Android then marks every tap as *obscured by another
  app's window*:
  - Some apps ignore obscured taps. That's why "tapping the glowing search does nothing and Saathi asks again".
  - vivo treats an accessibility app covering a tap into Settings as a hijack and **switches Saathi off**.
    - Tapping the Settings icon while a task is running: 7/8 switch-offs.
    - Opening Settings with no task running: 0/20.
- The full-screen dimming is also what looks tacky, and it stays dark while the person types (the YouTube darkout).
- **Fix:** no full-screen window, ever.
  - The pointer is a halo made of small windows placed *around* the target, never over it, so taps stay untouched.
  - No dimming.
  - All Saathi windows are removed the instant the person touches the screen near the target, and come back when the
    screen settles.
  - One redesign fixes four complaints: taps ignored, Settings switch-off, darkout, tacky look.

### B. Navigation is generic guessing instead of knowing the apps
- Unknown screens go to an on-device LLM planner (`Planner.decideInTask`). Small models wander: Minecraft looped in
  vivo's Videos app, Maps directions scrolled around, YouTube Subscriptions typed a sentence into search.
- The accessibility tree already gives every button's resource-id and label, instantly. The popular apps' screens are
  known and stable.
- **Fix:** *App maps*. For the top ~12 apps, a data file per app:
  - its screens (recognised by resource-ids and labels),
  - the routes to common goals (subscriptions, search, upload, crop, video call, ringtone…),
  - the words to say at each step (EN/HI/TE).

  The Guide follows the map deterministically and instantly. The LLM only picks the route and fills in the slots (who,
  what). The planner stays as the last resort for apps without a map.

### C. Conversation dead-ends
- At the end of a task Saathi says "Done!" and stops listening. There's no "did it work?", no next step, and no way to
  continue ("now send it to my son").
- **Fix:** every end state becomes a turn: a short result, one natural follow-up offer, and the mic open for about
  8 s. Tasks chain within the same task memory.

### D. Wrong jobs given to small models
- **TV coach:** FastVLM-0.5B can't reliably say which tile is highlighted on a real TV photo. The field logs show
  black frames and invented descriptions. This isn't fixable by tomorrow.
- **General questions:** the 1B model on the NPU and E2B make things up.
- **Fix:**
  - The models do what they're good at: understanding vague requests, picking an app-map route, explaining text we
    give them (OCR text, screen text), and short grounded answers.
  - Factual questions → web lookup (read back from the results) when online. Offline → a careful answer that says
    when it's unsure. Health → "ask your doctor".
  - TV: park it for the demo, or keep it to the IR "press this button" path without claims about vision.

### E. Camera and reading
- Many captures were black frames, and the model then described "a completely black image".
- Explanations were cut to 2 sentences.
- You can't ask follow-up questions about the paper.
- ML Kit has **no Telugu text recognition** (Latin and Devanagari only), so Telugu printed text can't be read.
- **Fix:**
  - Detect dark or blank frames and ask to retake.
  - A structured explanation (what it is / what matters / what to do), from the text brain using the OCR text.
  - Keep the paper's text in RAM for follow-up questions while the reader is open; clear it on close.
  - Be honest about Telugu print.

### F. The demo phone isn't set up
- WhatsApp isn't registered and Instagram isn't logged in, so every flow stops at the sign-in screen.
- **The owner must set these up** (only a person can: phone number, OTP, password).

### G. Portability and low-end phones
- **Tied to this chip:** only the NPU model files, which are compiled for SM8850.
- **Not tied to this phone:** the app logic (accessibility tree, deep links, app maps, rules). But it has only been
  tested here.
- **Fix:** a capability tier, detected at start-up from `Build.SOC_MODEL`, RAM and which model files are present:
  - **Tier 1** (flagship Snapdragon with a matching NPU build): NPU 1B for understanding + Gemma 4 on the GPU.
  - **Tier 2** (≥8 GB RAM, any GPU): Gemma 4 E2B or Gemma 3 1B on the GPU.
  - **Tier 3** (older phones): **no LLM**. Keyword skills, app maps, deep links, scam rules, reminders and ML Kit OCR
    all run without one.
  - Measure Tier 3 accuracy with the eval set (models off) and quote the number.
- **Fine-tuning:** not by tomorrow. Recompiling for the NPU needs Qualcomm AI Hub plus hours. App maps give more
  reliability than a fine-tune would.

## 2. What we will demo (refine, no new features)
1. **Learn any app, perfectly** (app maps):
   - YouTube subscriptions or search, WhatsApp video call or message, a photo edit in Google Photos (crop, brightness,
     save), the ringtone.
   - Teach mode: show once, then "now you try". Saathi only helps if they're stuck (hint after 6 s, glow after 10 s).
2. **Teach once:** family records a task and Saathi replays it as guidance.
3. **Scam shield:**
   - scam SMS/WhatsApp text
   - **APK file sent on WhatsApp** (warn before it's opened, block the installer)
   - screen-sharing app requests
   - scam on paper
   - stepping back in banking apps
4. **Camera:** read and explain a paper, with follow-up questions. **Form helper:** a paper form (what to write in
   each box, from the saved profile) and an online form (glow each field; "Do it" fills name / address / DOB, never
   OTP / PIN / card).
5. **Grounded answers**, and a conversation that never dead-ends.

## 3. Who does what (one phone → only Claude drives it; others work from tree fixtures + JVM tests)

| Track | Owner | Branch | Due |
|---|---|---|---|
| Overlay redesign (no full-screen window, halo, no dim, touch-safe) + Settings verification | Claude | main | 23:30 |
| End-state conversation, video-call choice, YouTube search fix, reader fixes, grounded answers | Claude | main | 01:30 |
| Tree fixtures of the top apps' screens for the other agents | Claude | main `fixtures/trees` | 22:00 |
| App maps engine + maps for 12 apps | Kiro (Opus 5) | `kiro/app-maps` | 03:00 |
| Form helper (paper + online) | Kiro (after app maps, or in parallel) | `kiro/form-helper` | 04:00 |
| Scam shield v2 (APK / installer / remote-access / UPI-collect) | Codex (GPT; limited, one focused task) | `codex/scam-shield` | 00:30 |
| Integration, on-phone tests, regression + eval + test sheet | Claude | main | 03:00–07:00 |
| Owner: WhatsApp + Instagram on the phone; morning run of the test sheet | Akash | – | 08:00 |

Handouts: `docs/handoff/`.
