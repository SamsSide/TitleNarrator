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

Rejected: packet-level hooks (late-subtitle handling needs `Gui` state anyway); logic inside the mixin (untestable).

## Components

| Unit | Responsibility | Depends on |
|---|---|---|
| `mixin/GuiMixin` | Inject into `Gui#setTitle` (TAIL, reads shadowed `subtitle`), `Gui#setSubtitle` (late subtitle), `Gui#setOverlayMessage` (action bar). Forwards plain strings + "title currently visible" to `TitleNarrator`. | `Gui` (names verified in 26.2 source) |
| `mixin/GameNarratorAccessor` | Accessor for `GameNarrator`'s underlying `com.mojang.text2speech.Narrator`. | `GameNarrator` |
| `TitleNarrator` | Façade: checks config + runtime toggle → composes text → `TextSanitiser` → `Deduper` → `Speaker`. | all below |
| `TextComposer` | `compose(title, subtitle)` → `"title. subtitle"` or `"title"`; blank → empty. Pure. | — |
| `TextSanitiser` | Small-caps → ASCII; strip decorative symbols/emoji; collapse whitespace. Pure. | — |
| `Deduper` | Suppress identical text within a configurable window; injectable `LongSupplier` clock. | — |
| `Speaker` (interface) | `speak(String text, boolean interrupt)`. Impls: `GameNarratorSpeaker` (respects Narrator option), `DirectSpeaker` (bypass). Each catches TTS failures, logs once, then no-ops. Tests use `RecordingSpeaker`. | narrator |
| `config/TitleNarratorConfig` | POJO + Gson load/save at `config/titlenarrator.json`; works without YACL. | Gson (bundled with MC) |
| `config/YaclScreenFactory`, `ModMenuIntegration` | Build YACL screen; loaded only via the `modmenu` entrypoint, so YACL/Mod Menu remain optional. | YACL, Mod Menu (`compileOnly`) |
| `ToggleKeybind` | Fabric keybind toggles runtime enabled state; confirmation via a toast (not the action bar, which would self-narrate). | Fabric API |

## Config fields and defaults

| Field | Default |
|---|---|
| `enabled` | `true` |
| `narrateTitles` | `true` |
| `narrateSubtitles` | `true` |
| `narrateActionBar` | `false` |
| `lateSubtitles` | `true` |
| `sanitiseText` | `true` |
| `dedupeWindowMs` | `3000` |
| `interrupt` | `true` (false = queue) |
| `bypassNarratorSetting` | `false` |

## Data flow

- `setTitle(title)` @TAIL: blank title → return (covers `/title clear|reset`). Else compose with shadowed `subtitle` (if `narrateSubtitles`) → sanitise → dedupe → speak.
- `setSubtitle(sub)`: only if `lateSubtitles` and a title is currently visible (title time remaining > 0, field name verified in 26.2) → narrate subtitle alone. Otherwise no-op; the title hook picks it up.
- `setOverlayMessage(msg, …)`: only if `narrateActionBar` → sanitise → dedupe → speak.
- Title times / fade packets: not hooked.
- Speaker selection per call: `bypassNarratorSetting ? DirectSpeaker : GameNarratorSpeaker`.

## Error handling

- TTS failure/unavailable (e.g. Linux without flite): log one warning per session, then silent. Never crash the client.
- Missing/corrupt config: log, fall back to defaults, rewrite file.
- Bypass accessor unavailable/null: fall back to `GameNarratorSpeaker`, log once.
- 26.2 source differs from mod spec: record under "Spec deviations" in README; do not silently work around.

## Testing

- **JUnit** (`src/test`): `TextComposer`, `TextSanitiser` (small-caps table, symbol stripping), `Deduper` (fake clock), `TitleNarrator` pipeline with `RecordingSpeaker` and config permutations, config load/save/corrupt file.
- **Client gametests** (`fabric-client-gametest-api-v1`): swap in `RecordingSpeaker`, create a singleplayer world with cheats, run the mod spec checklist via `/title` commands and assert recordings: plain title, subtitle→title, repeat within/after window, coloured JSON, `clear`, late subtitle, action bar on/off, small caps, keybind toggle.
- **Manual**: audible TTS on the user's OS, Narrator Off + bypass, a real multiplayer server.

## Build

Fabric template (split client/common sources), Loom plugin `net.fabricmc.fabric-loom`, no `mappings` line, `implementation`/`compileOnly` configurations, Java 25 toolchain. `fabric.mod.json`: `"environment": "client"`, `suggests` YACL and Mod Menu. Access wideners (if needed) use namespace `official`.
