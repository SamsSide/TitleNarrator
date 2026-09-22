# Title Narrator — Fabric Mod Spec (Minecraft 26.2)

## Goal

Build a **client-side Fabric mod** for **Minecraft Java 26.2** that reads aloud (text-to-speech) any **title** and **subtitle** displayed in the centre of the screen, including those triggered by `/title` from command blocks, datapacks, plugins, or servers.

It is an accessibility / immersion mod. It must work in singleplayer and on any multiplayer server (vanilla, Paper, etc.) without server-side installation.

## Core requirements

1. When a title appears on screen, speak it using Minecraft's built-in narrator (the same TTS used by the vanilla "Narrator" accessibility option).
2. If a subtitle is present, speak it after the title in a single utterance: `"<title>. <subtitle>"`.
3. Plain text only: use `Component#getString()` so colours and formatting codes are stripped.
4. Do not spam: if the exact same text was narrated within a short window (default 3 seconds), skip it. This handles repeating command blocks and maps that re-send titles.
5. Client only: `"environment": "client"` in `fabric.mod.json`; no server-side component.

## Stretch requirements (implement after core works)

- **Action bar narration**: optionally also speak action-bar messages (`/title actionbar`), off by default because action bars are often updated every tick.
- **Late subtitle handling**: some maps send the subtitle *after* the title. If `setSubtitle` is called while a title is currently displayed, narrate the subtitle on its own.
- **Text sanitisation**: convert small-caps Unicode (e.g. `ᴄʀᴀꜰᴛᴇᴅ`) back to normal ASCII letters, and strip decorative symbols/emoji that TTS mangles or reads literally.
- **Narrator setting bypass**: vanilla narration does nothing when the Narrator option is Off. Offer a config toggle that speaks titles regardless of that setting, by reaching the underlying `com.mojang.text2speech.Narrator` through an accessor mixin on `GameNarrator`.
- **Config** (Mod Menu + Cloth Config or YACL, optional dependency): enable/disable titles, subtitles, action bar; dedupe window; interrupt vs queue behaviour; bypass-narrator-setting toggle.
- **Keybind**: toggle narration on/off in-game, with a short on-screen confirmation.

## Technical context (important — much older tutorial material is outdated)

### 26.x is unobfuscated

- Minecraft Java has been unobfuscated since **26.1**. Fabric no longer maintains **Yarn** mappings for these versions.
- Use **Mojang's official names** throughout, e.g. `net.minecraft.client.gui.Gui`, not Yarn's `InGameHud`.
- Because the game is unobfuscated, **verify every class, method, and field name by reading the decompiled 26.2 source in the IDE**. Do not trust names from pre-26.1 tutorials or from memory.

### Build setup (differs from pre-26.1 tutorials)

- Generate the project from the Fabric template generator at https://fabricmc.net/develop, selecting 26.2 and split client/common sources.
- In `build.gradle`, the Loom plugin ID is `net.fabricmc.fabric-loom` (not `fabric-loom`).
- No `mappings` line in the dependencies block.
- Use `implementation` / `compileOnly` / `api` instead of `modImplementation` / `modCompileOnly` / `modApi`.
- Java **25** toolchain; IntelliJ IDEA 2025.3 or newer.
- If using an access widener or class tweaker, its header namespace is `official`, not `named`.

### Where titles are handled

- The server sends title packets; the client network handler passes them to the HUD class `net.minecraft.client.gui.Gui`.
- Relevant methods (verify signatures in 26.2):
  - `Gui#setTitle(Component)`: shows the main title. **This is the trigger point.**
  - `Gui#setSubtitle(Component)`: stores the subtitle; it is normally sent *before* the title and only shown once the title arrives.
  - `Gui#setOverlayMessage(Component, boolean)`: action bar.
- `Gui` holds the pending subtitle in a private field (expected name `subtitle`). Shadow it so the title injection can read both at once.

### Narration API

- Access via `Minecraft.getInstance().getNarrator()`, which returns `GameNarrator`.
- Expected method: `sayNow(String)` / `sayNow(Component)`, which interrupts current speech. **Confirm the exact method names and how they check the Narrator option in 26.2's `GameNarrator` source before relying on them.**
- `GameNarrator` wraps `com.mojang.text2speech.Narrator`, which does the actual platform TTS.

## Reference implementation sketch

This is a starting point, not verified against 26.2. Check the names before relying on it.

```java
@Mixin(Gui.class)
public abstract class TitleNarrationMixin {
    @Shadow private Component subtitle;

    @Inject(method = "setTitle", at = @At("TAIL"))
    private void titlenarrator$onTitle(Component title, CallbackInfo ci) {
        TitleNarrator.narrate(title, this.subtitle);
    }
}
```

```java
public final class TitleNarrator {
    private static String last = "";
    private static long lastTime = 0;

    public static void narrate(Component title, Component subtitle) {
        String text = title.getString();
        if (subtitle != null && !subtitle.getString().isBlank()) {
            text += ". " + subtitle.getString();
        }
        long now = System.currentTimeMillis();
        if (text.isBlank() || (text.equals(last) && now - lastTime < 3000)) return;
        last = text;
        lastTime = now;
        Minecraft.getInstance().getNarrator().sayNow(text);
    }
}
```

## Known gotchas

| Issue | Handling |
|---|---|
| Subtitle arrives before title | Read the shadowed `subtitle` field when `setTitle` fires |
| Repeating command blocks re-send titles | Dedupe identical text within a configurable window |
| Narrator option set to Off | Document it, or provide the bypass toggle (stretch) |
| Linux TTS | Mojang's text2speech needs system libraries (flite) that are often missing; fail silently and log once, and document it in the README |
| Stylised Unicode text | Sanitise before speaking (stretch) |
| `/title clear` / `/title reset` | Should not trigger narration; make sure empty/blank titles are ignored |
| Title times / fade packets | Don't hook these; only react to actual title text |

## Testing checklist

In a singleplayer world with cheats enabled:

1. `/title @s title "Hello"` → speaks "Hello".
2. `/title @s subtitle "World"` then `/title @s title "Hello"` → speaks "Hello. World".
3. A repeating command block running `/title @a title "Spam"` → speaks once, then again only after the dedupe window.
4. `/title @s title {"text":"Coloured","color":"red","bold":true}` → speaks "Coloured" with no formatting artefacts.
5. `/title @s clear` → no speech.
6. Narrator option Off → behaves per config (silent, or bypass if enabled).
7. Join a multiplayer server that sends titles → narration works with no server mod installed.
8. If implemented: action bar toggle, small-caps text, keybind toggle.

## Deliverables

- A buildable Fabric project for 26.2: `build.gradle`, `gradle.properties`, `settings.gradle`, `fabric.mod.json`, mixins JSON (client section), source files.
- `README.md` covering: what it does, installation, the Narrator setting note, the Linux TTS caveat, and config options.
- A built `.jar` via `./gradlew build`, tested against the checklist above.

## Instructions for the agent

1. Scaffold the project using the 26.2 setup above.
2. Before writing mixins, open the decompiled `Gui` and `GameNarrator` classes and confirm the method signatures and field names. Adjust the sketch to match.
3. Implement the core requirements first and get them passing the testing checklist.
4. Then add the stretch features one at a time, keeping config dependencies optional.
5. Flag any place where the 26.2 source differs from this spec rather than silently working around it.
