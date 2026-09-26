# Kiro: demo fixes (branch `kiro/demo-fixes`, off main 67d8fd6), Sun 27 Sep 01:35

Two commits. Compiles; guide + maps unit tests green (plus a new `MessageTextTest`). **Not run on the phone.**
Merge, install, then check the list below.

## What changed
- **Never dead-ends (Guide.handleUtterance + Overlay):**
  - A spoken "yes / haan / అవును", "no / नहीं / వద్దు", or a choice label ("WhatsApp", "Let me try")
    answers the card that is asking (`Overlay.acceptPending / declinePending / pickChoice`).
  - ASK / CHOICE cards now keep a mic button ("Answer by voice").
- **Mid-task doubt keeps the task:** a question (phrased as one, naming no other app/skill/route) goes to
  `answerQuestion(q, aside=…)`. The answer has the goal + current instruction as context; the existing
  set-aside → "Shall we continue?" path resumes it. Before, it was `start()`, which wiped the task.
- **Routing (`Guide.start`), general rules, no new app scripts:**
  - `watch me …` / `done teaching` is checked first (`teachOnce`).
  - "screenshot … send/share/whatsapp": after the screenshot, `start("send this photo to …")`.
  - `hereTask`: a request about THIS screen ("book a cab to this location" in a chat, "send this photo") in a
    third-party app → an open-ended planner flow from where they are (no launch, no map). It runs before the
    maps. A DIRECT instant skill still wins. Message words are ignored ("…saying I reached here").
  - `ownThingsTask` + `handleIntent` watch/music: "my liked videos / my playlist / मेरी प्लेलिस्ट" is never a
    search query → planner inside the app.
- **Form help:** when Saathi's own mic sheet is still on top, it waits 700 ms and reads the app window
  underneath (`appRoot`). Before, voice requests saw 0 boxes → camera.
- **Card:** dragging moves the card's window (it was clipped inside it). Telugu/Devanagari text gets font
  padding + 1.22 line height (tops and bottoms were cut off).
- **Slots:** Hindi (`… मैसेज भेजो कि …`) and Telugu (`… అని మెసేజ్ పంపు`) message text is extracted, so HI/TE
  messages get typed.

## Check on the phone
1. "Watch my serial on Hotstar" (not installed) → say "yes" → Play Store opens.
2. "Video call my son" → say "WhatsApp".
3. Mid-task in YouTube search: "what is this button?" → answer + "Shall we continue?" → "yes" → the glow is back.
4. "Watch me: search a movie on JioHotstar" → recording card; do it; "done teaching" → "Learned … in N steps".
5. "Take a screenshot and send it to Akash on WhatsApp" → screenshot, then the WhatsApp photo route.
6. In a WhatsApp chat with a shared location: "book an Uber to this location" → the planner starts in the chat.
   **The model has to do this one; nothing is scripted.** Note where it goes wrong.
7. "Show my liked videos on YouTube" → YouTube opens, the planner (not search).
8. Chrome, httpbin.org/forms/post, voice "help me fill this form" → boxes, not the camera.
9. Drag the card to the top → it moves and stays. Long Telugu card → nothing cut off.
10. Telugu: "అబ్బాయికి నేను ఇంటికి చేరుకున్నాను అని మెసేజ్ పంపు" → the text is typed, Send glows.
