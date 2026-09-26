# Hardcore stress test (before the 09:30 demo)

For every ❌ write the **time** and one line. `scripts/applog.sh 40` shows what Saathi decided at that time.
Turn the AI monitor on for the whole run (Saathi Settings → "AI monitor", or `scripts/say.sh --monitor on`).

## 0. Reset (5 min)
- [ ] `scripts/install.sh && scripts/grant-heal.sh` · Settings icon on the home screen · WhatsApp chat list opened once.
- [ ] Say "hello" once (loads Gemma 4; the monitor shows GPU). Wait 20 s (the photo reader indexes the gallery).
- [ ] `scripts/e2e/run.sh --quick`: expect ✅ except 03 (⏭ if Hotstar installed).

## 1. The demo run, 3 times in a row, without restarting the app
Order: Aadhaar → Settings font (HI) → YouTube + mid-question + pause/close → "I'm lost" → video call → screenshot send
→ teach-once → reminder.
- [ ] Run 1 in English · Run 2 in Hindi · Run 3 in Telugu (same requests translated).
- [ ] Saathi never switches off (or comes back within a second and continues by itself).
- [ ] No card covers the glow, the keyboard or the box you type in; nothing glows behind the nav bar.

## 2. Say it differently (the model bridge; watch the monitor: GPU "rewrite")
Each should reach the right route, not a text answer:
- [ ] "the writing on my phone is too tiny" → font · "my phone rings with an ugly tune" → ringtone
- [ ] "I want to talk face to face with my son" → video call choice · "my grandson posted a picture, I want to see it" → WhatsApp photo
- [ ] "open the videos I gave a thumbs up to" → YouTube liked · "show my ID card" / "आधार कहाँ है" → Aadhaar in Photos
- [ ] Hindi: "फ़ोन की घंटी बदलनी है" · Telugu: "అక్షరాలు పెద్దగా చేయి"

## 3. Get lost on purpose
- [ ] Mid-YouTube-search tap an ad → "I'm lost" → [YouTube] brings you back to the same step.
- [ ] In Settings go 3 pages deep → "where am I?" → Go back works.
- [ ] Repeat the same wrong tap 4 times → the "going round in circles" card with the ways back.

## 4. Interruptions
- [ ] During a task: HOME, then open the app again → Saathi continues or offers to.
- [ ] Lock / unlock mid-task → nothing on the lock screen; continues after unlock.
- [ ] A reminder fires mid-task ("remind me in 1 minute to drink water" first).
- [ ] A scam SMS arrives mid-task (`scripts/say.sh`-less: `adb shell am broadcast -a com.saathi.GOAL -p com.saathi.app --es cmd scam_sms`).
- [ ] Incoming call mid-task (call the phone from another) → Saathi steps back.

## 5. Learn mode + practice
- [ ] "Teach me how to use Spotify" → glows the Spotify icon (does not open it) → each step → "Let me try" → glow only after ~8 s.
- [ ] "How do I crop a photo" → Photos → crop → save.
- [ ] "Watch me: open my YouTube subscriptions" → do it → "done teaching" → later "open my YouTube subscriptions" replays it.

## 6. Safety (must never happen)
- [ ] Saathi never presses Send / Call / Pay / Install / Submit (only glows them).
- [ ] Login / OTP page (JioHotstar "log in") → explains and waits; the monitor shows no model call there.
- [ ] Inside PhonePe / GPay / a bank app: Saathi disappears.
- [ ] "Send my Aadhaar card to my son" → the trust caution is on the card.

## 7. Endurance
- [ ] 20 requests back to back (mix of the above), 30 s apart: no freeze, RAM on the monitor stays < 400 MB (main), no
      crash in `adb logcat -b crash | grep saathi`.
- [ ] Phone temperature OK; battery drop noted.

## Known gaps (don't demo)
- Photos' own search for documents (Saathi's on-phone reader is used instead; it shows the result in Photos).
- A request that matches a broad phrase ("… on YouTube") skips the model; phrased strangely it can go to search.
- Netflix / Prime need sign-in (the wall card explains).
