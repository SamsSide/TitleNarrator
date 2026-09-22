# Title Narrator — Design

Date: 2026-09-22
Source requirements: `docs/title-narrator-mod-spec.md` (the "mod spec"). This document records the agreed design; the mod spec remains authoritative for requirements and the testing checklist.

## Intent

A client-only Fabric mod for Minecraft Java **26.2** that speaks on-screen titles and subtitles (from `/title`, datapacks, plugins, servers) through the vanilla narrator. Accessibility/immersion; must work in singleplayer and on unmodified multiplayer servers.

**Success:** the mod spec's testing checklist passes (automated where possible), `./gradlew build` produces a jar, README documents install, the Narrator setting, the Linux TTS caveat, config, and any spec deviations.

## Decisions

| Decision | Choice |
|---|---|
| Scope | Core + all stretch features, one plan, core first |
| Target | Minecraft 26.2 (26.3 exists but is out of scope) |
| Config UI | YACL `3.9.7+26.2-fabric` + Mod Menu `20.0.2`, both optional |
| Fabric API | `0.161.0+26.2` |
| Testing | JUnit for pure logic + Fabric client gametests for in-game behaviour; manual only for audible TTS, bypass, multiplayer |
| Package / mod id | `dev.samsside.titlenarrator` / `titlenarrator` |
| Architecture | Thin mixins → pure-Java pipeline behind a `Speaker` interface |

Rejected: packet-level hooks (late-subtitle handling needs `Hud` state anyway); logic inside the mixin (untestable).

## Components

| Unit | Responsibility | Depends on |
|---|---|---|
| `mixin/HudMixin` | Inject into `Hud#setTitle` (TAIL, reads shadowed `subtitle`), `Hud#setSubtitle` (HEAD, reads shadowed `titleTime` for late subtitle), `Hud#setOverlayMessage` (TAIL, action bar). Forwards plain strings + "title currently visible" to `TitleNarrator`. | `net.minecraft.client.gui.Hud` (verified, see below) |
| `mixin/GameNarratorAccessor` | Accessor for `GameNarrator`'s underlying `com.mojang.text2speech.Narrator`. | `GameNarrator` |
| `TitleNarrator` | Façade: checks config + runtime toggle → composes text → `TextSanitiser` → `Deduper` → `Speaker`. | all below |
| `TextComposer` | `compose(title, subtitle)` → `"title. subtitle"` or `"title"`; blank → empty. Pure. | — |
| `TextSanitiser` | Small-caps → ASCII; strip decorative symbols/emoji; collapse whitespace. Pure. | — |
| `Deduper` | Suppress identical text within a configurable window; injectable `LongSupplier` clock. | — |
| `Speaker` (interface) | `speak(String text, boolean interrupt)`. Impls: `GameNarratorSpeaker` (respects Narrator option), `DirectSpeaker` (bypass). Each catches TTS failures, logs once, then no-ops. Tests use `RecordingSpeaker`. | narrator |
| `config/TitleNarratorConfig` | POJO + Gson load/save at `config/titlenarrator.json`; works without YACL. | Gson (bundled with MC) |
| `config/YaclScreenFactory`, `ModMenuIntegration` | Build YACL screen; loaded only via the `modmenu` entrypoint, so YACL/Mod Menu remain optional. | YACL, Mod Menu (`compileOnly`) |
| `ToggleKeybind` | Fabric keybind (unbound by default, to avoid clashing with other mods) toggles `enabled` and saves; confirmation via a toast (not the action bar, which would self-narrate). | Fabric API |

## Config fields and defaults

| Field | Default |
|---|---|
| `enabled` | `true` |
| `narrateTitles` | `true` |
| `narrateSubtitles` | `true` |
| `narrateActionBar` | `false` |
| `lateSubtitles` | `true` |
| `sanitiseText` | `true` |
| `dedupeWindowMs` | `3000` (clamped to 0–10000) |
| `interrupt` | `true` (false = queue) |
| `bypassNarratorSetting` | `false` |

## Data flow

- `setTitle(title)` @TAIL: blank title → return (covers `/title clear|reset`). Else compose with shadowed `subtitle` (if `narrateSubtitles`) → sanitise → dedupe → speak.
- `setSubtitle(sub)`: only if `lateSubtitles` and a title is currently visible (`Hud.titleTime > 0`) → queue the subtitle as a *pending late subtitle*, spoken after `LATE_SUBTITLE_DELAY_TICKS` (2) client ticks unless a `setTitle` arrives first (which cancels it and narrates `title. subtitle` instead). The delay stops the normal "subtitle then title" sequence from double-speaking the subtitle when a previous title is still on screen. Otherwise no-op.
- `setOverlayMessage(msg, …)`: only if `narrateActionBar` → sanitise → dedupe → speak.
- Title times / fade packets: not hooked.
- Speaker selection per call: `bypassNarratorSetting ? DirectSpeaker : GameNarratorSpeaker`.

## Error handling

- TTS failure/unavailable (e.g. Linux without flite): log one warning per session, then silent. Never crash the client.
- Missing/corrupt config: log, fall back to defaults, rewrite file.
- Bypass: the `GameNarrator.narrator` field is final and non-null, and the accessor mixin is `required`, so no runtime fallback is needed; an inactive TTS engine is handled like any other TTS-unavailable case.
- Any exception thrown while speaking is caught in `TitleNarrator`, logged once, and swallowed.
- 26.2 source differs from mod spec: record under "Spec deviations" in README; do not silently work around.

## Testing

- **JUnit** (`src/test`): `TextComposer`, `TextSanitiser` (small-caps table, symbol stripping), `Deduper` (fake clock), `TitleNarrator` pipeline with `RecordingSpeaker` and config permutations, config load/save/corrupt file.
- **Client gametests** (`fabric-client-gametest-api-v1`): swap in `RecordingSpeaker`, create a singleplayer world with cheats, run the mod spec checklist via `/title` commands and assert recordings: plain title, subtitle→title, repeat within/after window, coloured JSON, `clear`, late subtitle, action bar on/off, small caps, keybind toggle.
- **Manual**: audible TTS on the user's OS, Narrator Off + bypass, a real multiplayer server.

## Verified against 26.2 (spike, 2026-09-22)

A throwaway spike compiled and ran against Minecraft 26.2 / Fabric API `0.161.0+26.2` / Loader `0.19.5` / Loom `1.17-SNAPSHOT`. Findings, including **deviations from the mod spec**:

| Mod spec said | 26.2 reality |
|---|---|
| Titles handled in `net.minecraft.client.gui.Gui` | **Moved to `net.minecraft.client.gui.Hud`** (`Minecraft.gui.hud`). Same method names: `setTitle(Component)`, `setSubtitle(Component)`, `setOverlayMessage(Component, boolean)`, `clearTitles()`, `resetTitleTimes()`, `setTimes(int,int,int)`. Private fields `title`, `subtitle`, `titleTime` (int ticks). |
| `GameNarrator#sayNow(String/Component)` | **Renamed:** `saySystemNow(String)` / `saySystemNow(Component)` (interrupting), `saySystemQueued(Component)` (non-interrupting). Both speak only when `NarratorStatus.shouldNarrateSystem()` (option `ALL` or `SYSTEM`). |
| Underlying narrator | `GameNarrator` private field `narrator` of type `com.mojang.text2speech.Narrator` (`say(String, boolean interrupt, float volume)`, `clear()`, `active()`). Volume: `Options#getFinalSoundSourceVolume(SoundSource.VOICE)`. |
| `/title clear` / `reset` | Call `Hud#clearTitles()` (+ `resetTitleTimes()`); `setTitle` is **not** called. Blank check still needed for `/title @s title ""`. |
| Action bar | Both `ClientboundSetActionBarTextPacket` and overlay system-chat call `Hud#setOverlayMessage`. Client-local overlay messages (e.g. jukebox "Now Playing") also do. **Overlay system chat goes through `ChatListener#handleOverlay`, which also calls `GameNarrator#saySystemQueued`** — so the mod skips those when vanilla will speak them (found in final review). |
| Linux TTS | Confirmed: `Narrator$InitializeException: Failed to load library flite` when libflite is missing; vanilla logs and continues with an inactive narrator. |
| Keybind API | `net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping`; `KeyMapping(String, InputConstants.Type, int, KeyMapping.Category)`; `KeyMapping.Category.register(Identifier)` (lang key `key.category.<ns>.<path>`). |
| Toasts | `Minecraft.gui.toastManager()`; `SystemToast.addOrUpdate(ToastManager, SystemToastId, Component, Component)`. |
| Client gametests | Run headless under WSL via `./gradlew runClientGameTest`; `gametest` source set sees client classes; JUnit in `src/test` can test client-source-set classes. |
| YACL | Mod id `yet_another_config_lib_v3`, maven `dev.isxander:yet-another-config-lib:3.9.7+26.2-fabric` (repo `https://maven.isxander.dev/releases`). Mod Menu `com.terraformersmc:modmenu:20.0.2` (repo `https://maven.terraformersmc.com/releases`). |

## Build

Fabric template (split client/common sources; all mod code lives in the `client` source set, `main` holds only `fabric.mod.json`), Loom plugin `net.fabricmc.fabric-loom`, no `mappings` line, `implementation`/`compileOnly` configurations, Java 25 toolchain. `fabric.mod.json`: `"environment": "client"`, `suggests` YACL and Mod Menu. Access wideners (if needed) use namespace `official`.
