# Title Narrator

A client-side Fabric mod for Minecraft Java **26.2** that reads on-screen **titles** and **subtitles** aloud with Minecraft's built-in narrator — including titles from `/title`, command blocks, datapacks, plugins and servers. It is an accessibility and immersion mod, and it works on any server (vanilla, Paper, …) without anything installed server-side.

## What gets spoken

- A title, followed by its subtitle: `"Hello. World"`.
- A subtitle that arrives *after* its title is already on screen is spoken on its own.
- Optionally, action-bar messages (the text above the hotbar). Off by default because some servers update them every tick.
- Formatting is dropped. Stylised text is cleaned up: small caps (`ᴄʀᴀꜰᴛᴇᴅ`) become normal letters; symbols, emoji and custom resource-pack glyphs are skipped.
- The same text is not repeated within 3 seconds (configurable), so repeating command blocks don't spam you.
- `/title clear`, `/title reset` and empty titles are silent.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.19.5+ for Minecraft 26.2.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) and `title-narrator-<version>.jar` in your `mods` folder.
3. Optional: add [YACL](https://modrinth.com/mod/yacl) and [Mod Menu](https://modrinth.com/mod/modmenu) for an in-game settings screen. Without them, edit `config/titlenarrator.json`.

## The Narrator setting

Minecraft only speaks when **Options → Accessibility → Narrator** is set to **All** or **System** (press `Ctrl+B` to cycle it). With it set to **Off** or **Chat**, titles stay silent — unless you turn on **Speak even when Narrator is Off**, which uses text-to-speech directly. Narration volume follows the **Voice/Speech** volume slider.

## Linux

Minecraft's text-to-speech on Linux needs the **flite** library, which is often not installed. Without it the log shows `Failed to load library flite` and nothing is spoken; the mod logs one warning and otherwise carries on. Install the package that provides `libflite.so`, for example:

- Debian/Ubuntu: `sudo apt install flite1-dev`
- Fedora: `sudo dnf install flite-devel`
- Arch: `sudo pacman -S flite`

Check with `ldconfig -p | grep libflite`.

## Toggle keybind

**Toggle title narration** is unbound by default (to avoid clashing with other mods). Bind it under **Options → Controls → Key Binds → Title Narrator**. Pressing it shows a small toast saying whether narration is now on or off.

## Settings

Via Mod Menu → Title Narrator → Configure (needs YACL), or by editing `config/titlenarrator.json` while the game is closed.

| Setting | JSON key | Default | Meaning |
|---|---|---|---|
| Enable narration | `enabled` | `true` | Master switch (the keybind flips this). |
| Speak titles | `narrateTitles` | `true` | Speak the large centre text. |
| Speak subtitles | `narrateSubtitles` | `true` | Add the subtitle after the title. |
| Speak late subtitles | `lateSubtitles` | `true` | Speak a subtitle that arrives while its title is already showing. |
| Speak action bar | `narrateActionBar` | `false` | Speak messages above the hotbar. |
| Clean up stylised text | `sanitiseText` | `true` | Small caps → letters; drop symbols, emoji and formatting codes. |
| Repeat suppression (ms) | `dedupeWindowMs` | `3000` | Don't repeat identical text within this window (0–10000). |
| Interrupt current speech | `interrupt` | `true` | `false` queues new titles after the current speech instead. |
| Speak even when Narrator is Off | `bypassNarratorSetting` | `false` | Ignore the vanilla Narrator option. |

If the file is unreadable, the mod keeps a copy as `titlenarrator.json.bak` and starts again from the defaults.

## Spec deviations

Differences between the original spec (`docs/title-narrator-mod-spec.md`) and Minecraft 26.2, found by reading the 26.2 game code:

- Titles are handled by **`net.minecraft.client.gui.Hud`**, not `Gui` (`Minecraft.gui.hud`). Same method names: `setTitle`, `setSubtitle`, `setOverlayMessage`.
- `GameNarrator` has no `sayNow`: the methods are **`saySystemNow`** (interrupting) and **`saySystemQueued`** (queued). Both only speak when the Narrator option is All or System.
- `/title clear` and `/title reset` call `Hud#clearTitles`, so they never reach `setTitle` at all.
- Late subtitles are spoken **2 client ticks** after they arrive, and only if no title follows in that time. This stops the normal "subtitle, then title" order from reading the subtitle twice when an earlier title is still on screen.
- Action-bar narration also picks up client-side overlay messages (for example the jukebox "Now Playing" text), because they go through the same `Hud` method.

## Building and testing

```bash
./gradlew build              # compile, unit tests, jar in build/libs/
./gradlew runClientGameTest  # in-game tests: /title scenarios, keybind, config screen, dedicated server
./gradlew runClient          # dev client with YACL + Mod Menu
```

Requires Java 25.
