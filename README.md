# Spoken English: test version 0.3

A small test app to try the risky parts on the Galaxy A34 before building the whole course:

- A free conversation with **Sam or Maya** (the app chooses, about half each)
- **Claude-style talk bar:** waiting = **සිං** + "Tap [mic] for English, or hold [සිං] for Sinhala." + mic;
  talking = **✕** cancel · **සිං** · sound bars · **■** pause · orange **↑** send
- His words appear live in **grey italics** above the bar and add up at each pause; **never editable**
- **■ pause** keeps the words; tap the mic to go on (new words are added after); **↑** sends everything
- **සිං = press, talk, let go:** if the turn started with සිං, letting go sends it; held mid-sentence, letting go goes back to English
- Mic shows **"Getting ready…"** until it really listens, then a short **buzz**; every button ripples and ticks when touched; mic and සිං are greyed out while the partner thinks
- **Nudges:** silent 8 s after the partner speaks → the partner helps by voice; 8 s more → "Stuck? Hold සිං" and the සිං button pulses; 6 s of silence while recording → an on-screen "Take your time" line
- Replies by **Gemini 3.5 Flash Lite** (free, 500 a day); if it hits its limit the app quietly uses Gemini 3.8 Flash (20 a day); a limit error is never shown raw
- **Laughs:** the partner never says "haha"; a laughing face (and for Maya a real 1-second laugh) plays first, then the voice
- ⚙ shows **which model actually answered**, any model resting after a limit, and the last problem

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

## What to test and report (v0.3)

1. **Speed of buttons:** does every button react at once (ripple + tick)? Does the mic show "Getting ready…" then buzz when it listens?
2. **Talk bar:** ✕ throws the turn away; ■ pauses (words stay); mic continues (new words after); ↑ sends.
3. **සිං:** hold from the start, talk Sinhala, let go → it sends by itself. Hold in the middle of English → let go → English continues until ↑.
4. **Nudges:** stay silent after Sam/Maya speaks: after ~8 s they help you; ~8 s later the hint + pulsing සිං. Stop talking while recording for 6 s: "Take your time".
5. **Short answers:** answer "Yes." or "Fine." → does the partner ask something that invites a longer answer?
6. **Laughs:** say something funny: no "haha" read out; laughing face; Maya's laugh sound.
7. **Speed of replies** (seconds under each reply) and ⚙ › "Last reply came from".
8. Any words lost between pauses? Any beep at each pause?

Send notes and screenshots to Claude.

## Files (for later)

- `app/src/main/java/lk/spokenenglish/app/`
  - `MainActivity.kt`: the screen and the conversation flow
  - `SpeechLoop.kt`: Google speech to text, restarting at each pause until ■ or ↑; Sinhala while held
  - `GeminiClient.kt`: the Gemini reply (default `gemini-flash-lite-latest`, backup `gemini-flash-latest`; changeable with ⚙); removes laughs/emojis from the spoken text
  - `CharacterVoice.kt`: Google voices (male for Sam, female for Maya)
  - `FaceView.kt`: the drawn face and moods
  - `SoundBarsView.kt`: the sound bars
  - `Icons.kt`: round icon buttons (mic, ■, ↑, ✕) and the small pictures in the instruction line
- `app/src/main/res/raw/maya_laugh.ogg`: Maya's laugh (CC0, lmbubec, Freesound 119450 via Wikimedia Commons)
  - `Characters.kt`: Sam and Maya: personality, rules for replies, opening lines
- `.github/workflows/build.yml`: GitHub builds the APK
- `app/debug.keystore`: fixed test key so new versions install over old ones (test only)
