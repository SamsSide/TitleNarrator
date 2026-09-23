# Narrator Volume Slider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Narrator volume" slider (0–100%) to Title Narrator's settings screen that controls title narration loudness independently of Minecraft's Master and Voice/Speech sliders.

**Architecture:** A new `narratorVolume` int field in `TitleNarratorConfig` is the single source of truth. A pure helper, `NarratorVolume.fromPercent`, converts it to the 0–1 float the TTS engine takes. Both speakers receive a `Supplier<Float>` in place of the vanilla `voiceVolume()` lookup. `TitleNarratorClient` wires that supplier to the live config, so slider changes apply to the next title. The YACL screen gets an integer slider bound to the field.

**Tech Stack:** Java 25, Fabric Loom 1.17-SNAPSHOT, Minecraft 26.2 (Mojang mappings), YACL 3.9.7+26.2-fabric, JUnit 5 via fabric-loader-junit, Fabric client gametests.

**Spec:** `docs/superpowers/specs/2026-09-23-narrator-volume-design.md`

**Working directory:** `/home/samuel/dev/TitleNarrator/.claude/worktrees/title-narrator` (branch `worktree-title-narrator`). All paths below are relative to it.

## Global Constraints

- Range is exactly 0–100 whole percent; default `100`; JSON key `narratorVolume`.
- Narration volume must NOT depend on Master or Voice/Speech (`getFinalSoundSourceVolume` is no longer used for titles).
- No volume above 100% / no software amplification.
- 0% passes through as volume `0f` (no special skip).
- Lang key names: `titlenarrator.config.narratorVolume` and `titlenarrator.config.narratorVolume.desc`.
- Code style: tabs for indentation, one-line Javadoc on public types, same idiom as surrounding code.
- Commit messages: conventional prefix (`feat:`, `test:`, `docs:`), **no** `Co-Authored-By`, `Claude-Session` or "Generated with Claude Code" lines.

## Review Focus

- An existing `titlenarrator.json` written before this change (no `narratorVolume` key) must load as 100%, not 0%. Tested in Task 1 (`missingVolumeDefaultsToFull`).
- A hand-edited out-of-range value (`150`, `-5`) must be clamped on load, not crash or pass through. Tested in Task 1 (`narratorVolumeIsClamped`).
- A hand-edited non-integer value (`"loud"`, `55.5`) must take the corrupt-file path (`.bak` + defaults), not crash the client. Tested in Task 1 (`nonIntegerVolumeFallsBackToDefaults`).
- Moving the slider must take effect on the next title with no restart, meaning the supplier reads config on every `speak`, not once at construction. Enforced by code shape in Task 2 (the lambda is passed unevaluated), and checked by the reviewer and the manual check in Task 4.
- The slider must show the saved value when the screen opens, not the default. Tested in Task 3 (the gametest sets 37 and asserts the pending value is 37).

---

### Task 1: `narratorVolume` config field

**Files:**
- Modify: `src/client/java/dev/samsside/titlenarrator/config/TitleNarratorConfig.java`
- Test: `src/test/java/dev/samsside/titlenarrator/config/TitleNarratorConfigTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `public int narratorVolume` (default `100`) and `public static final int MAX_VOLUME = 100;` on `TitleNarratorConfig`. They're clamped to `[0, MAX_VOLUME]` in `load` and copied in `copyFrom`.

- [ ] **Step 1: Write the failing tests**

In `TitleNarratorConfigTest.java`:

1. In `defaultsMatchDesign`, add after `assertFalse(config.bypassNarratorSetting);`:

```java
		assertEquals(100, config.narratorVolume);
```

2. In `savedValuesRoundTrip`, add `config.narratorVolume = 40;` after `config.dedupeWindowMs = 1500;`, and add `assertEquals(40, loaded.narratorVolume);` after `assertEquals(1500, loaded.dedupeWindowMs);`.

3. In `copyFromCopiesEveryField`, add `source.narratorVolume = 7;` after `source.bypassNarratorSetting = true;`.

4. Add these tests after `dedupeWindowIsClamped`:

```java
	@Test
	void narratorVolumeIsClamped() throws IOException {
		Files.writeString(file(), "{\"narratorVolume\": 150}");
		assertEquals(TitleNarratorConfig.MAX_VOLUME, TitleNarratorConfig.load(file()).narratorVolume);
		Files.writeString(file(), "{\"narratorVolume\": -5}");
		assertEquals(0, TitleNarratorConfig.load(file()).narratorVolume);
	}

	@Test
	void missingVolumeDefaultsToFull() throws IOException {
		Files.writeString(file(), "{\"enabled\": true, \"dedupeWindowMs\": 3000}");
		assertEquals(100, TitleNarratorConfig.load(file()).narratorVolume);
	}

	@Test
	void nonIntegerVolumeFallsBackToDefaults() throws IOException {
		Files.writeString(file(), "{\"narratorVolume\": \"loud\"}");
		assertDefaults(TitleNarratorConfig.load(file()));
		Files.writeString(file(), "{\"narratorVolume\": 55.5}");
		assertDefaults(TitleNarratorConfig.load(file()));
	}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'dev.samsside.titlenarrator.config.TitleNarratorConfigTest'`
Expected: compilation FAILURE, `cannot find symbol ... narratorVolume` / `MAX_VOLUME`.

- [ ] **Step 3: Implement**

In `TitleNarratorConfig.java`:

Add below `MAX_DEDUPE_WINDOW_MS`:

```java
	public static final int MAX_VOLUME = 100;
```

Add below `public boolean bypassNarratorSetting = false;`:

```java
	/** Title narration loudness in percent, independent of the game's volume sliders. */
	public int narratorVolume = 100;
```

In `copyFrom`, add after `bypassNarratorSetting = other.bypassNarratorSetting;`:

```java
		narratorVolume = other.narratorVolume;
```

In `clamp()`, add after the `dedupeWindowMs` line:

```java
		narratorVolume = Math.clamp(narratorVolume, 0, MAX_VOLUME);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'dev.samsside.titlenarrator.config.TitleNarratorConfigTest'`
Expected: BUILD SUCCESSFUL. If `55.5` does NOT fall back to defaults (Gson accepted it), stop and report; do not change the test to match.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/dev/samsside/titlenarrator/config/TitleNarratorConfig.java src/test/java/dev/samsside/titlenarrator/config/TitleNarratorConfigTest.java
git commit -m "feat: add narratorVolume setting to config"
```

---

### Task 2: Speakers use the mod's volume instead of Master × Voice

**Files:**
- Create: `src/client/java/dev/samsside/titlenarrator/speech/NarratorVolume.java`
- Create: `src/test/java/dev/samsside/titlenarrator/speech/NarratorVolumeTest.java`
- Modify: `src/client/java/dev/samsside/titlenarrator/speech/GameNarratorSpeaker.java`
- Modify: `src/client/java/dev/samsside/titlenarrator/speech/DirectSpeaker.java`
- Modify: `src/client/java/dev/samsside/titlenarrator/TitleNarratorClient.java`

**Interfaces:**
- Consumes: `TitleNarratorConfig.narratorVolume`, `TitleNarratorConfig.MAX_VOLUME` (Task 1).
- Produces:
  - `public static float NarratorVolume.fromPercent(int percent)`: clamps to `[0, MAX_VOLUME]` and returns `percent / 100f`.
  - `new GameNarratorSpeaker(SpeechOutput output, Supplier<Float> volume)`
  - `new DirectSpeaker(SpeechOutput output, Supplier<Float> volume)`
  - `GameNarratorSpeaker.voiceVolume()` is **removed**. `GameNarratorSpeaker.vanillaSpeaksSystemMessages()` stays (HudMixin uses it).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/samsside/titlenarrator/speech/NarratorVolumeTest.java`:

```java
package dev.samsside.titlenarrator.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NarratorVolumeTest {
	@Test
	void convertsPercentToEngineVolume() {
		assertEquals(0f, NarratorVolume.fromPercent(0));
		assertEquals(0.5f, NarratorVolume.fromPercent(50));
		assertEquals(1f, NarratorVolume.fromPercent(100));
	}

	@Test
	void clampsOutOfRangePercent() {
		assertEquals(1f, NarratorVolume.fromPercent(150));
		assertEquals(0f, NarratorVolume.fromPercent(-10));
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'dev.samsside.titlenarrator.speech.NarratorVolumeTest'`
Expected: compilation FAILURE, `cannot find symbol ... NarratorVolume`.

- [ ] **Step 3: Implement `NarratorVolume`**

Create `src/client/java/dev/samsside/titlenarrator/speech/NarratorVolume.java`:

```java
package dev.samsside.titlenarrator.speech;

import dev.samsside.titlenarrator.config.TitleNarratorConfig;

/** Converts the Narrator volume setting into the 0–1 volume the text-to-speech engine takes. */
public final class NarratorVolume {
	private NarratorVolume() {
	}

	public static float fromPercent(int percent) {
		return Math.clamp(percent, 0, TitleNarratorConfig.MAX_VOLUME) / 100f;
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'dev.samsside.titlenarrator.speech.NarratorVolumeTest'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Give both speakers a volume supplier**

Replace the whole of `src/client/java/dev/samsside/titlenarrator/speech/GameNarratorSpeaker.java` with:

```java
package dev.samsside.titlenarrator.speech;

import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.NarratorStatus;

/**
 * Speaks only when the vanilla Narrator option is All or System, through the platform's {@link SpeechOutput}.
 */
public final class GameNarratorSpeaker implements Speaker {
	private final SpeechOutput output;
	private final Supplier<Float> volume;

	public GameNarratorSpeaker(SpeechOutput output, Supplier<Float> volume) {
		this.output = output;
		this.volume = volume;
	}

	@Override
	public void speak(String text, boolean interrupt) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.getNarrator().isActive()) {
			SpeechThread.warnUnavailable();
			return;
		}
		NarratorStatus status = minecraft.options.narrator().get();
		if (!status.shouldNarrateSystem()) {
			LogOnce.info("narrator-option", "[Title Narrator] Narrator option is {}; titles are only spoken when it is "
					+ "All or System, or when 'Speak even when Narrator is Off' is enabled", status);
			return;
		}
		output.submit(text, interrupt, volume.get());
	}

	/** Whether vanilla's own system narration (e.g. {@code saySystemQueued}) would actually be spoken right now. */
	public static boolean vanillaSpeaksSystemMessages() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.getNarrator().isActive() && minecraft.options.narrator().get().shouldNarrateSystem();
	}
}
```

Replace the whole of `src/client/java/dev/samsside/titlenarrator/speech/DirectSpeaker.java` with:

```java
package dev.samsside.titlenarrator.speech;

import java.util.function.Supplier;
import net.minecraft.client.Minecraft;

/** Speaks regardless of the Narrator option, through the platform's {@link SpeechOutput}. */
public final class DirectSpeaker implements Speaker {
	private final SpeechOutput output;
	private final Supplier<Float> volume;

	public DirectSpeaker(SpeechOutput output, Supplier<Float> volume) {
		this.output = output;
		this.volume = volume;
	}

	@Override
	public void speak(String text, boolean interrupt) {
		// Vanilla's engine being inactive means the platform has no usable TTS, so ours would not work either.
		if (!Minecraft.getInstance().getNarrator().isActive()) {
			SpeechThread.warnUnavailable();
			return;
		}
		output.submit(text, interrupt, volume.get());
	}
}
```

- [ ] **Step 6: Wire the live config into the speakers**

In `src/client/java/dev/samsside/titlenarrator/TitleNarratorClient.java`:

Add the import (keep imports sorted):

```java
import dev.samsside.titlenarrator.speech.NarratorVolume;
```

Add `import java.util.function.Supplier;` after `import java.util.function.LongSupplier;`.

Replace:

```java
		Speaker gameSpeaker = new GameNarratorSpeaker(output);
		Speaker directSpeaker = new DirectSpeaker(output);
```

with:

```java
		// Read on every title so a slider change applies to the next one.
		Supplier<Float> volume = () -> NarratorVolume.fromPercent(config().narratorVolume);
		Speaker gameSpeaker = new GameNarratorSpeaker(output, volume);
		Speaker directSpeaker = new DirectSpeaker(output, volume);
```

- [ ] **Step 7: Verify nothing else used `voiceVolume`, then build**

Run: `grep -rn "voiceVolume\|SoundSource.VOICE" src`
Expected: no output.

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (all unit tests pass).

- [ ] **Step 8: Commit**

```bash
git add src/client/java/dev/samsside/titlenarrator/speech/NarratorVolume.java src/test/java/dev/samsside/titlenarrator/speech/NarratorVolumeTest.java src/client/java/dev/samsside/titlenarrator/speech/GameNarratorSpeaker.java src/client/java/dev/samsside/titlenarrator/speech/DirectSpeaker.java src/client/java/dev/samsside/titlenarrator/TitleNarratorClient.java
git commit -m "feat: speak titles at the mod's own volume, independent of Master"
```

---

### Task 3: Narrator volume slider in the settings screen

**Files:**
- Modify: `src/client/java/dev/samsside/titlenarrator/config/YaclScreenFactory.java`
- Modify: `src/client/resources/assets/titlenarrator/lang/en_us.json`
- Modify: `src/gametest/java/dev/samsside/titlenarrator/gametest/ConfigScreenGameTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: `TitleNarratorConfig.narratorVolume`, `TitleNarratorConfig.MAX_VOLUME` (Task 1); `TitleNarratorClient.config()`.
- Produces: `public static YetAnotherConfigLib YaclScreenFactory.build()`. `YaclScreenFactory.create(@Nullable Screen parent)` keeps its signature (Mod Menu uses it as a method reference).

- [ ] **Step 1: Write the failing gametest**

Replace the whole of `src/gametest/java/dev/samsside/titlenarrator/gametest/ConfigScreenGameTest.java` with:

```java
package dev.samsside.titlenarrator.gametest;

import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.samsside.titlenarrator.TitleNarratorClient;
import dev.samsside.titlenarrator.config.ModMenuIntegration;
import dev.samsside.titlenarrator.config.YaclScreenFactory;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;

public final class ConfigScreenGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		Screen screen = context.computeOnClient(mc -> new ModMenuIntegration().getModConfigScreenFactory().create(null));
		if (screen == null) {
			throw new AssertionError("config screen should exist when YACL is installed");
		}
		context.setScreen(() -> screen);
		context.waitTick();
		context.takeScreenshot("titlenarrator-config-screen");
		context.setScreen(() -> null);

		assertVolumeSliderShowsSavedValue(context);
	}

	private static void assertVolumeSliderShowsSavedValue(ClientGameTestContext context) {
		int saved = TitleNarratorClient.config().narratorVolume;
		try {
			TitleNarratorClient.config().narratorVolume = 37;
			Object pending = context.computeOnClient(mc -> findOption(YaclScreenFactory.build(),
					"titlenarrator.config.narratorVolume").pendingValue());
			if (!Integer.valueOf(37).equals(pending)) {
				throw new AssertionError("narrator volume slider should show the saved value 37, got " + pending);
			}
		} finally {
			TitleNarratorClient.config().narratorVolume = saved;
		}
	}

	private static Option<?> findOption(YetAnotherConfigLib yacl, String key) {
		return yacl.categories().stream()
				.flatMap(category -> category.groups().stream())
				.flatMap(group -> group.options().stream())
				.filter(option -> option.name().getContents() instanceof TranslatableContents contents
						&& contents.getKey().equals(key))
				.findFirst()
				.orElseThrow(() -> new AssertionError("config screen has no option " + key));
	}
}
```

- [ ] **Step 2: Run gametests to verify it fails**

Run: `./gradlew runClientGameTest`
Expected: compilation FAILURE, `cannot find symbol ... build()` in `YaclScreenFactory`.

- [ ] **Step 3: Add the slider and split `build()` out of `create()`**

In `src/client/java/dev/samsside/titlenarrator/config/YaclScreenFactory.java`:

Add this import immediately **before** `import dev.isxander.yacl3.api.controller.LongSliderControllerBuilder;` (keeps imports sorted):

```java
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
```

Replace the method header and first lines:

```java
	public static Screen create(@Nullable Screen parent) {
		TitleNarratorConfig config = TitleNarratorClient.config();
```

with:

```java
	public static Screen create(@Nullable Screen parent) {
		return build().generateScreen(parent);
	}

	public static YetAnotherConfigLib build() {
		TitleNarratorConfig config = TitleNarratorClient.config();
```

Replace the tail of the builder chain:

```java
				.save(TitleNarratorClient::saveConfig)
				.build()
				.generateScreen(parent);
	}
```

with:

```java
				.save(TitleNarratorClient::saveConfig)
				.build();
	}
```

Insert the slider directly after the `enabled` toggle line (`.option(toggle("enabled", ...))`):

```java
						.option(Option.<Integer>createBuilder()
								.name(Component.translatable("titlenarrator.config.narratorVolume"))
								.description(OptionDescription.of(Component.translatable("titlenarrator.config.narratorVolume.desc")))
								.binding(defaults.narratorVolume, () -> config.narratorVolume, v -> config.narratorVolume = v)
								.controller(option -> IntegerSliderControllerBuilder.create(option)
										.range(0, TitleNarratorConfig.MAX_VOLUME)
										.step(1)
										.formatValue(v -> Component.literal(v + "%")))
								.build())
```

- [ ] **Step 4: Add the lang entries**

In `src/client/resources/assets/titlenarrator/lang/en_us.json`, add after the `"titlenarrator.config.enabled.desc"` line:

```json
	"titlenarrator.config.narratorVolume": "Narrator volume",
	"titlenarrator.config.narratorVolume.desc": "How loud titles are spoken. Independent of the game's Master and Voice/Speech sliders — to make the narrator louder than the game, keep this high and lower Master.",
```

- [ ] **Step 5: Run gametests to verify they pass**

Run: `./gradlew runClientGameTest`
Expected: BUILD SUCCESSFUL, all gametests pass (including `ConfigScreenGameTest`). Open the screenshot under `build/run/clientGameTest/screenshots/` (search with `find build/run -name 'titlenarrator-config-screen*'`) and confirm a "Narrator volume" row showing `100%` sits below "Enable narration".

- [ ] **Step 6: Update the README**

In `README.md`, section "The Narrator setting", replace the sentence:

```
Narration volume follows the **Voice/Speech** volume slider.
```

with:

```
Title narration has its own **Narrator volume** setting (0–100%), separate from Minecraft's Master and Voice/Speech sliders. To make the narrator louder than the game, keep Narrator volume high and turn Master down. Windows' voice can't go above 100%, so the narrator stands out by turning the game down, not by boosting the voice.
```

In the Settings table, add this row directly after the `Enable narration` row:

```
| Narrator volume | `narratorVolume` | `100` | How loud titles are spoken, 0–100%. Not affected by Master or Voice/Speech. |
```

- [ ] **Step 7: Commit**

```bash
git add src/client/java/dev/samsside/titlenarrator/config/YaclScreenFactory.java src/client/resources/assets/titlenarrator/lang/en_us.json src/gametest/java/dev/samsside/titlenarrator/gametest/ConfigScreenGameTest.java README.md
git commit -m "feat: add Narrator volume slider to the settings screen"
```

---

### Task 4: Final verification

**Files:** none changed.

- [ ] **Step 1: Clean build and all tests**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: All gametests**

Run: `./gradlew runClientGameTest`
Expected: BUILD SUCCESSFUL, every gametest passes.

- [ ] **Step 3: Confirm the volume is live-read**

Run: `grep -n "narratorVolume" src/client/java/dev/samsside/titlenarrator/TitleNarratorClient.java`
Expected: exactly one hit, inside the `() -> NarratorVolume.fromPercent(config().narratorVolume)` lambda. It must not be a value computed once and captured.

- [ ] **Step 4: Hand the manual check to the user** (needs Windows with audio; can't be automated here)

Ask the user to: set Master to 30% and Narrator volume to 100%, then run `/title @s title {"text":"Hello"}` in a singleplayer world. Narration should be clearly louder than the game sounds. Then set Narrator volume to 20% (Save), run the command again, and the narration should be quieter with no restart.
