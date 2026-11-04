# Voice Prompter

A voice-guided teleprompter for Android. It reads your script aloud, listens
while you repeat it, and advances on its own when you've said the line.

Built for sermon practice, talks, teaching and recorded video — anywhere you
want your eyes on the room instead of on a scrolling wall of text.

Native Kotlin + Jetpack Compose. Fully offline once the speech model is
downloaded.

---

## Before you build: three things to know

**1. The Gradle wrapper is included** (Gradle 8.14.3), but `gradlew` needs its
executable bit preserved through git or CI fails at `chmod +x ./gradlew`:

```bash
git add gradlew gradlew.bat gradle/wrapper/
git update-index --chmod=+x gradlew
```

Verify with `git ls-files -s gradlew` — the mode should read `100755`, not
`100644`. The `.gitignore` has an explicit un-ignore for
`gradle/wrapper/gradle-wrapper.jar`, since some global gitignore templates
exclude `*.jar` and would silently drop it.

**2. The dependency versions in `gradle/libs.versions.toml` are unverified.**
They were written in November 2026 but not checked against current releases.
Bump them before or after the first build — Android Studio will flag stale
ones. The one version that *was* checked is `vosk-android 0.3.75`, the latest
published to Maven Central at time of writing.

**3. The Kotlin compiles and the unit tests pass** as of the first green CI
run. Matcher scoring was additionally verified numerically against the cases
in `PhraseMatcherTest`. What has *not* been verified is runtime behaviour on a
real device — nothing below the compile line has been exercised against an
actual microphone, TTS engine or Vosk model.

### A note on document import

`poi-ooxml` was removed. It drags in log4j-api and xmlbeans, which reference
OSGi and bnd annotation classes that do not exist on Android; R8 then refuses
to shrink the release build against unresolved references. It also costs
several MB of APK.

When the importer is written, `.docx` is a zip containing `word/document.xml`
and can be parsed with the built-in `XmlPullParser` plus `java.util.zip` —
much smaller and no R8 trouble. `pdfbox-android` can come back for PDF text
extraction; it is Android-specific and behaves. Both are still declared in
`gradle/libs.versions.toml`, just not applied, and the `-dontwarn` rules they
need are already in `proguard-rules.pro`.

One trap worth remembering: R8's "Missing class X" is silenced by a
`-dontwarn` naming **X**, not the class referencing it. `-dontwarn
org.apache.poi.**` does nothing about a missing `org.osgi.framework.Bundle`.

---

## What v1 does

| Feature | Status |
|---|---|
| Practice mode (app reads → you repeat → it advances) | Implemented |
| Fuzzy speech matching, strict/balanced/relaxed | Implemented + tested |
| Ad-lib detection and hold | Implemented |
| Automatic re-sync to a nearby phrase | Implemented + tested |
| Offline recognition (Vosk) | Implemented |
| Script create / edit / search / duplicate / delete (Room) | Implemented |
| Volume-key and page-key scroll | Implemented |
| TTS rate and pitch | Implemented |
| Document import (PDF/DOCX) | Deferred — deps removed, see note below |
| Live mode (you lead, TTS silent) | Not built — see below |
| EQ, Bluetooth device switching, mirror mode, folders | Not built |

## The design decision that shapes everything

**v1 never listens while it is speaking.** The microphone is paused for the
duration of every TTS utterance and resumed afterwards.

This is not a shortcut, it is the fix for a real problem. If the app listens
through the phone speaker while it talks, the recogniser transcribes the app's
own synthesised voice, scores it as a perfect match, and the script races to
the end by itself. Acoustic echo cancellation does not rescue this —
Android's `AcousticEchoCanceler` is built for telephony echo paths, not for
subtracting a known TTS signal, and synthesised speech is *harder* to cancel
than a human voice because it's cleaner and more consistent. Device-to-device
variation in hardware AEC makes it worse.

The cost: mid-sentence ad-lib detection doesn't work in practice mode, because
the app only notices an ad-lib once it is already listening.

**For live preaching (v2)**, the right design is the inverse: TTS silent or
routed to a Bluetooth earpiece, mic listening continuously, script scrolling to
follow you. That mode has no echo problem at all, because nothing is coming out
of the speaker. `PhraseMatcher.resync()` is already written and tested for it.

## How the matcher works

`PhraseMatcher` blends two measures, because each alone fails in a way the
other catches:

- **Coverage** (longest common subsequence ÷ expected length) — "how much of
  the line did they say, in order?" Forgiving of inserted words. Too forgiving
  alone: a line buried in a long ad-lib scores 1.0.
- **Edit similarity** (token-level Levenshtein) — "how different are these two
  sequences overall?" Penalises the insertions coverage ignores. Too harsh
  alone on a skipped clause.

Blended 60/40. Verified behaviour against `"Great leaders inspire people
through vision and action."`:

| Spoken | Score | Strict | Balanced | Relaxed |
|---|---|---|---|---|
| exact repeat | 1.00 | advance | advance | advance |
| with "uh"/"um" fillers | 1.00 | advance | advance | advance |
| stutter ("great great leaders…") | 1.00 | advance | advance | advance |
| one wrong word | 0.88 | hold | advance | advance |
| two words skipped | 0.75 | hold | advance | advance |
| heavy skip (3 of 8 words) | 0.38 | hold | hold | hold |
| ad-lib | 0.00 | hold | hold | hold |
| unrelated scripture | 0.00 | hold | hold | hold |
| line buried in an ad-lib | 0.74 | hold | advance | advance |

The thresholds in `MatchMode` are hand-picked starting points, **not tuned
against recordings**. Expect to adjust them once you've run real sermons
through the app — that's the only way to find the right values for your voice,
your mic and your room.

## Speech model

The app downloads `vosk-model-small-en-us-0.15` (~40 MB) on first run and
unpacks it into app storage. After that everything runs offline.

The download URL in `ModelManager.DEFAULT_MODEL_URL` should be verified before
you rely on it — Alpha Cephei reorganises the model index periodically and
model names get retired.

To bundle it in the APK instead, unzip the model into
`app/src/main/assets/model-en-us/` and remove that path from `.gitignore`.
`ensureModel()` will find it and skip the download.

## Where the spec was changed, and why

- **Flutter → native Kotlin.** Almost every subsystem here is Android-specific:
  audio routing, Bluetooth SCO, volume-key interception, the TTS engine,
  `AudioEffect` EQ. In Flutter each needs a platform channel, and the Vosk
  Flutter plugin has splintered into several competing forks
  (`vosk_flutter`, `_2`, `_fixed`, `_service`), which usually signals the
  original is unmaintained.
- **"TFLite speech recognition models" → Vosk.** TFLite is a runtime, not a
  model. Vosk gives continuous large-vocabulary transcription with a streaming
  API, ~50 MB models, Apache 2.0.
- **Age/gender voice catalogue (Adult Male, Boy, Elderly Female…) → real voice
  list.** Android does not expose voices by age or gender. `Voice` carries a
  name, locale, quality and feature set. Offering that picker would mean
  hand-maintaining a mapping of voice names per TTS engine, which breaks every
  time the user's engine updates. For genuinely different neural voices, the
  path is **Piper voices via sherpa-onnx** — a published benchmark puts Android
  System TTS and Piper VITS as the fastest on-device options, comfortably
  faster than real time.
- **`.doc` (legacy binary) dropped.** `.docx`, `.txt`, `.md` and PDF only.
- **EQ on TTS output deferred.** It is not clear you can cleanly attach an
  `Equalizer` effect to the TTS audio session; synthesising to file and playing
  through ExoPlayer is the more reliable route. Worth confirming before you
  design UI around it.

## Known limitations

- **Numbers.** Recognisers are inconsistent about rendering numbers ("2025" vs
  "twenty twenty five"). `Normalizer` expands single digits only. Scripts with
  heavy numeric content will match less reliably.
- **Accents.** The small English model is trained mainly on US English.
  Matching may be looser than you'd like with a Filipino English accent —
  `RELAXED` mode exists partly for this. If it's a problem, the larger Vosk
  English model is more accurate at the cost of size and speed.
- **Volume keys only work in the foreground.** Android doesn't route them to a
  backgrounded app. A Bluetooth clicker sending page/media keys is the route
  for hands-free control from a distance.

## Release builds

The release APK is **unsigned** unless four repository secrets are set:
`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

```bash
base64 -w0 release.jks   # paste into the KEYSTORE_B64 secret
```

Pushing a tag beginning with `v` builds and publishes APKs to GitHub Releases.

```bash
git tag v0.1.0 && git push origin v0.1.0
```

## Licence

Vosk is Apache 2.0. Choose a licence for this project before accepting outside
contributions.
