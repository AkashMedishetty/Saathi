# Manual checklist (Sat night → before the 09:30 demo)

Tick ✅ / ❌. For every ❌ write **the time** and one line on what you saw. Logs are keyed by time.

## 0. Setup first
- [ ] WhatsApp registered · Instagram logged in · YouTube account signed in · Uber installed + logged in (optional)
- [ ] Saathi Settings → family contact saved (name + number) · **My details** filled (name, DOB, address, mobile)
- [ ] Settings app left on its **main page** (not on Accessibility)

## 1. The pointer and the card (look and feel)
- [ ] The glow is a calm ring **around** the button; the screen never goes dark.
- [ ] Tapping the glowing button always works (no "tap does nothing").
- [ ] The card never covers the glowing button, the keyboard or the box you're typing in.
- [ ] Dragging the card to the top or bottom: it stays there.
- [ ] Long Telugu text wraps fully and is never cut off.

## 2. YouTube (English, then हिंदी, then తెలుగు)
- [ ] "Search for old Telugu songs on YouTube": Search → type ("Do it" types) → the suggestion or search key → a real
      video (not the ad) → it plays → the ending card shows **Let me try / Something else**.
- [ ] Open a **playlist** from the results. (Known gap: sent to Kiro; note what happens.)
- [ ] "Show my YouTube subscriptions": glow on Subscriptions.
- [ ] An ad on the results page is never glowed; a video ad shows "An ad is in the way" + its close button.

## 3. Learn mode (the Learn section, or "teach me / how do I")
- [ ] "Teach me how to use Spotify": Saathi does **not** open Spotify; it glows the Spotify icon (or says swipe up),
      then guides each step.
- [ ] "How do I crop a photo": Google Photos, then crop, step by step.
- [ ] "Create a new document in Google Docs", then "save it as Word".
- [ ] At the end, **Let me try**: the same task again; Saathi prompts, and the glow appears only after ~8 s; the end
      card says "You did it yourself!".

## 4. Installing a missing app
- [ ] "Watch my serial on Hotstar" (not installed): "isn't on this phone… help you get it?" → Play Store opens
      directly (no store picker) → glow on **Install**, with no "Do it for me" → you tap Install → "installing,
      please wait" → Open → "Show me how to use it".

## 5. Calls and messages (after WhatsApp is set up)
- [ ] "Video call my son": two buttons **WhatsApp | Phone call**.
  - WhatsApp → chat → the video button glows.
  - Phone → the dialer with his number → the green button glows. Saathi never presses Call.
- [ ] "Send a WhatsApp message to my son saying I reached home": the text is typed, then Send glows. Saathi never
      presses Send.
- [ ] "Call my son": the dialer opens with his number.
- [ ] "Read my messages": they're read aloud; an OTP is never read out.

## 6. Settings (the person opens Settings)
- [ ] From the home screen, "make the letters bigger": Saathi glows the **Settings icon** (or says swipe up); you tap
      it; Saathi guides you to font size. **Saathi must not switch off.**
- [ ] With Settings already open, "how do I change my ringtone": search → "ringtone" → the result row glows (not the
      history chip) → "Here it is. Choose what you like."
- [ ] If Settings reopens on an old sub-page, Saathi asks you to tap back (it doesn't press anything itself).

## 7. Forms
- [ ] Chrome → any web form (e.g. httpbin.org/forms/post) → "help me fill this form": box by box; "Do it" fills your
      saved name/phone; OTP/password are never filled; it never presses Submit.
- [ ] Camera → **Fill a form** chip (or "help me fill this form" on the home screen) → photo of a printed form → box
      by box on the photo, with what to write in big letters; Aadhaar/signature say "write it yourself".

## 8. Camera reader
- [ ] "Read this letter for me" on a clear printed page: a 3-part explanation (what it is / what matters / what to
      do), no stars or "Okay! let me explain".
- [ ] A blurry photo or a laptop screen: "The words are blurry…" (no invented story).
- [ ] Phone face-down: "It's too dark".
- [ ] **Ask about it** → "what is the due date?": answered only from the paper, or "I can't see that on this paper".

## 9. Scam shield
- [ ] From another phone, send an SMS: "Your SBI KYC is pending, account blocked today, update bit.ly/xyz": a red
      warning card + voice.
- [ ] On WhatsApp, send a file named `update.apk` or the text "install this app": a STOP card that stays until
      closed.
- [ ] Opening an APK from WhatsApp/Chrome: the installer shows a STOP card with **Take me back to safety**.
- [ ] "Install AnyDesk" in a message: a warning.
- [ ] Inside PhonePe / Google Pay / a bank app: Saathi disappears completely.

## 10. Talking to Saathi
- [ ] "Hello": one short line + "What shall we do on your phone?" (no long chat).
- [ ] "Will it rain today" / "gold price today": the Google results open + the answer read from them.
- [ ] "How to make upma": a short answer + "Shall I find a video?".
- [ ] "How do I use Instagram" in Telugu: Instagram opens and is guided (not a text answer).
- [ ] "Remind me in 2 minutes to drink water": it fires on time, even during another task.

## 11. Robustness
- [ ] 10 requests back to back, with Home/Back in the middle: no freeze; Saathi stays on (Settings → Accessibility).
- [ ] Lock/unlock during a task: nothing drawn on the lock screen.
- [ ] Repeating the same failing step: after 3 tries, "We are repeating the same steps…" with Back / Ask family.
