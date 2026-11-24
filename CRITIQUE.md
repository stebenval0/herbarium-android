# Herbarium for Android: a critique of its design and code

Written 2026-11-23. Herbarium promises four things:
1. One calm look.
2. It never feels like Android.
3. Anyone can use it, including someone looked after by a keeper.
4. It cuts the phone's noise down: blocking, a notification summary, and an allow-list for calls.

Each finding names where it lives and what to do. Findings marked **[done]** are fixed in this pass.

## 1. Philosophy: where the idea works against itself

**1.1 The metaphor is in charge, not the person.**
The garden language is consistent and charming. It also costs every reader a translation step, and that cost is highest for exactly the people Herbarium is for:
- "Nuntius"
- "Seek"
- "Voyages", "Hearth", "Aux"
- "quieter things"
- "Open the garden", "Back to the garden"
- "the garden's walls"

Rule: decoration may set the mood, but every word that names an action or a place must be the plain word. "Find an app" works where "Seek an app" doesn't, and "Other apps" where "Aux" doesn't. Keep the poetry in what's only looked at, such as plates, season names, the moon and the greeting.
- [done] Setup and "Not now" wording.
- Home and chapter names are left to the main session (see §5).

**1.2 Pictures that don't mean anything.**
The flower plates are beautiful but carry no information. A marigold doesn't say "Phone". People find things by recognising them, not by remembering where they are, so a chapter has to be recognisable from its picture. Suggestion: each arch shows the faces of its three most-used apps, or a small engraved glyph for its kind (receiver, coin, book), with the flower as the frame (§5).

**1.3 Ornamental type in functional places.**
Spaced small capitals ("READ IT TO ME") are slower to read than ordinary words: every letter is the same height, so words lose their shape. Low vision makes that worse. The headline summary is in italic, which is also harder to read, and it's the most important sentence on the screen.
- [done] Buttons now use ordinary sentence case at every size.
- [done] Headings keep small capitals, but with less letter spacing.
- The summary headline should be upright (§5).

**1.4 "Accessibility" is really "Settings", and it isn't guarded.**
The page holds everything: calls, Clausus, updates, Caretaken mode. Someone being looked after can wander in and switch off call screening or Caretaken mode itself. **[in progress in the main session: a salted, hashed helper PIN that opens settings for 10 minutes]** In Caretaken mode the page should ask before opening, for example "Hold for three seconds" or a question only the keeper knows. It should also be renamed Settings, with Accessibility as one section inside it (§5).

**1.5 Hidden gestures.**
The shade opens with a pull from a 26 dp strip at the very top edge. That's the kind of precise gesture Steady taps exist to avoid. Tapping the time already opens it, and that should be the main way in. The edge pull can stay for people who expect it.

**1.6 One identical look for everyone, except where it can't be.**
"Always look like mine" was the right instinct for dignity: nobody gets a "special" phone. Caretaken mode's call-first Home breaks it, but that's justified because the job is different. The look still stays identical: same paper, type and buttons. Keep that line. Caretaken changes what's on the screen, never how it looks.

## 2. Understanding notifications

**2.1 "Reminders" counts things that aren't reminders.**
Every high-priority non-message notification (bank alerts, delivery updates, security codes) is counted as a "reminder". So the home screen says "seven reminders" when there may be none. Now there are separate counts:
- reminders, which are reminders, events and alarms,
- "alerts", for anything else marked important.

**[done]**

**2.2 Opening a note loses its target after a restart.**
`Notes.OPEN` keeps PendingIntents in memory only. After Herbarium restarts, "Open" just launches the app. That's acceptable, but the summary should say so rather than silently landing somewhere unexpected. Left as it is.

**2.3 Two days, 200 notes, re-parsed on every notification.**
Every posted notification re-read and re-wrote the whole JSON list in preferences, then broadcast to Home, which rebuilt the summary on the main thread.
- [done] The list now stays in memory.
- [done] Disk writes are debounced to a background thread.
- [done] One notification storm, such as a group chat, now causes one write, not fifty.

## 3. Steadiness and touch

**3.1 The repeat guard eats real second taps on "Smaller/Larger" and "Undo".**
Steady ignores a second tap on the same control within 0.7 s. That's right for "Open" and wrong for stepping controls, which people tap several times on purpose.
- [done] `Steady.tap` accepts a shorter guard for stepping controls.
- [done] The new `Steady.repeatable(view)` marks them (250 ms).
- The callers in AccessActivity should use it (§5).

**3.2 The pressed state can vanish mid-press.**
A finger that drifts past the slop loses the highlight and the tap with no feedback, and the person doesn't know why nothing happened.
[done] Drifting now dims the highlight while the finger is beyond the slop. Coming back within it restores the highlight and still counts. Before, a drift cancelled for good.

## 4. Implementation quality

- **4.1** Preferences are a database by accident: notes, learned words, lists and passes all live in one SharedPreferences XML file, rewritten whole on each `apply()`. Notes are now separate. Learned words should follow (Keys, §5).
- **4.2** `Theme.get()` is built many times per screen; it's cheap now that the prefs handle is cached.
- **4.3** No tests at all. The pure logic is the riskiest and the easiest to test without a device: `Summary.of` classification, `Calls.mayRing`, `State.remaining`, `Missed.waiting`'s rules. A tiny plain-Java test runner in `build.sh` would catch regressions in the code that decides whether a call rings.

## 5. For the main session's files (concrete fixes)

1. **HomeActivity:**
   - The summary headline should be `Theme.regular`, not italic.
   - "Seek an app…" should read "Find an app…", and "Seek…" should read "Find…".
2. **Apps.NAMES:** use plain chapter names: "Phone", "Every day", "Money", "Travel", "Reading & TV", "Work & study", "Camera", "Home", "Shopping", "Health", "Other". Keep the old keys for saved layouts by mapping old to new on load.
3. **AccessActivity:**
   - Mark the Smaller/Larger text-size buttons `Steady.repeatable(...)`.
   - In Caretaken mode, guard the page with a 3-second hold on the Home button that opens it.
4. **Keys:**
   - Keep learned words in their own prefs file (`getSharedPreferences("words")`).
   - Give the drawn Board an accessibility node provider so TalkBack and Switch Access can reach each key.
5. **LockActivity / HomeActivity:** create TextToSpeech in onCreate when `Summary.canSpeak`, so the first "Read it to me" doesn't wait a second.
