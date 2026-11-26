# Herbarium for Android

A calm home screen for Android. Pressed-flower plates instead of rows of icons. A few readable lines instead of a pile of notifications. Big, forgiving buttons, and text that grows up to three times its normal size.

**[Download Herbarium](https://github.com/stebenval0/herbarium-android/releases/latest/download/Herbarium.apk)** (Android 11 or newer)

---

## Installing

1. On the phone, open the download link above.
2. Android warns that the file could be harmful, because it doesn't come from the Play Store. Tap **Download anyway**.
3. Open the downloaded file. If Android asks, allow your browser to install apps (**Settings → Allow from this source**), then go back.
4. Tap **Install**, then **Open**.
5. When the phone asks which home screen to use, choose **Herbarium** and **Always**.

Herbarium then asks who the phone is for and walks you through a short checklist, one step per page: name, text size, town (for the weather), home screen, calls, texts, keyboard and voice, notifications, protection, and, for a phone you look after, favourite people and a helper PIN. You can change all of it later in Herbarium's **Settings**.

### "Restricted setting"

Android is careful with apps that don't come from the Play Store. If switching on Herbarium (under Accessibility or notification access) says **Restricted setting**:

1. Go back and open **Settings → Apps → Herbarium**.
2. Tap **⋮** in the top corner, then **Allow restricted settings**.
3. Try the switch again.

## What it does

- **Home.** Your apps sit in a few chapters, each behind a flower plate. Each chapter has a small seal (a handset, a coin, a book), and you can also find an app by name.
- **Letters.** Notifications are gathered into one line, such as "A message from Sam, two reminders and five quieter things." Tap **Read it to me** to hear it aloud.
- **Calls.** Herbarium can be the phone's calling app: the caller's name in big letters, a big Answer, and a big Hang up. People in your contacts ring through. You can add other numbers in Herbarium's Settings. Other numbers are declined quietly. If the same number calls twice within three minutes, it rings.
- **Keyboard.** Big keys that grow with the text, word suggestions, and voice typing that works even on phones without Google.
- **Lock screen, shade and screensaver** all wear Herbarium's look.
- **Caretaken mode**, for a phone someone else looks after: Home is about calling (missed calls wait in big letters with a one-tap "Call back"), the ringer stays on, the keyboard listens first, and a helper PIN keeps the settings for helpers.
- **Text size, steadier taps, stronger contrast,** and day or night, in Herbarium's Settings.

## A keeper (optional)

Someone you trust can look after what's blocked on this phone from their computer, using Clausus. They send you an invitation link. Open it on the phone, or copy it and tap **Paste the invitation** in setup or in Herbarium's Settings. From then on, only they can switch the blocking on or off, wherever the phone is.

For the strongest lock, the keeper connects the phone to their computer once with a USB cable and runs `clausus phone pair`.

## Updates

Herbarium checks for a new version once a day and, when there is one, shows a quiet notice; the update itself is installed from Herbarium's Settings. To check straight away, tap **Check for an update** there. Each update is checked against its fingerprint before it installs.

## Building from source

The source is in this repository: plain Java, no Gradle. You need the Android SDK (build-tools 36.1.0, platform android-36) and JDK 17 or newer. Fetch the offline speech libraries listed in `libs/README` into `libs/vosk` and `libs/jna`, then run `./build.sh`; it runs the checks in `tests/` and writes `Herbarium.apk`, signed with a key of your own. `vendor/` holds Android's MMS message code (Apache-2.0, see `vendor/README`); the Literata fonts in `assets/fonts` are under the SIL Open Font License.
