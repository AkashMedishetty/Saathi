# Scenario coverage

These are executable assertions, not recorded phone successes. Preconditions are in each scenario.

| ID | Scenario | Scope / precondition |
| --- | --- | --- |
| 01 | YouTube search and playback (EN) | A7/B10/B11. Checks the suggestion (go) path; no arbitrary first-video coordinates. |
| 02 | YouTube subscriptions (EN) | A8/B1. Requires an authenticated YouTube profile. |
| 03 | YouTube search and playback (HI) | A7/B10/B11. Checks the suggestion (go) path; no arbitrary first-video coordinates. |
| 04 | YouTube subscriptions (HI) | A8/B1. Requires an authenticated YouTube profile. |
| 05 | YouTube search and playback (TE) | A7/B10/B11. Checks the suggestion (go) path; no arbitrary first-video coordinates. |
| 06 | YouTube subscriptions (TE) | A8/B1. Requires an authenticated YouTube profile. |
| 07 | Video choice then phone path (EN) | A1/B14. WhatsApp choice must be available; operator supplies current --phone-choice X,Y. Stops before calling. |
| 08 | Video choice then phone path (HI) | A1/B14. WhatsApp choice must be available; operator supplies current --phone-choice X,Y. Stops before calling. |
| 09 | Video choice then phone path (TE) | A1/B14. WhatsApp choice must be available; operator supplies current --phone-choice X,Y. Stops before calling. |
| 10 | Call son guidance without placing call (EN) | A4. Save a family contact for the terminal Call glow; contact-search guidance also counts. |
| 11 | Call son guidance without placing call (HI) | A4. Save a family contact for the terminal Call glow; contact-search guidance also counts. |
| 12 | Call son guidance without placing call (TE) | A4. Save a family contact for the terminal Call glow; contact-search guidance also counts. |
| 13 | One-minute reminder fires | A17. Creates a real one-time reminder; let it fire before reset. |
| 14 | Reminder fires during YouTube task | A20. Task intentionally remains at the first glow while reminder fires. |
| 15 | SMS KYC threat alert | C8: debug broadcast, no real message is sent. |
| 16 | APK attachment STOP alert | Demo pivot: debug APK warning only, never open/install a file. |
| 17 | Missing Hotstar points to Install | B4. Precondition: Hotstar is absent. No installation is performed. |
| 18 | Learn Spotify entry (EN) | Demo pivot. Checks entry to teaching, not completion of all Spotify operations. |
| 19 | Learn Spotify entry (HI) | Demo pivot. Checks entry to teaching, not completion of all Spotify operations. |
| 20 | Learn Spotify entry (TE) | Demo pivot. Checks entry to teaching, not completion of all Spotify operations. |
| 21 | Learn Google Docs entry | Demo pivot. No document or account is modified. |
| 22 | Settings ringtone search (EN) | A16/Settings trap. Opens Settings via person-style intent; stops before choosing a ringtone. |
| 23 | Settings ringtone search (HI) | A16/Settings trap. Opens Settings via person-style intent; stops before choosing a ringtone. |
| 24 | Settings ringtone search (TE) | A16/Settings trap. Opens Settings via person-style intent; stops before choosing a ringtone. |
| 25 | Reader opens (EN) | C1 entry only. Camera capture/OCR quality requires a real paper and manual visual review. |
| 26 | Reader opens (HI) | C1 entry only. Camera capture/OCR quality requires a real paper and manual visual review. |
| 27 | Reader opens (TE) | C1 entry only. Camera capture/OCR quality requires a real paper and manual visual review. |
| 28 | Greeting response (EN) | Current greeting branch logs finish; general response branches log respond. |
| 29 | Greeting response (HI) | Current greeting branch logs finish; general response branches log respond. |
| 30 | Greeting response (TE) | Current greeting branch logs finish; general response branches log respond. |
| 31 | General question routes to source (EN) | B17. Browser connectivity required; checks routing/output event, not factual correctness. |
| 32 | General question routes to source (HI) | B17. Browser connectivity required; checks routing/output event, not factual correctness. |
| 33 | General question routes to source (TE) | B17. Browser connectivity required; checks routing/output event, not factual correctness. |
| 34 | Instagram login wall | Owner has not logged in: wall is an expected safe outcome. |
| 35 | WhatsApp message guidance or login wall | A2. Never tap Send or enter account credentials. |
| 36 | Learn-section photo lesson entry | School Week 2 camera phrase; does not take a photo. |
| 37 | Learn-section bigger letters entry | School Week 4 font phrase; does not tap the Settings launcher icon. |
| 38 | Ten requests without resetting apps | D1. Exactly ten requests; no internal reset/force-stop. No terminal communication tap. |
| 39 | HOME and BACK during guidance | D2. Pause log required on HOME; BACK asserts health only, overlay placement needs visual review. |
| 40 | Learn-section reading lesson entry | School Week 3 read_this phrase; only reader entry is asserted. |

## Demo-fix regressions

| ID | Scenario | Assertion |
| --- | --- | --- |
| 41 | Hotstar spoken yes | Missing-app card → ordinary `say EN yes` → Play Store focus and Install glow. |
| 42 | Spoken WhatsApp choice | Choice event → ordinary WhatsApp reply → step `map_wa_video_call_3`, with bounds top < 500; never tap. |
| 43 | Mid-task aside | `[aside] mid-task question`, then spoken yes and resumed search glow. |
| 44 | Teach-once voice commands | `[teach] recording` then `[teach] saved`; dispatch only, not proof of a nonempty saved recipe. |
| 45 | Liked videos | `[route] own things`; rejects a new YouTube search route. |
| 46 | Uber this location | Current WhatsApp focus then `[route] about this screen`; no booking. |
| 47 | Telugu message | The specified Telugu sentence → body step 3 → Do it → Send step 4; no send tap. |
| 48 | Screenshot/share handoff | Follow-up photo-sharing goal and next guide target; no Send tap. |
| 49 | Form under voice sheet | Chrome form, nonzero online box count and retained Chrome focus. |

| 50 | Hotstar installed | Search tab → search bar/fill → hero Watch button → setup login wall; no planner afterwards. Requires signed-out Home. |
| 51 | YouTube playlist | Search through result step 5; a playlist page must glow Play all at step 4, or a direct video must finish with playing. |
| 52 | Media controls | Start a video, then pause/louder/close media logs and launcher focus. Dispatch and app-focus checks, not audio measurement. |
| 53 | Step explanation | The magnifying-glass question receives the map's own answer; yes restores step 0. |

Demo-fix step 9 (dragging and text clipping) remains a manual visual check. It cannot be established by log matching.

## Quick set (separate directory)

14 plans, 13 runnable highlights when one Hotstar alternative skips. YouTube accepts input/suggestion/submit
after Search. Hotstar can dismiss one nag popup before the home step. Video choice continues through spoken
WhatsApp to the top-bar call glow. Hindi Settings continues through the font result to the slider settle event.
The original SMS/APK, Spotify and reminder cases remain. New cases 09–13 cover Aadhaar finding/sharing,
“I'm lost”, literal Notes dictation, and at most one Tatkal coach start over 20 seconds.

Needs a saved/indexable Aadhaar photo, Photos, registered WhatsApp and configured son contact, Notes reopening
an editable note, and the app/account states for the existing demos. Sharing stops at pick/Send; no document
is sent. The coach case permits zero starts: it detects repeat loops, not successful ticket booking.
See README for the 330/355-second budgets and conditional/count syntax.
