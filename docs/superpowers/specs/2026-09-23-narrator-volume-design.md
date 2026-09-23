# Narrator Volume Slider — Design

Date: 2026-09-23
Status: Approved in chat, awaiting written-spec review

## Goal

The user records Minecraft videos where every on-screen title is read aloud. They want the narration to be
much louder than the game's own sounds. Title Narrator gets its own **Narrator volume** slider in its
settings screen that controls only title narration, independent of Minecraft's volume sliders.

### Success criteria

- The mod's settings screen (Mod Menu → Title Narrator, via YACL) shows a "Narrator volume" slider, 0–100%.
- Moving the slider changes the loudness of the next spoken title, with no restart.
- Lowering Minecraft's Master (or Voice/Speech) slider does **not** make title narration quieter.
- The setting persists in `config/titlenarrator.json`.

### Decisions made with the user

- **Independent of Master.** Narration volume = slider value only. To make the narrator louder than the
  game, set the slider high and lower Master.
- **Range 0–100%, no boost.** The user records on Windows. Windows SAPI takes volume as 0–100 (text2speech
  1.19.12 multiplies the float by 100 before `ISpVoice::SetVolume`), so values above 100% can't be louder
  there. macOS is also capped at 1.0. Only Linux's flite amplifies above 1.0, so it isn't worth a separate range.
- **Default 100%.** A fresh install (or an existing config without the field) narrates at full engine volume.
- **0% passes through.** At 0% speech is still sent at volume 0 (silent); there's no special skip.

### Rejected approaches

- *Slider × vanilla Voice/Speech*: two sliders controlling the same voice is confusing.
- *Real amplification on Windows* (render SAPI to a buffer and play it through OpenAL with gain): a new audio
  pipeline with latency and distortion risk. It isn't needed once narration no longer follows Master.

## Current behaviour

`GameNarratorSpeaker.voiceVolume()` returns `options.getFinalSoundSourceVolume(SoundSource.VOICE)`, i.e.
Master × Voice/Speech. Both `GameNarratorSpeaker` and `DirectSpeaker` pass that to
`SpeechOutput.submit(text, interrupt, volume)`, which reaches `Narrator.say(text, interrupt, volume)` on
either `SpeechThread` (Windows) or `VanillaEngineOutput` (Linux/macOS).

## Design

### Config — `TitleNarratorConfig`

- New field `public int narratorVolume = 100;` (whole percent), plus `public static final int MAX_VOLUME = 100;`.
- `clamp()` also clamps `narratorVolume` to `[0, MAX_VOLUME]`.
- `copyFrom` copies `narratorVolume`.
- Missing field in an existing JSON file → stays at 100 (Gson keeps field initialisers via the no-arg
  constructor; same mechanism as the existing `missingFieldsKeepDefaults` test).
- Non-numeric value (e.g. `"loud"`) → existing corrupt-file path: `.bak` backup and defaults.

### Volume conversion — `speech/NarratorVolume`

A small pure helper: `static float fromPercent(int percent)` returns `clamp(percent, 0, 100) / 100f`. It
keeps the conversion in one tested place and guards against a caller bypassing config clamping (e.g. a
hand-edited value set at runtime).

### Speakers

- `GameNarratorSpeaker(SpeechOutput output, Supplier<Float> volume)` and
  `DirectSpeaker(SpeechOutput output, Supplier<Float> volume)`. Each `speak` reads `volume.get()` and
  passes it to `output.submit`.
- The static `GameNarratorSpeaker.voiceVolume()` is removed.
- `TitleNarratorClient` builds one supplier, `() -> NarratorVolume.fromPercent(config().narratorVolume)`, and
  passes it to both speakers. Because it reads the config on every title, slider changes apply to the next title.
- `SpeechThread`, `VanillaEngineOutput` and `SpeechOutput` are unchanged.

### Settings screen — `YaclScreenFactory`

- New option directly after "Enable narration": `Option<Integer>` named
  `titlenarrator.config.narratorVolume`, bound to `config.narratorVolume` (default 100), with an
  `IntegerSliderControllerBuilder` using range 0–`MAX_VOLUME`, step 1, and a formatter showing `N%`.
- Split `create(parent)` into `build()` (returns the `YetAnotherConfigLib`) and `create(parent)`
  (`build().generateScreen(parent)`), so the gametest can inspect the options.
- Lang (`en_us.json`):
  - `titlenarrator.config.narratorVolume`: "Narrator volume"
  - `titlenarrator.config.narratorVolume.desc`: "How loud titles are spoken. Independent of the game's Master
    and Voice/Speech sliders — to make the narrator louder than the game, keep this high and lower Master."

### Unchanged behaviour

- The vanilla Narrator option (All/System vs Off/Chat) still gates speech unless "Speak even when Narrator
  is Off" is on.
- Vanilla's own narration (menus, chat) still follows Voice/Speech; only title narration uses the new slider.

### README

- In "The Narrator setting", replace "Narration volume follows the **Voice/Speech** volume slider" with a
  description of the Narrator volume setting and the "keep it high, lower Master" tip.
- Add a `narratorVolume` row to the Settings table.

## Testing

- **Unit (`TitleNarratorConfigTest`)**: default is 100 (`defaultsMatchDesign`); `150` → 100 and `-5` → 0 on
  load; a file without the field loads as 100; `copyFromCopiesEveryField` sets `narratorVolume`; a saved
  value round-trips.
- **Unit (`NarratorVolumeTest`)**: 0 → 0f, 100 → 1f, 50 → 0.5f, 150 → 1f, -10 → 0f.
- **Gametest (`ConfigScreenGameTest`)**: the built YACL config contains an option named
  `titlenarrator.config.narratorVolume` whose pending value equals `config.narratorVolume`. The screen
  screenshot is kept.
- **Full `./gradlew build`** (unit tests) and `./gradlew runClientGameTest` pass.
- **Manual (user, on Windows)**: set Master to 30%, Narrator volume to 100%, trigger `/title @s title
  {"text":"Hello"}`; narration should be clearly louder than game sounds. Then drop the slider to 20% and
  confirm the next title is quieter.

## Out of scope

- Volume above 100% / software amplification.
- A separate volume for action-bar vs title narration.
- A volume keybind.
