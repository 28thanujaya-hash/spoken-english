# Spoken English: test version 0.1

A small test app to try the risky parts on the Galaxy A34 before building the whole course:

- A free conversation with **Sam or Maya** (the app chooses, about half each)
- **Talk → Stop** button, with moving sound bars and a timer
- His words appear in the **"So far" box at every pause**; the recording keeps going until **Stop**
- **Sinhala button, press and hold** (Sinhala while held, English again when released)
- Speech to text by **Google on the phone** (works offline when the languages are downloaded)
- Replies by **Gemini** (free key), with a **mood**
- Characters speak with **Google's voice** on the phone; Sam male, Maya female
- A simple **drawn face** that changes with the mood and moves its mouth while speaking
- Each reply shows **how many seconds** it took

Not in this test: lessons, drills, scoring, the other screens, shake-to-report.

---

## How to get the APK with GitHub (one time, about 15 minutes, easiest on a computer)

1. **Make a free GitHub account** at github.com (if you don't have one).
2. **New repository:** tap **+** › **New repository**, name it `spoken-english`, choose **Private**, tap **Create repository**.
3. **Upload the files:** on the new repository page tap **uploading an existing file**. Unzip `spoken-english.zip` on your computer, open the `spoken-english` folder, select **everything inside it**, and drag it onto the page. Tap **Commit changes**.
4. **Check the build file arrived.** Folders starting with a dot are sometimes skipped. If you don't see a `.github` folder in the repository:
   tap **Add file › Create new file**, type the name `.github/workflows/build.yml`, paste the text of `build.yml` from the zip, and tap **Commit changes**.
5. **Add your free Gemini key** (from aistudio.google.com, *Get API key*):
   **Settings › Secrets and variables › Actions › New repository secret**. Name: `GEMINI_API_KEY`, value: your key. Save.
   *(Or skip this and type the key in the app later with the ⚙ button.)*
6. **Build:** tap the **Actions** tab › **Build test APK** › **Run workflow**. Wait about 5–8 minutes for the green tick.
7. **Download:** open the finished run, scroll to **Artifacts**, download `spoken-english-test-apk`. It's a zip; inside is `app-debug.apk`.
8. **Install on the A34:** send the APK to the phone (WhatsApp to yourself, Google Drive, or a cable), tap it, and allow *Install unknown apps* when asked.

Every time the files change on GitHub, a new APK is built automatically (step 6 happens by itself). It installs over the old one.

## Before testing on the A34

- **Text-to-speech:** Settings › search *Text-to-speech* › engine **Google**; download **English (United States)** voices.
- **Offline speech:** already checked: English and Sinhala voice typing work offline.
- Allow the **microphone** when the app asks.

## What to test and report

1. Does the text appear in the "So far" box at each pause, and does it keep listening until Stop?
2. Do any words get lost between pauses? Is there a beep at each pause?
3. Hold **Sinhala** in the middle of a sentence: does the Sinhala part appear correctly (highlighted)?
4. Seconds per reply (shown under each reply). Do Sam and Maya sound different?
5. Do the replies feel natural? Do they turn his Sinhala into English naturally?
6. Try with mobile data off (Wi-Fi off too): speech to text should still work; replies will not.

Shake the phone? Not in this version. Send notes and screenshots to Claude.

## Files (for later)

- `app/src/main/java/lk/spokenenglish/app/`
  - `MainActivity.kt`: the screen and the conversation flow
  - `SpeechLoop.kt`: Google speech to text, restarting at each pause until Stop; Sinhala while held
  - `GeminiClient.kt`: the Gemini reply (default model `gemini-flash-latest`; changeable with ⚙)
  - `CharacterVoice.kt`: Google voices (male for Sam, female for Maya)
  - `FaceView.kt`: the drawn face and moods
  - `SoundBarsView.kt`: the sound bars
  - `Characters.kt`: Sam and Maya: personality, rules for replies, opening lines
- `.github/workflows/build.yml`: GitHub builds the APK
- `app/debug.keystore`: fixed test key so new versions install over old ones (test only)
