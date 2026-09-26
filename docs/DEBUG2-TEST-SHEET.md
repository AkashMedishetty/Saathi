# Saathi — Debug 2 test sheet

Fill this in on paper or on the laptop while you test. For every ❌, write **the time**, **what you said**, and
**what you saw**. The phone logs everything with timestamps (from 14:00), so the time alone lets me find the problem.

**Mark each row:** ✅ worked · ⚠️ worked but slow or clumsy · ❌ wrong or stuck

## Before you start
- [ ] Phone charged above 50%. Wi-Fi on (it's needed only for the look-ups: weather, scores, where a movie streams).
- [ ] Settings → Accessibility → Saathi is **on**. (If it ever turns off by itself, write down the time: that's a bug.)
- [ ] **Unplug the USB cable** before testing IRCTC or banking apps. They refuse to work while USB debugging is connected, even without Saathi.
- [ ] Try each core task in **English, Hindi and Telugu**. Switch the language in Saathi's Settings, or just speak it.
- [ ] To give Saathi a task: open Saathi, tap the big mic, then speak (or type). You can also use the floating Saathi bubble.

---

## A. Core tasks: these must work every time

| # | Say this | What should happen | EN | HI | TE |
|---|----------|--------------------|----|----|----|
| A1 | "video call my son on WhatsApp" / "बेटे को वीडियो कॉल करो" / "కొడుకుకి వీడియో కాల్ చేయి" | WhatsApp opens on the son's chat; the glow is on the video-call button. If no son is saved: it asks who. | | | |
| A2 | "send a WhatsApp message to Priya saying I reached home" | The chat opens with the text filled in; the glow is on Send. **Saathi never presses Send itself.** | | | |
| A3 | "send a photo to my daughter on WhatsApp" | Chat → attach → gallery, one glow at a time. | | | |
| A4 | "call my son" / "बेटे को फ़ोन करो" / "కొడుకుకి ఫోన్ చేయి" | The dialer opens with the number; the glow is on Call. | | | |
| A5 | "call 9876543210" | The dialer opens with that number. | | | |
| A6 | "read my messages" / "मैसेज पढ़ो" | It reads new messages aloud, and warns if one looks like a scam. | | | |
| A7 | "play Hanuman Chalisa on YouTube" / "भजन लगाओ" / "పాట పెట్టు" | YouTube search results open, with the glow on the first result. | | | |
| A8 | "open my YouTube subscriptions" | YouTube opens; the glow is on Subscriptions. | | | |
| A9 | "make the letters bigger" / "अक्षर बड़े करो" / "అక్షరాలు పెద్దవి చేయి" | Settings → Display → font size, glowing step by step, and the card never covers the glow. | | | |
| A10 | "turn on the torch" then "turn off the torch" | The torch goes on and off instantly. | | | |
| A11 | "increase the volume" / "आवाज़ बढ़ाओ" | Volume goes up, or the slider glows. | | | |
| A12 | "turn on Wi-Fi" / "turn on Bluetooth" / "dark mode on" | The right toggle glows. | | | |
| A13 | "increase brightness" | The brightness slider glows. | | | |
| A14 | "my phone is full, clean up storage" | Storage settings open with the glow on the free-up / clean option. **It must not ask you to search for junk.** | | | |
| A15 | "how much battery is left" | It speaks the battery %. | | | |
| A16 | "change my ringtone" (a setting with no skill) | Settings search finds Ringtone and guides you there. | | | |
| A17 | "remind me in 2 minutes to switch off the gas" | Says "Okay. At HH:MM I'll remind you…". **After 2 minutes it speaks and buzzes.** | | | |
| A18 | "remind me at 8 pm to take my tablet" / "रात 8 बजे दवा की याद दिलाना" | Confirms the time; the reminder appears in Home → Today as "Once · 8:00 PM". | | | |
| A19 | "every day at 9 pm remind me to take BP tablet" | Daily reminder set; it shows in Today and in Settings → routines. | | | |
| A20 | Reminder fires **while another task is running** | It still speaks "Reminder: …" and doesn't break the task. | | | |
| A21 | Reminder fires while the phone is **locked** | It speaks and buzzes; nothing is drawn over the lock screen. | | | |
| A22 | "set an alarm for 6 am" | The Clock app opens with the alarm ready; the glow is on Save. | | | |
| A23 | "open WhatsApp" / "open camera" / "open Maps" | The app opens and Saathi says "X is open", with no extra guiding loop. | | | |
| A24 | "take a selfie" | The camera opens, the glow is on the switch-camera button, then on the shutter. | | | |
| A25 | "directions to the railway station" | Maps opens with directions. | | | |

## B. Retest every Debug-1 note

| # | Debug-1 note | How to retest | Result |
|---|--------------|---------------|--------|
| B1 | Loops / asks again after the button was clicked | Do A9 and A8. Each step should appear once, then move on. | |
| B2 | Loses context | "play a bhajan on YouTube" → mid-way ask "what time is it?" → it answers and **resumes the YouTube task**. | |
| B3 | Card covers the keyboard | A2: when the keyboard is up, the card should move to the top. | |
| B4 | Paused in the Play Store | "install Truecaller": it guides to Install, then **stops and asks** before pressing Install. | |
| B5 | Duplicate accessibility icon | There should be no extra accessibility button in the nav bar. | |
| B6 | Voice doesn't work / buggy start | Tap the mic 5 times in a row in each language. It should always listen; if it fails, the Google voice pop-up appears. | |
| B7 | Netflix goes to Help instead of sign-in | "watch Pushpa on Netflix" → search or sign-in, **never Help**. | |
| B8 | Slow to understand the screen | Time from speaking to the first glow: write it down (target under 3 s). | |
| B9 | Continue does nothing after a redirect | In any guided task, tap Continue after the app changes screens: it should pick up the new screen. | |
| B10 | Ads not handled | YouTube with an ad playing: it says "wait for Skip" / points at Skip. | |
| B11 | Opens an app but doesn't guide | A7 and A8 should show a glow **after** opening. | |
| B12 | Pushes to WhatsApp accessibility settings | No flow should take you to WhatsApp's accessibility settings. | |
| B13 | Repetitive on/off | A10 twice. Turning something off should work too. | |
| B14 | Should ask "TV or phone" | "play Guntur Kaaram": it plays on the phone. "play Guntur Kaaram **on TV**": the TV coach. | |
| B15 | Cookies question loops | Chrome site with a cookie banner: it answers once and moves on. | |
| B16 | Feels hardcoded | Try 5 tasks in your **own words** (not from this sheet), and write what happened. | |
| B17 | Assumes instead of checking | "will it rain today": it reads the real answer from the web. | |
| B18 | "TV" goes to the phone | "guntur karam movie on tv": it looks up where it streams → asks about the subscription → camera on the TV. | |
| B19 | Unclear how to reach camera help | Home: the camera button → Read / Medicine / How to use / TV chips are all reachable. | |
| B20 | Camera permission | Clear the app's camera permission, then open the reader: it asks nicely. | |
| B21 | Saathi turns off by itself | After **every** section, check Settings → Accessibility → Saathi is still on. | |

## C. Demo features (for the presentation)

| # | Do this | What should happen | Result |
|---|---------|--------------------|--------|
| C1 | "read this letter for me" → point at any printed paper → shutter | FastVLM on the NPU explains it in about 2 s, in your language. | |
| C2 | "scan my medicine strip" | Reads the medicine name → offers a daily reminder. | |
| C3 | "how do I use this washing machine" | The camera opens in "How to use" mode. | |
| C4 | TV coach with the laptop TV simulator (open `tools/tv-simulator.html` full-screen) | "play guntur karam on my tv" → LOOKUP → "Do you have Netflix?" → "yes" → camera on the laptop → it tells you which **remote button** to press (use the arrow keys / Enter on the laptop as the remote). | |
| C5 | Teach once: "watch me: open my YouTube subscriptions" → do it by hand → "done teaching" → go Home → say it again | The second time, the glow follows what you did. | |
| C6 | "remember my BP tablet is Telma 40" → later "what's my BP tablet?" | "Telma 40" | |
| C7 | "help!" | The SOS screen opens (call family / 112). | |
| C8 | A scam SMS arrives (or use `scripts/say.sh`, cmd scam_sms) | A big warning card. | |
| C9 | "do it all for me: make the letters bigger" | Saathi taps each step itself; the glow stays visible. | |
| C10 | "book a tatkal ticket from Hyderabad to Delhi" (USB unplugged) | The coach asks for the details → IRCTC guidance; Saathi disappears on payment/login screens. | |
| C11 | Open any banking / UPI app | Saathi steps back completely: no card, no glow. | |
| C12 | "good morning" | Today's briefing: reminders and routines. | |
| C13 | "where am I" / "explain this screen" | Explains the current screen simply. | |

## D. Stress and robustness

| # | Do this | Pass if | Result |
|---|---------|---------|--------|
| D1 | 10 tasks back-to-back without closing anything | No freeze, no crash, Saathi stays on. | |
| D2 | Press Home or Back in the middle of a guided task | It says "paused" / "lost", with no weird overlay left behind. | |
| D3 | Lock and unlock the phone during a task | Nothing over the lock screen; the task continues or pauses cleanly. | |
| D4 | Airplane mode → "will it rain today" | "That needs the internet" (no hang). Offline tasks (A9, A10, A17) still work. | |
| D5 | Use the phone normally for 15 min with Saathi on | Phone not hot or laggy; battery drop noted: ___% | |
| D6 | Drag the card to the top and the bottom | It stays where you put it and doesn't jump. | |
| D7 | Long text in the card (Telugu) | Wraps fully, never cut off. | |

---

### When you come back
Tell me the **row numbers that failed plus their times**. I'll pull the phone log (`scripts/pull-logs.sh`) and fix
them. Anything that surprised you, even if it "worked", is worth a line.
