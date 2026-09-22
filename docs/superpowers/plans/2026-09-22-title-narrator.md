# Title Narrator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A client-only Fabric mod for Minecraft Java 26.2 that speaks on-screen titles, subtitles (and optionally action-bar messages) through Minecraft's narrator.

**Architecture:** Two thin mixins (`Hud` for title/subtitle/action-bar events, `GameNarrator` accessor for the bypass) forward plain strings into a pure-Java pipeline — `TitleNarrator` → `TextSanitiser` / `TextComposer` → `Deduper` → `Speaker`. Everything except the mixins and the two real `Speaker` implementations is plain Java tested with JUnit; the in-game behaviour is tested with Fabric client gametests that swap in a recording `Speaker`.

**Tech Stack:** Java 25, Fabric Loom `1.17-SNAPSHOT` (`net.fabricmc.fabric-loom`), Fabric Loader `0.19.5`, Fabric API `0.161.0+26.2`, Mixin, Gson (bundled with Minecraft), JUnit 5 via `fabric-loader-junit`, `fabric-client-gametest-api-v1`, optional YACL `3.9.7+26.2-fabric` + Mod Menu `20.0.2`.

**Spec:** `docs/superpowers/specs/2026-09-22-title-narrator-design.md` (design + verified 26.2 facts) and `docs/title-narrator-mod-spec.md` (original requirements and testing checklist). Read both before starting.

## Global Constraints

- Minecraft **26.2** only: `"minecraft": "~26.2"` in `fabric.mod.json`, `minecraft_version=26.2`.
- Java **25**: `options.release = 25`, source/target `JavaVersion.VERSION_25`, mixin `compatibilityLevel` `JAVA_25`.
- Loom plugin id `net.fabricmc.fabric-loom`; **no `mappings` line**; use `implementation` / `compileOnly` / `localRuntime` / `testImplementation` — never `modImplementation` / `modCompileOnly` / `modApi`.
- Mojang official names only (26.x is unobfuscated). Titles live in `net.minecraft.client.gui.Hud`, **not** `Gui` (verified; see spec "Verified against 26.2").
- Client only: `"environment": "client"`; no `main` entrypoint; all Java code lives in `src/client/java`; `src/main` holds only `resources/fabric.mod.json`.
- Mod id `titlenarrator`; Java package `dev.samsside.titlenarrator`; mixin handler names prefixed `titlenarrator$`.
- YACL and Mod Menu stay **optional**: `compileOnly` + `localRuntime` in Gradle, `suggests` (never `depends`) in `fabric.mod.json`; no class outside `config/YaclScreenFactory` may reference YACL types.
- Narration code must never crash the client: exceptions from speaking are caught, logged once, swallowed.
- Indent with tabs (matches the Fabric template).
- Any place the 26.2 game differs from `docs/title-narrator-mod-spec.md` is recorded in the README "Spec deviations" section.

## Review Focus

1. **Subtitle-then-title while an earlier title is still on screen** — must speak `"Second. Second sub"` once, never the subtitle on its own as well. Pinned by Task 6 (`lateSubtitleIsCancelledByFollowingTitle`) and Task 8 gametest scenario `nextTitleWhileVisible`.
2. **Titles made only of decorative glyphs / resource-pack private-use icons** (`"★★★"`, `""`) — silence, not "black star" or "private use character"; a symbol-only title with a real subtitle speaks just the subtitle. Pinned by Task 3 (`symbolOnlyTextBecomesEmpty`, `privateUseGlyphsAreRemoved`) and Task 6 (`symbolOnlyTitleIsSilent`, `symbolOnlyTitleWithSubtitleSpeaksSubtitle`).
3. **Legacy `§` formatting codes embedded in literal text by plugins** (`"§6§lGold"`) — `Component#getString()` does not strip these; they must not be read aloud. Pinned by Task 3 (`legacyFormattingCodesAreStripped`).
4. **Hand-edited or corrupt config** (bad JSON, empty file, wrong types, out-of-range window, missing fields) — defaults for bad files with a `.bak` of the original, per-field defaults for missing fields, clamping, no crash. Pinned by Task 5 tests.
5. **TTS engine throwing at speak time** (native library failure) — the client keeps running, the failure is logged once. Pinned by Task 6 (`speakerFailureIsSwallowed`) and Task 6 `LogOnceTest`.

---

## File Structure

```
build.gradle, settings.gradle, gradle.properties, gradlew, gradlew.bat, gradle/wrapper/*, .gitignore, LICENSE, README.md
src/main/resources/fabric.mod.json                                   mod metadata (client-only)
src/client/resources/titlenarrator.client.mixins.json                 mixin config
src/client/resources/assets/titlenarrator/lang/en_us.json             keybind, toast, config strings
src/client/java/dev/samsside/titlenarrator/
    TitleNarratorClient.java        ClientModInitializer; owns config, narrator, test overrides
    ToggleKeybind.java              toggle keybind + toast
    core/TextComposer.java          "title. subtitle" joining
    core/TextSanitiser.java         small caps → ASCII, strip symbols/emoji/§ codes
    core/Deduper.java               repeat suppression (fixed or sliding window)
    core/TitleNarrator.java         event → text → dedupe → speaker pipeline, late-subtitle timer
    speech/Speaker.java             functional interface
    speech/LogOnce.java             once-per-session logging
    speech/GameNarratorSpeaker.java vanilla narrator (respects Narrator option)
    speech/DirectSpeaker.java       raw text2speech narrator (bypass)
    config/TitleNarratorConfig.java settings POJO + Gson load/save
    config/ModMenuIntegration.java  Mod Menu entrypoint (YACL-guarded)
    config/YaclScreenFactory.java   YACL screen (only YACL-aware class)
    mixin/HudMixin.java             title / subtitle / action-bar hooks
    mixin/GameNarratorAccessor.java accessor for GameNarrator.narrator
src/test/java/dev/samsside/titlenarrator/...                         JUnit tests (mirror packages)
src/gametest/resources/fabric.mod.json                                gametest mod metadata
src/gametest/java/dev/samsside/titlenarrator/gametest/
    RecordingSpeaker.java, TitleNarratorGameTest.java, ToggleKeybindGameTest.java,
    ConfigScreenGameTest.java, MultiplayerGameTest.java
```

---

### Task 1: Scaffold the Fabric 26.2 project

**Files:**
- Create: `settings.gradle`, `gradle.properties`, `build.gradle`, `.gitignore`, `LICENSE`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- Create: `src/main/resources/fabric.mod.json`
- Create: `src/client/resources/titlenarrator.client.mixins.json`
- Create: `src/client/java/dev/samsside/titlenarrator/TitleNarratorClient.java`

**Interfaces:**
- Consumes: nothing.
- Produces: a building project; `TitleNarratorClient implements ClientModInitializer` (empty body for now; Task 8 fills it). Gradle tasks `build`, `test`, `runClient`, `runClientGameTest`.

- [ ] **Step 1: Copy the Gradle wrapper from the official Fabric template (pinned commit)**

```bash
SCRATCH=$(mktemp -d)
git clone -q https://github.com/FabricMC/fabric-example-mod.git "$SCRATCH/template"
git -C "$SCRATCH/template" checkout -q 7912983947e28b2d808ff70e185c99c851267d93
cp -r "$SCRATCH/template/gradlew" "$SCRATCH/template/gradlew.bat" "$SCRATCH/template/gradle" .
chmod +x gradlew
grep distributionUrl gradle/wrapper/gradle-wrapper.properties
```

Expected: `distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.1-bin.zip`

- [ ] **Step 2: Write `settings.gradle`**

```groovy
pluginManagement {
	repositories {
		maven {
			name = 'Fabric'
			url = 'https://maven.fabricmc.net/'
		}
		mavenCentral()
		gradlePluginPortal()
	}
}

rootProject.name = 'title-narrator'
```

- [ ] **Step 3: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx1G
org.gradle.parallel=true
# IntelliJ IDEA is not yet fully compatible with configuration cache, see: https://github.com/FabricMC/fabric-loom/issues/1349
org.gradle.configuration-cache=false

# Fabric Properties (check on https://fabricmc.net/develop)
minecraft_version=26.2
loader_version=0.19.5
loom_version=1.17-SNAPSHOT

# Mod Properties
version=0.1.0
group=dev.samsside

# Dependencies
fabric_api_version=0.161.0+26.2
yacl_version=3.9.7+26.2-fabric
modmenu_version=20.0.2
```

- [ ] **Step 4: Write `build.gradle`**

```groovy
plugins {
	id 'net.fabricmc.fabric-loom' version "${loom_version}"
}

repositories {
	maven {
		name = 'isXander'
		url = 'https://maven.isxander.dev/releases'
	}
	maven {
		name = 'TerraformersMC'
		url = 'https://maven.terraformersmc.com/releases'
	}
}

loom {
	splitEnvironmentSourceSets()

	mods {
		"titlenarrator" {
			sourceSet sourceSets.main
			sourceSet sourceSets.client
		}
	}
}

fabricApi {
	configureTests {
		createSourceSet = true
		modId = "titlenarrator-gametest"
		enableGameTests = false
		enableClientGameTests = true
		eula = true
	}
}

dependencies {
	minecraft "com.mojang:minecraft:${project.minecraft_version}"
	implementation "net.fabricmc:fabric-loader:${project.loader_version}"
	implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"

	// Optional config screen: compiled against, present in dev runs, never required at runtime.
	compileOnly "dev.isxander:yet-another-config-lib:${project.yacl_version}"
	compileOnly "com.terraformersmc:modmenu:${project.modmenu_version}"
	localRuntime "dev.isxander:yet-another-config-lib:${project.yacl_version}"
	localRuntime "com.terraformersmc:modmenu:${project.modmenu_version}"

	testImplementation "net.fabricmc:fabric-loader-junit:${project.loader_version}"
}

processResources {
	def version = project.version
	inputs.property "version", version

	filesMatching("fabric.mod.json") {
		expand "version": version
	}
}

tasks.withType(JavaCompile).configureEach {
	it.options.release = 25
}

java {
	withSourcesJar()

	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
}

jar {
	def projectName = project.name
	inputs.property "projectName", projectName

	from("LICENSE") {
		rename { "${it}_$projectName" }
	}
}

test {
	useJUnitPlatform()
}
```

- [ ] **Step 5: Write `.gitignore`**

```gitignore
# gradle
.gradle/
build/
out/
classes/

# idea
.idea/
*.iml
*.ipr
*.iws

# vscode
.settings/
.vscode/
bin/
.classpath
.project

# macos
*.DS_Store

# windows download markers
*:Zone.Identifier

# fabric
run/

# java
hs_err_*.log
replay_*.log
*.hprof
*.jfr
```

- [ ] **Step 6: Write `LICENSE` (MIT)**

```text
MIT License

Copyright (c) 2026 SamsSide

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

- [ ] **Step 7: Write `src/main/resources/fabric.mod.json`**

```json
{
	"schemaVersion": 1,
	"id": "titlenarrator",
	"version": "${version}",
	"name": "Title Narrator",
	"description": "Reads on-screen titles and subtitles aloud using Minecraft's narrator.",
	"authors": [
		"SamsSide"
	],
	"license": "MIT",
	"environment": "client",
	"entrypoints": {
		"client": [
			"dev.samsside.titlenarrator.TitleNarratorClient"
		]
	},
	"mixins": [
		{
			"config": "titlenarrator.client.mixins.json",
			"environment": "client"
		}
	],
	"depends": {
		"fabricloader": ">=0.19.5",
		"minecraft": "~26.2",
		"java": ">=25",
		"fabric-api": "*"
	},
	"suggests": {
		"yet_another_config_lib_v3": "*",
		"modmenu": "*"
	}
}
```

- [ ] **Step 8: Write `src/client/resources/titlenarrator.client.mixins.json`** (mixin list filled in Tasks 7–8)

```json
{
	"required": true,
	"package": "dev.samsside.titlenarrator.mixin",
	"compatibilityLevel": "JAVA_25",
	"client": [],
	"injectors": {
		"defaultRequire": 1
	},
	"overwrites": {
		"requireAnnotations": true
	}
}
```

- [ ] **Step 9: Write the placeholder initializer `src/client/java/dev/samsside/titlenarrator/TitleNarratorClient.java`**

```java
package dev.samsside.titlenarrator;

import net.fabricmc.api.ClientModInitializer;

public final class TitleNarratorClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
	}
}
```

- [ ] **Step 10: Build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL` (first run downloads Minecraft 26.2; allow several minutes). Then:

Run: `unzip -l build/libs/title-narrator-0.1.0.jar | grep -E "fabric.mod.json|mixins.json|TitleNarratorClient"`
Expected: all three entries listed.

- [ ] **Step 11: Commit**

```bash
git add settings.gradle gradle.properties build.gradle .gitignore LICENSE gradlew gradlew.bat gradle src
git commit -m "build: scaffold Fabric 26.2 client-only project"
```

---

### Task 2: TextComposer

**Files:**
- Create: `src/client/java/dev/samsside/titlenarrator/core/TextComposer.java`
- Test: `src/test/java/dev/samsside/titlenarrator/core/TextComposerTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `public static String TextComposer.compose(@Nullable String title, @Nullable String subtitle)` — strips both; returns `""` if both blank, the non-blank one if only one is present, otherwise `title + ". " + subtitle` (or `title + " " + subtitle` when the title already ends in `.`, `!` or `?`).

- [ ] **Step 1: Write the failing test**

```java
package dev.samsside.titlenarrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextComposerTest {
	@Test
	void titleOnly() {
		assertEquals("Hello", TextComposer.compose("Hello", null));
	}

	@Test
	void titleAndSubtitleJoinedWithFullStop() {
		assertEquals("Hello. World", TextComposer.compose("Hello", "World"));
	}

	@Test
	void blankSubtitleIsDropped() {
		assertEquals("Hello", TextComposer.compose("Hello", "   "));
	}

	@Test
	void titleEndingInPunctuationIsNotDoubled() {
		assertEquals("Victory! Well done", TextComposer.compose("Victory!", "Well done"));
		assertEquals("Ready? Go", TextComposer.compose("Ready?", "Go"));
		assertEquals("The end. Thanks", TextComposer.compose("The end.", "Thanks"));
	}

	@Test
	void blankTitleFallsBackToSubtitle() {
		assertEquals("World", TextComposer.compose("", "World"));
		assertEquals("World", TextComposer.compose(null, "World"));
	}

	@Test
	void bothBlankIsEmpty() {
		assertEquals("", TextComposer.compose(" ", null));
		assertEquals("", TextComposer.compose(null, null));
	}

	@Test
	void surroundingWhitespaceIsStripped() {
		assertEquals("Hello. World", TextComposer.compose("  Hello  ", " World "));
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.samsside.titlenarrator.core.TextComposerTest'`
Expected: FAIL — compilation error `cannot find symbol: variable TextComposer`.

- [ ] **Step 3: Implement**

```java
package dev.samsside.titlenarrator.core;

import org.jspecify.annotations.Nullable;

/** Joins a title and an optional subtitle into a single utterance. */
public final class TextComposer {
	private TextComposer() {
	}

	public static String compose(@Nullable String title, @Nullable String subtitle) {
		String t = title == null ? "" : title.strip();
		String s = subtitle == null ? "" : subtitle.strip();
		if (t.isEmpty()) {
			return s;
		}
		if (s.isEmpty()) {
			return t;
		}
		char last = t.charAt(t.length() - 1);
		String separator = last == '.' || last == '!' || last == '?' ? " " : ". ";
		return t + separator + s;
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'dev.samsside.titlenarrator.core.TextComposerTest'`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/client/java/dev/samsside/titlenarrator/core/TextComposer.java src/test/java/dev/samsside/titlenarrator/core/TextComposerTest.java
git commit -m "feat: compose title and subtitle into one utterance"
```

---

### Task 3: TextSanitiser

**Files:**
- Create: `src/client/java/dev/samsside/titlenarrator/core/TextSanitiser.java`
- Test: `src/test/java/dev/samsside/titlenarrator/core/TextSanitiserTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `public static String TextSanitiser.sanitise(String input)` — never null; may return `""`.

Rules, in order:
1. Remove legacy formatting pairs `§x` (any character after `§`).
2. Unicode NFKC normalisation (fullwidth, mathematical bold/italic, superscripts, ligatures → plain).
3. Map small capitals `ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫꞯʀꜱᴛᴜᴠᴡʏᴢ` → `abcdefghijklmnopqqrstuvwyz` (`ǫ` is what small-caps generators use for q; there is no small-cap x, generators use a plain `x`).
4. Map smart quotes/dashes to ASCII (`‘’‚`→`'`, `“”„`→`"`, `–—‐‑−`→`-`).
5. Keep letters, digits, whitespace, combining marks (except variation selectors), and `.,!?'"-:;()&%$/+`; every other code point becomes a space.
6. Collapse whitespace, trim, remove a space directly before `.,!?:;`.

- [ ] **Step 1: Write the failing test**

```java
package dev.samsside.titlenarrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextSanitiserTest {
	@Test
	void plainTextIsUnchanged() {
		assertEquals("Hello, World!", TextSanitiser.sanitise("Hello, World!"));
		assertEquals("Chapter 1: The End", TextSanitiser.sanitise("Chapter 1: The End"));
		assertEquals("Player's Base (PvP) & 50% off $5 1/2 +1",
				TextSanitiser.sanitise("Player's Base (PvP) & 50% off $5 1/2 +1"));
	}

	@Test
	void smallCapsBecomeAscii() {
		assertEquals("crafted", TextSanitiser.sanitise("ᴄʀᴀꜰᴛᴇᴅ"));
		assertEquals("abcdefghijklmnopqrstuvwxyz", TextSanitiser.sanitise("ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ"));
		assertEquals("q", TextSanitiser.sanitise("ꞯ"));
	}

	@Test
	void decorativeSymbolsAndEmojiAreRemoved() {
		assertEquals("Welcome", TextSanitiser.sanitise("★ Welcome ★"));
		assertEquals("Level Up!", TextSanitiser.sanitise("Level Up! 🎉"));
		assertEquals("Wave 3 Boss", TextSanitiser.sanitise("Wave 3 » Boss"));
		assertEquals("ARENA", TextSanitiser.sanitise("=== ARENA ==="));
		assertEquals("Boss", TextSanitiser.sanitise("<Boss>"));
		assertEquals("Hello!", TextSanitiser.sanitise("Hello ★!"));
	}

	@Test
	void symbolOnlyTextBecomesEmpty() {
		assertEquals("", TextSanitiser.sanitise("★★★"));
		assertEquals("", TextSanitiser.sanitise("❤️"));
		assertEquals("", TextSanitiser.sanitise("***"));
	}

	@Test
	void privateUseGlyphsAreRemoved() {
		assertEquals("Custom", TextSanitiser.sanitise(" Custom"));
		assertEquals("", TextSanitiser.sanitise(""));
	}

	@Test
	void legacyFormattingCodesAreStripped() {
		assertEquals("Gold", TextSanitiser.sanitise("§6§lGold"));
		assertEquals("Red and Blue", TextSanitiser.sanitise("§cRed§r and §9Blue"));
		assertEquals("A", TextSanitiser.sanitise("A§"));
	}

	@Test
	void compatibilityFormsAreNormalised() {
		assertEquals("Fullwidth", TextSanitiser.sanitise("Ｆｕｌｌｗｉｄｔｈ"));
		assertEquals("Bold", TextSanitiser.sanitise("𝐁𝐨𝐥𝐝"));
		assertEquals("Café", TextSanitiser.sanitise("Café"));
	}

	@Test
	void smartPunctuationBecomesAscii() {
		assertEquals("It's \"quoted\" - ok", TextSanitiser.sanitise("It’s “quoted” – ok"));
	}

	@Test
	void whitespaceIsCollapsedAndTrimmed() {
		assertEquals("lots of space", TextSanitiser.sanitise("  lots \t  of\n space "));
	}

	@Test
	void underscoresBecomeSpaces() {
		assertEquals("Player Name", TextSanitiser.sanitise("Player_Name"));
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.samsside.titlenarrator.core.TextSanitiserTest'`
Expected: FAIL — compilation error `cannot find symbol: variable TextSanitiser`.

- [ ] **Step 3: Implement**

```java
package dev.samsside.titlenarrator.core;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Turns stylised on-screen text into something a text-to-speech voice reads naturally. */
public final class TextSanitiser {
	/** Unicode small capitals, plus 'ǫ' which small-caps generators use for q. There is no small-cap x. */
	private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫꞯʀꜱᴛᴜᴠᴡʏᴢ";
	private static final String SMALL_CAPS_ASCII = "abcdefghijklmnopqqrstuvwyz";
	private static final String KEPT_PUNCTUATION = ".,!?'\"-:;()&%$/+";
	private static final Pattern LEGACY_FORMATTING = Pattern.compile("§.", Pattern.DOTALL);
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");
	private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile(" ([.,!?:;])");

	private TextSanitiser() {
	}

	public static String sanitise(String input) {
		String text = LEGACY_FORMATTING.matcher(input).replaceAll("");
		text = Normalizer.normalize(text, Normalizer.Form.NFKC);
		StringBuilder out = new StringBuilder(text.length());
		text.codePoints().forEach(cp -> out.appendCodePoint(map(cp)));
		String collapsed = WHITESPACE.matcher(out).replaceAll(" ").strip();
		return SPACE_BEFORE_PUNCTUATION.matcher(collapsed).replaceAll("$1");
	}

	private static int map(int cp) {
		int smallCap = SMALL_CAPS.indexOf(cp);
		if (smallCap >= 0) {
			return SMALL_CAPS_ASCII.charAt(smallCap);
		}
		return switch (cp) {
			case '‘', '’', '‚' -> '\'';
			case '“', '”', '„' -> '"';
			case '–', '—', '‐', '‑', '−' -> '-';
			default -> isSpoken(cp) ? cp : ' ';
		};
	}

	private static boolean isSpoken(int cp) {
		if (Character.isLetterOrDigit(cp) || Character.isWhitespace(cp) || KEPT_PUNCTUATION.indexOf(cp) >= 0) {
			return true;
		}
		int type = Character.getType(cp);
		boolean combiningMark = type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK;
		boolean variationSelector = (cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0xE0100 && cp <= 0xE01EF);
		return combiningMark && !variationSelector;
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'dev.samsside.titlenarrator.core.TextSanitiserTest'`
Expected: PASS (10 tests). If a case fails, fix the implementation, not the expectation — the expectations are the spec.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/dev/samsside/titlenarrator/core/TextSanitiser.java src/test/java/dev/samsside/titlenarrator/core/TextSanitiserTest.java
git commit -m "feat: sanitise small caps, symbols and formatting codes for TTS"
```

---

### Task 4: Deduper

**Files:**
- Create: `src/client/java/dev/samsside/titlenarrator/core/Deduper.java`
- Test: `src/test/java/dev/samsside/titlenarrator/core/DeduperTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `public Deduper(LongSupplier clockMillis, boolean sliding)`; `public boolean shouldSpeak(String text, long windowMs)` — `false` if `text` equals the last spoken text and fewer than `windowMs` ms have passed since the reference time. The reference time is the last *speak* (fixed) or the last *sighting* (sliding, used for action bars that are re-sent every tick). `public void reset()`.

- [ ] **Step 1: Write the failing test**

```java
package dev.samsside.titlenarrator.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DeduperTest {
	private final AtomicLong clock = new AtomicLong(10_000);

	@Test
	void firstOccurrenceIsSpoken() {
		assertTrue(new Deduper(clock::get, false).shouldSpeak("Hello", 3000));
	}

	@Test
	void fixedWindowSuppressesRepeatsUntilWindowElapses() {
		Deduper deduper = new Deduper(clock::get, false);
		assertTrue(deduper.shouldSpeak("Spam", 3000));
		clock.addAndGet(2999);
		assertFalse(deduper.shouldSpeak("Spam", 3000));
		clock.addAndGet(1);
		assertTrue(deduper.shouldSpeak("Spam", 3000));
	}

	@Test
	void fixedWindowDoesNotExtendOnSuppressedRepeats() {
		Deduper deduper = new Deduper(clock::get, false);
		assertTrue(deduper.shouldSpeak("Spam", 3000));
		clock.addAndGet(2000);
		assertFalse(deduper.shouldSpeak("Spam", 3000));
		clock.addAndGet(1000);
		assertTrue(deduper.shouldSpeak("Spam", 3000));
	}

	@Test
	void slidingWindowExtendsWhileTextKeepsArriving() {
		Deduper deduper = new Deduper(clock::get, true);
		assertTrue(deduper.shouldSpeak("Bar", 3000));
		clock.addAndGet(2000);
		assertFalse(deduper.shouldSpeak("Bar", 3000));
		clock.addAndGet(2000);
		assertFalse(deduper.shouldSpeak("Bar", 3000));
		clock.addAndGet(3000);
		assertTrue(deduper.shouldSpeak("Bar", 3000));
	}

	@Test
	void differentTextIsAlwaysSpoken() {
		Deduper deduper = new Deduper(clock::get, false);
		assertTrue(deduper.shouldSpeak("A", 3000));
		assertTrue(deduper.shouldSpeak("B", 3000));
		assertTrue(deduper.shouldSpeak("A", 3000));
	}

	@Test
	void zeroWindowDisablesSuppression() {
		Deduper deduper = new Deduper(clock::get, false);
		assertTrue(deduper.shouldSpeak("Spam", 0));
		assertTrue(deduper.shouldSpeak("Spam", 0));
	}

	@Test
	void resetForgetsLastText() {
		Deduper deduper = new Deduper(clock::get, false);
		assertTrue(deduper.shouldSpeak("Spam", 3000));
		deduper.reset();
		assertTrue(deduper.shouldSpeak("Spam", 3000));
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.samsside.titlenarrator.core.DeduperTest'`
Expected: FAIL — compilation error `cannot find symbol: class Deduper`.

- [ ] **Step 3: Implement**

```java
package dev.samsside.titlenarrator.core;

import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/** Suppresses speaking the same text twice within a time window. */
public final class Deduper {
	private final LongSupplier clockMillis;
	private final boolean sliding;
	private @Nullable String lastText;
	private long lastTime;

	/**
	 * @param sliding if true, every suppressed repeat restarts the window (for text re-sent every tick);
	 *                if false, the window is measured from when the text was last spoken
	 */
	public Deduper(LongSupplier clockMillis, boolean sliding) {
		this.clockMillis = clockMillis;
		this.sliding = sliding;
	}

	public boolean shouldSpeak(String text, long windowMs) {
		long now = clockMillis.getAsLong();
		if (text.equals(lastText) && now - lastTime < windowMs) {
			if (sliding) {
				lastTime = now;
			}
			return false;
		}
		lastText = text;
		lastTime = now;
		return true;
	}

	public void reset() {
		lastText = null;
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'dev.samsside.titlenarrator.core.DeduperTest'`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/client/java/dev/samsside/titlenarrator/core/Deduper.java src/test/java/dev/samsside/titlenarrator/core/DeduperTest.java
git commit -m "feat: add repeat-suppression deduper with fixed and sliding windows"
```

---

### Task 5: TitleNarratorConfig

**Files:**
- Create: `src/client/java/dev/samsside/titlenarrator/config/TitleNarratorConfig.java`
- Test: `src/test/java/dev/samsside/titlenarrator/config/TitleNarratorConfigTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `public final class TitleNarratorConfig` with public mutable fields `boolean enabled = true, narrateTitles = true, narrateSubtitles = true, narrateActionBar = false, lateSubtitles = true, sanitiseText = true, interrupt = true, bypassNarratorSetting = false; long dedupeWindowMs = 3000;` constant `public static final long MAX_DEDUPE_WINDOW_MS = 10_000;` methods `public static TitleNarratorConfig load(Path path)`, `public void save(Path path)`, `public void copyFrom(TitleNarratorConfig other)`.

Behaviour of `load`: missing file → defaults, written to disk. Unreadable / invalid JSON / empty / wrong types → warn, move the original to `<name>.bak` (replacing an older backup), write and return defaults. Missing fields keep their defaults. `dedupeWindowMs` is clamped to `[0, MAX_DEDUPE_WINDOW_MS]`. `save` creates parent directories and logs (never throws) on I/O failure.

- [ ] **Step 1: Write the failing test**

```java
package dev.samsside.titlenarrator.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TitleNarratorConfigTest {
	private static final Gson GSON = new Gson();

	@TempDir
	Path dir;

	private Path file() {
		return dir.resolve("titlenarrator.json");
	}

	private static void assertDefaults(TitleNarratorConfig config) {
		assertEquals(GSON.toJson(new TitleNarratorConfig()), GSON.toJson(config));
	}

	@Test
	void defaultsMatchDesign() {
		TitleNarratorConfig config = new TitleNarratorConfig();
		assertTrue(config.enabled);
		assertTrue(config.narrateTitles);
		assertTrue(config.narrateSubtitles);
		assertFalse(config.narrateActionBar);
		assertTrue(config.lateSubtitles);
		assertTrue(config.sanitiseText);
		assertEquals(3000, config.dedupeWindowMs);
		assertTrue(config.interrupt);
		assertFalse(config.bypassNarratorSetting);
	}

	@Test
	void missingFileCreatesDefaults() {
		TitleNarratorConfig config = TitleNarratorConfig.load(file());
		assertDefaults(config);
		assertTrue(Files.exists(file()));
	}

	@Test
	void savedValuesRoundTrip() {
		TitleNarratorConfig config = new TitleNarratorConfig();
		config.narrateActionBar = true;
		config.interrupt = false;
		config.dedupeWindowMs = 1500;
		config.save(file());

		TitleNarratorConfig loaded = TitleNarratorConfig.load(file());
		assertTrue(loaded.narrateActionBar);
		assertFalse(loaded.interrupt);
		assertEquals(1500, loaded.dedupeWindowMs);
	}

	@Test
	void corruptFileFallsBackToDefaultsAndKeepsBackup() throws IOException {
		Files.writeString(file(), "{not json");
		TitleNarratorConfig config = TitleNarratorConfig.load(file());
		assertDefaults(config);
		assertEquals("{not json", Files.readString(dir.resolve("titlenarrator.json.bak")));
		assertDefaults(TitleNarratorConfig.load(file()));
	}

	@Test
	void emptyFileFallsBackToDefaults() throws IOException {
		Files.writeString(file(), "");
		assertDefaults(TitleNarratorConfig.load(file()));
	}

	@Test
	void wrongTypeFallsBackToDefaults() throws IOException {
		Files.writeString(file(), "{\"dedupeWindowMs\": \"soon\"}");
		assertDefaults(TitleNarratorConfig.load(file()));
	}

	@Test
	void missingFieldsKeepDefaults() throws IOException {
		Files.writeString(file(), "{\"narrateActionBar\": true}");
		TitleNarratorConfig config = TitleNarratorConfig.load(file());
		assertTrue(config.narrateActionBar);
		assertTrue(config.enabled);
		assertEquals(3000, config.dedupeWindowMs);
	}

	@Test
	void dedupeWindowIsClamped() throws IOException {
		Files.writeString(file(), "{\"dedupeWindowMs\": 999999}");
		assertEquals(TitleNarratorConfig.MAX_DEDUPE_WINDOW_MS, TitleNarratorConfig.load(file()).dedupeWindowMs);
		Files.writeString(file(), "{\"dedupeWindowMs\": -5}");
		assertEquals(0, TitleNarratorConfig.load(file()).dedupeWindowMs);
	}

	@Test
	void saveCreatesParentDirectories() {
		Path nested = dir.resolve("a/b/titlenarrator.json");
		new TitleNarratorConfig().save(nested);
		assertTrue(Files.exists(nested));
	}

	@Test
	void copyFromCopiesEveryField() {
		TitleNarratorConfig source = new TitleNarratorConfig();
		source.enabled = false;
		source.narrateTitles = false;
		source.narrateSubtitles = false;
		source.narrateActionBar = true;
		source.lateSubtitles = false;
		source.sanitiseText = false;
		source.dedupeWindowMs = 42;
		source.interrupt = false;
		source.bypassNarratorSetting = true;

		TitleNarratorConfig target = new TitleNarratorConfig();
		target.copyFrom(source);
		assertEquals(GSON.toJson(source), GSON.toJson(target));
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.samsside.titlenarrator.config.TitleNarratorConfigTest'`
Expected: FAIL — compilation error `cannot find symbol: class TitleNarratorConfig`.

- [ ] **Step 3: Implement**

```java
package dev.samsside.titlenarrator.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** User settings, stored as JSON in {@code config/titlenarrator.json}. Works without any config library. */
public final class TitleNarratorConfig {
	public static final long MAX_DEDUPE_WINDOW_MS = 10_000;

	private static final Logger LOGGER = LoggerFactory.getLogger("titlenarrator");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public boolean enabled = true;
	public boolean narrateTitles = true;
	public boolean narrateSubtitles = true;
	public boolean narrateActionBar = false;
	public boolean lateSubtitles = true;
	public boolean sanitiseText = true;
	public long dedupeWindowMs = 3000;
	public boolean interrupt = true;
	public boolean bypassNarratorSetting = false;

	public static TitleNarratorConfig load(Path path) {
		if (!Files.exists(path)) {
			TitleNarratorConfig defaults = new TitleNarratorConfig();
			defaults.save(path);
			return defaults;
		}
		try (Reader reader = Files.newBufferedReader(path)) {
			TitleNarratorConfig config = GSON.fromJson(reader, TitleNarratorConfig.class);
			if (config == null) {
				throw new JsonParseException("file is empty");
			}
			config.clamp();
			return config;
		} catch (IOException | JsonParseException e) {
			LOGGER.warn("[Title Narrator] Could not read {} ({}); using defaults", path, e.getMessage());
			backUp(path);
			TitleNarratorConfig defaults = new TitleNarratorConfig();
			defaults.save(path);
			return defaults;
		}
	}

	public void save(Path path) {
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(this));
		} catch (IOException e) {
			LOGGER.warn("[Title Narrator] Could not save {}", path, e);
		}
	}

	public void copyFrom(TitleNarratorConfig other) {
		enabled = other.enabled;
		narrateTitles = other.narrateTitles;
		narrateSubtitles = other.narrateSubtitles;
		narrateActionBar = other.narrateActionBar;
		lateSubtitles = other.lateSubtitles;
		sanitiseText = other.sanitiseText;
		dedupeWindowMs = other.dedupeWindowMs;
		interrupt = other.interrupt;
		bypassNarratorSetting = other.bypassNarratorSetting;
	}

	private void clamp() {
		dedupeWindowMs = Math.clamp(dedupeWindowMs, 0, MAX_DEDUPE_WINDOW_MS);
	}

	private static void backUp(Path path) {
		try {
			Files.move(path, path.resolveSibling(path.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			LOGGER.warn("[Title Narrator] Could not back up {}", path, e);
		}
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'dev.samsside.titlenarrator.config.TitleNarratorConfigTest'`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add src/client/java/dev/samsside/titlenarrator/config/TitleNarratorConfig.java src/test/java/dev/samsside/titlenarrator/config/TitleNarratorConfigTest.java
git commit -m "feat: add JSON config with defaults, clamping and corrupt-file backup"
```

---

### Task 6: Speaker, LogOnce and the TitleNarrator pipeline

**Files:**
- Create: `src/client/java/dev/samsside/titlenarrator/speech/Speaker.java`
- Create: `src/client/java/dev/samsside/titlenarrator/speech/LogOnce.java`
- Create: `src/client/java/dev/samsside/titlenarrator/core/TitleNarrator.java`
- Test: `src/test/java/dev/samsside/titlenarrator/speech/LogOnceTest.java`
- Test: `src/test/java/dev/samsside/titlenarrator/core/TitleNarratorTest.java`

**Interfaces:**
- Consumes: `TextComposer.compose` (Task 2), `TextSanitiser.sanitise` (Task 3), `Deduper` (Task 4), `TitleNarratorConfig` fields (Task 5).
- Produces:
  - `@FunctionalInterface public interface Speaker { void speak(String text, boolean interrupt); }`
  - `public final class LogOnce { public static boolean warn(String key, String message, Object... args); public static boolean info(String key, String message, Object... args); }` — returns `true` if it logged (first time for `key`).
  - `public final class TitleNarrator` with `public static final int LATE_SUBTITLE_DELAY_TICKS = 2;`, constructor `TitleNarrator(Supplier<TitleNarratorConfig> config, Supplier<Speaker> speaker, LongSupplier clockMillis)`, and methods `onTitle(String title, @Nullable String currentSubtitle)`, `onSubtitle(String subtitle, boolean titleVisible)`, `onActionBar(String message)`, `onClientTick()`, `reset()`. All called on the client thread.

- [ ] **Step 1: Write the failing tests**

`src/test/java/dev/samsside/titlenarrator/speech/LogOnceTest.java`:

```java
package dev.samsside.titlenarrator.speech;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogOnceTest {
	@Test
	void logsEachKeyOnlyOnce() {
		assertTrue(LogOnce.warn("logonce-test-a", "first {}", 1));
		assertFalse(LogOnce.warn("logonce-test-a", "second {}", 2));
		assertTrue(LogOnce.info("logonce-test-b", "other key"));
	}
}
```

`src/test/java/dev/samsside/titlenarrator/core/TitleNarratorTest.java`:

```java
package dev.samsside.titlenarrator.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.samsside.titlenarrator.config.TitleNarratorConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TitleNarratorTest {
	private final List<String> spoken = new ArrayList<>();
	private final List<Boolean> interrupts = new ArrayList<>();
	private final AtomicLong clock = new AtomicLong(1_000_000);
	private final TitleNarratorConfig config = new TitleNarratorConfig();
	private final TitleNarrator narrator = new TitleNarrator(() -> config, () -> (text, interrupt) -> {
		spoken.add(text);
		interrupts.add(interrupt);
	}, clock::get);

	private void ticks(int count) {
		for (int i = 0; i < count; i++) {
			narrator.onClientTick();
		}
	}

	@Test
	void speaksTitleWithSubtitle() {
		narrator.onTitle("Hello", "World");
		assertEquals(List.of("Hello. World"), spoken);
	}

	@Test
	void speaksTitleAlone() {
		narrator.onTitle("Hello", null);
		assertEquals(List.of("Hello"), spoken);
	}

	@Test
	void blankTitleIsSilent() {
		narrator.onTitle("", "World");
		narrator.onTitle("   ", null);
		assertEquals(List.of(), spoken);
	}

	@Test
	void subtitleOmittedWhenSubtitlesDisabled() {
		config.narrateSubtitles = false;
		narrator.onTitle("Hello", "World");
		assertEquals(List.of("Hello"), spoken);
	}

	@Test
	void titlesDisabledIsSilent() {
		config.narrateTitles = false;
		narrator.onTitle("Hello", "World");
		assertEquals(List.of(), spoken);
	}

	@Test
	void masterSwitchSilencesEverything() {
		config.enabled = false;
		config.narrateActionBar = true;
		narrator.onTitle("Hello", null);
		narrator.onActionBar("Bar");
		narrator.onSubtitle("Late", true);
		ticks(TitleNarrator.LATE_SUBTITLE_DELAY_TICKS + 1);
		assertEquals(List.of(), spoken);
	}

	@Test
	void repeatedTitleSpokenOncePerWindow() {
		narrator.onTitle("Spam", null);
		narrator.onTitle("Spam", null);
		narrator.onTitle("Spam", null);
		assertEquals(List.of("Spam"), spoken);
		clock.addAndGet(config.dedupeWindowMs);
		narrator.onTitle("Spam", null);
		assertEquals(List.of("Spam", "Spam"), spoken);
	}

	@Test
	void sanitisesWhenEnabled() {
		narrator.onTitle("ᴄʀᴀꜰᴛᴇᴅ ★", null);
		assertEquals(List.of("crafted"), spoken);
	}

	@Test
	void leavesTextAloneWhenSanitisingDisabled() {
		config.sanitiseText = false;
		narrator.onTitle("ᴄʀᴀꜰᴛᴇᴅ ★", null);
		assertEquals(List.of("ᴄʀᴀꜰᴛᴇᴅ ★"), spoken);
	}

	@Test
	void symbolOnlyTitleIsSilent() {
		narrator.onTitle("★★★", null);
		assertEquals(List.of(), spoken);
	}

	@Test
	void symbolOnlyTitleWithSubtitleSpeaksSubtitle() {
		narrator.onTitle("★★★", "Boss");
		assertEquals(List.of("Boss"), spoken);
	}

	@Test
	void passesInterruptSetting() {
		narrator.onTitle("One", null);
		config.interrupt = false;
		narrator.onTitle("Two", null);
		assertEquals(List.of(true, false), interrupts);
	}

	@Test
	void lateSubtitleIsSpokenAfterDelay() {
		narrator.onTitle("Boss", null);
		narrator.onSubtitle("Phase 2", true);
		ticks(TitleNarrator.LATE_SUBTITLE_DELAY_TICKS - 1);
		assertEquals(List.of("Boss"), spoken);
		ticks(1);
		assertEquals(List.of("Boss", "Phase 2"), spoken);
		ticks(5);
		assertEquals(List.of("Boss", "Phase 2"), spoken);
	}

	@Test
	void lateSubtitleIsCancelledByFollowingTitle() {
		narrator.onTitle("First", null);
		narrator.onSubtitle("Second sub", true);
		narrator.onTitle("Second", "Second sub");
		ticks(TitleNarrator.LATE_SUBTITLE_DELAY_TICKS + 3);
		assertEquals(List.of("First", "Second. Second sub"), spoken);
	}

	@Test
	void subtitleWithoutVisibleTitleWaitsForTitle() {
		narrator.onSubtitle("World", false);
		ticks(TitleNarrator.LATE_SUBTITLE_DELAY_TICKS + 1);
		assertEquals(List.of(), spoken);
	}

	@Test
	void lateSubtitlesCanBeDisabled() {
		config.lateSubtitles = false;
		narrator.onSubtitle("Phase 2", true);
		ticks(TitleNarrator.LATE_SUBTITLE_DELAY_TICKS + 1);
		assertEquals(List.of(), spoken);
	}

	@Test
	void actionBarOffByDefault() {
		narrator.onActionBar("Bar");
		assertEquals(List.of(), spoken);
	}

	@Test
	void actionBarRepeatsSuppressedWhileStillBeingSent() {
		config.narrateActionBar = true;
		narrator.onActionBar("Bar");
		for (int i = 0; i < 5; i++) {
			clock.addAndGet(1000);
			narrator.onActionBar("Bar");
		}
		assertEquals(List.of("Bar"), spoken);
		clock.addAndGet(config.dedupeWindowMs);
		narrator.onActionBar("Bar");
		assertEquals(List.of("Bar", "Bar"), spoken);
	}

	@Test
	void blankActionBarIsSilent() {
		config.narrateActionBar = true;
		narrator.onActionBar("  ");
		assertEquals(List.of(), spoken);
	}

	@Test
	void speakerFailureIsSwallowed() {
		TitleNarrator failing = new TitleNarrator(() -> config, () -> (text, interrupt) -> {
			throw new IllegalStateException("native TTS exploded");
		}, clock::get);
		assertDoesNotThrow(() -> failing.onTitle("Hello", null));
	}

	@Test
	void resetAllowsImmediateRepeatAndDropsPendingSubtitle() {
		narrator.onTitle("Spam", null);
		narrator.onSubtitle("Pending", true);
		narrator.reset();
		narrator.onTitle("Spam", null);
		ticks(TitleNarrator.LATE_SUBTITLE_DELAY_TICKS + 1);
		assertEquals(List.of("Spam", "Spam"), spoken);
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.samsside.titlenarrator.core.TitleNarratorTest' --tests 'dev.samsside.titlenarrator.speech.LogOnceTest'`
Expected: FAIL — compilation errors for `TitleNarrator`, `LogOnce`.

- [ ] **Step 3: Implement `Speaker`**

```java
package dev.samsside.titlenarrator.speech;

/** Something that can say text out loud. */
@FunctionalInterface
public interface Speaker {
	/**
	 * @param interrupt true to cut off whatever is currently being spoken, false to queue after it
	 */
	void speak(String text, boolean interrupt);
}
```

- [ ] **Step 4: Implement `LogOnce`**

```java
package dev.samsside.titlenarrator.speech;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logs a message the first time its key is seen and ignores it for the rest of the session. */
public final class LogOnce {
	private static final Logger LOGGER = LoggerFactory.getLogger("titlenarrator");
	private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

	private LogOnce() {
	}

	public static boolean warn(String key, String message, Object... args) {
		if (!SEEN.add(key)) {
			return false;
		}
		LOGGER.warn(message, args);
		return true;
	}

	public static boolean info(String key, String message, Object... args) {
		if (!SEEN.add(key)) {
			return false;
		}
		LOGGER.info(message, args);
		return true;
	}
}
```

- [ ] **Step 5: Implement `TitleNarrator`**

```java
package dev.samsside.titlenarrator.core;

import dev.samsside.titlenarrator.config.TitleNarratorConfig;
import dev.samsside.titlenarrator.speech.LogOnce;
import dev.samsside.titlenarrator.speech.Speaker;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Decides what to say when the HUD shows a title, subtitle or action-bar message.
 * All methods must be called on the client thread.
 */
public final class TitleNarrator {
	/**
	 * Client ticks a subtitle that arrives while a title is on screen waits before being spoken on its own.
	 * A title arriving in that time cancels it (the usual "subtitle, then title" sequence).
	 */
	public static final int LATE_SUBTITLE_DELAY_TICKS = 2;

	private final Supplier<TitleNarratorConfig> config;
	private final Supplier<Speaker> speaker;
	private final Deduper titleDeduper;
	private final Deduper actionBarDeduper;
	private @Nullable String pendingLateSubtitle;
	private int pendingTicks;

	public TitleNarrator(Supplier<TitleNarratorConfig> config, Supplier<Speaker> speaker, LongSupplier clockMillis) {
		this.config = config;
		this.speaker = speaker;
		this.titleDeduper = new Deduper(clockMillis, false);
		this.actionBarDeduper = new Deduper(clockMillis, true);
	}

	/** Called after the HUD stores a new title; {@code currentSubtitle} is the subtitle it will show with it. */
	public void onTitle(String title, @Nullable String currentSubtitle) {
		pendingLateSubtitle = null;
		TitleNarratorConfig cfg = config.get();
		if (!cfg.enabled || !cfg.narrateTitles || title.isBlank()) {
			return;
		}
		String subtitle = cfg.narrateSubtitles ? clean(currentSubtitle, cfg) : null;
		speakIfNew(TextComposer.compose(clean(title, cfg), subtitle), titleDeduper, cfg);
	}

	/** Called before the HUD stores a new subtitle. */
	public void onSubtitle(String subtitle, boolean titleVisible) {
		TitleNarratorConfig cfg = config.get();
		if (!titleVisible || !cfg.enabled || !cfg.narrateSubtitles || !cfg.lateSubtitles || subtitle.isBlank()) {
			pendingLateSubtitle = null;
			return;
		}
		pendingLateSubtitle = subtitle;
		pendingTicks = LATE_SUBTITLE_DELAY_TICKS;
	}

	public void onActionBar(String message) {
		TitleNarratorConfig cfg = config.get();
		if (!cfg.enabled || !cfg.narrateActionBar) {
			return;
		}
		speakIfNew(clean(message, cfg), actionBarDeduper, cfg);
	}

	public void onClientTick() {
		if (pendingLateSubtitle == null || --pendingTicks > 0) {
			return;
		}
		String subtitle = pendingLateSubtitle;
		pendingLateSubtitle = null;
		TitleNarratorConfig cfg = config.get();
		if (cfg.enabled) {
			speakIfNew(clean(subtitle, cfg), titleDeduper, cfg);
		}
	}

	public void reset() {
		titleDeduper.reset();
		actionBarDeduper.reset();
		pendingLateSubtitle = null;
	}

	private static String clean(@Nullable String text, TitleNarratorConfig cfg) {
		if (text == null) {
			return "";
		}
		return cfg.sanitiseText ? TextSanitiser.sanitise(text) : text.strip();
	}

	private void speakIfNew(String text, Deduper deduper, TitleNarratorConfig cfg) {
		if (text.isBlank() || !deduper.shouldSpeak(text, cfg.dedupeWindowMs)) {
			return;
		}
		try {
			speaker.get().speak(text, cfg.interrupt);
		} catch (RuntimeException e) {
			LogOnce.warn("speak-failed", "[Title Narrator] Speaking failed; further failures will not be logged", e);
		}
	}
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests 'dev.samsside.titlenarrator.core.TitleNarratorTest' --tests 'dev.samsside.titlenarrator.speech.LogOnceTest'`
Expected: PASS (21 + 1 tests).

- [ ] **Step 7: Run the whole unit suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests from Tasks 2–6 pass.

- [ ] **Step 8: Commit**

```bash
git add src/client/java/dev/samsside/titlenarrator/speech src/client/java/dev/samsside/titlenarrator/core/TitleNarrator.java src/test/java/dev/samsside/titlenarrator/speech src/test/java/dev/samsside/titlenarrator/core/TitleNarratorTest.java
git commit -m "feat: add narration pipeline with late subtitles and action bar"
```

---

### Task 7: Real speakers and the GameNarrator accessor

**Files:**
- Create: `src/client/java/dev/samsside/titlenarrator/mixin/GameNarratorAccessor.java`
- Create: `src/client/java/dev/samsside/titlenarrator/speech/GameNarratorSpeaker.java`
- Create: `src/client/java/dev/samsside/titlenarrator/speech/DirectSpeaker.java`
- Modify: `src/client/resources/titlenarrator.client.mixins.json` (`"client": []` → `["GameNarratorAccessor"]`)

**Interfaces:**
- Consumes: `Speaker`, `LogOnce` (Task 6). Verified 26.2 API: `Minecraft#getNarrator()` → `GameNarrator` with `isActive()`, `saySystemNow(String)`, `saySystemQueued(Component)`; `Minecraft.options.narrator().get()` → `NarratorStatus` with `shouldNarrateSystem()`; private final field `GameNarrator.narrator` of type `com.mojang.text2speech.Narrator` with `active()`, `clear()`, `say(String, boolean, float)`; `Options#getFinalSoundSourceVolume(SoundSource.VOICE)`.
- Produces: `GameNarratorSpeaker implements Speaker` (respects the Narrator option), `DirectSpeaker implements Speaker` (bypass), `GameNarratorAccessor#titlenarrator$getNarrator()`, and `static void GameNarratorSpeaker.warnUnavailable()` shared by both.

These classes need a running Minecraft, so there is no JUnit test; they are exercised by the manual checklist in Task 12. The gate here is that the mixin config is valid and everything compiles.

- [ ] **Step 1: Write the accessor**

```java
package dev.samsside.titlenarrator.mixin;

import com.mojang.text2speech.Narrator;
import net.minecraft.client.GameNarrator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameNarrator.class)
public interface GameNarratorAccessor {
	@Accessor("narrator")
	Narrator titlenarrator$getNarrator();
}
```

- [ ] **Step 2: Register it in `titlenarrator.client.mixins.json`**

```json
	"client": [
		"GameNarratorAccessor"
	],
```

- [ ] **Step 3: Write `GameNarratorSpeaker`**

```java
package dev.samsside.titlenarrator.speech;

import net.minecraft.client.GameNarrator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.NarratorStatus;
import net.minecraft.network.chat.Component;

/** Speaks through the vanilla narrator, which stays silent unless the Narrator option is All or System. */
public final class GameNarratorSpeaker implements Speaker {
	@Override
	public void speak(String text, boolean interrupt) {
		Minecraft minecraft = Minecraft.getInstance();
		GameNarrator narrator = minecraft.getNarrator();
		if (!narrator.isActive()) {
			warnUnavailable();
			return;
		}
		NarratorStatus status = minecraft.options.narrator().get();
		if (!status.shouldNarrateSystem()) {
			LogOnce.info("narrator-option", "[Title Narrator] Narrator option is {}; titles are only spoken when it is "
					+ "All or System, or when 'Speak even when Narrator is Off' is enabled", status);
			return;
		}
		if (interrupt) {
			narrator.saySystemNow(text);
		} else {
			narrator.saySystemQueued(Component.literal(text));
		}
	}

	static void warnUnavailable() {
		LogOnce.warn("tts-unavailable", "[Title Narrator] Text-to-speech is unavailable on this system, so titles "
				+ "will not be spoken. On Linux, install the flite library (see README).");
	}
}
```

- [ ] **Step 4: Write `DirectSpeaker`**

```java
package dev.samsside.titlenarrator.speech;

import com.mojang.text2speech.Narrator;
import dev.samsside.titlenarrator.mixin.GameNarratorAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

/** Speaks through the underlying text-to-speech engine, ignoring the Narrator option. */
public final class DirectSpeaker implements Speaker {
	@Override
	public void speak(String text, boolean interrupt) {
		Minecraft minecraft = Minecraft.getInstance();
		Narrator tts = ((GameNarratorAccessor) minecraft.getNarrator()).titlenarrator$getNarrator();
		if (!tts.active()) {
			GameNarratorSpeaker.warnUnavailable();
			return;
		}
		if (interrupt) {
			tts.clear();
		}
		tts.say(text, interrupt, minecraft.options.getFinalSoundSourceVolume(SoundSource.VOICE));
	}
}
```

- [ ] **Step 5: Build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`; all unit tests still pass.

- [ ] **Step 6: Commit**

```bash
git add src/client/java/dev/samsside/titlenarrator/mixin/GameNarratorAccessor.java src/client/java/dev/samsside/titlenarrator/speech/GameNarratorSpeaker.java src/client/java/dev/samsside/titlenarrator/speech/DirectSpeaker.java src/client/resources/titlenarrator.client.mixins.json
git commit -m "feat: add vanilla-narrator and direct TTS speakers"
```

---

### Task 8: Hud mixin, client wiring and the title gametests

**Files:**
- Create: `src/client/java/dev/samsside/titlenarrator/mixin/HudMixin.java`
- Modify: `src/client/resources/titlenarrator.client.mixins.json` (add `"HudMixin"`)
- Modify: `src/client/java/dev/samsside/titlenarrator/TitleNarratorClient.java` (full implementation)
- Create: `src/gametest/resources/fabric.mod.json`
- Create: `src/gametest/java/dev/samsside/titlenarrator/gametest/RecordingSpeaker.java`
- Create: `src/gametest/java/dev/samsside/titlenarrator/gametest/TitleNarratorGameTest.java`

**Interfaces:**
- Consumes: `TitleNarrator` (Task 6), `TitleNarratorConfig` (Task 5), `GameNarratorSpeaker`, `DirectSpeaker` (Task 7). Verified 26.2 API: `net.minecraft.client.gui.Hud` methods `setTitle(Component)`, `setSubtitle(Component)`, `setOverlayMessage(Component, boolean)`; private fields `Component subtitle`, `int titleTime`. Gametest API `fabric-client-gametest-api-v1` 6.0.2: `ClientGameTestContext#worldBuilder().create()`, `TestSingleplayerContext#getServer()/#getConnection()`, `TestServerContext#runOnServer`, `TestServerConnection#waitForClientboundPackets()`, `ClientGameTestContext#waitTicks/runOnClient/computeOnClient`.
- Produces:
  - `TitleNarratorClient.config()` → `TitleNarratorConfig` (non-null after init), `TitleNarratorClient.narrator()` → `@Nullable TitleNarrator`, `TitleNarratorClient.saveConfig()`, `TitleNarratorClient.setTestOverrides(@Nullable Speaker speaker, @Nullable LongSupplier clockMillis)`.
  - Gametest helper `RecordingSpeaker` with `spoken()` and `clear()`, reused by Tasks 9–11.

- [ ] **Step 1: Write the gametest mod metadata `src/gametest/resources/fabric.mod.json`**

```json
{
	"schemaVersion": 1,
	"id": "titlenarrator-gametest",
	"version": "1.0.0",
	"name": "Title Narrator Game Tests",
	"environment": "client",
	"entrypoints": {
		"fabric-client-gametest": [
			"dev.samsside.titlenarrator.gametest.TitleNarratorGameTest"
		]
	},
	"depends": {
		"titlenarrator": "*"
	}
}
```

- [ ] **Step 2: Write `RecordingSpeaker`**

```java
package dev.samsside.titlenarrator.gametest;

import dev.samsside.titlenarrator.speech.Speaker;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records what would have been spoken; written on the client thread, read on the test thread. */
public final class RecordingSpeaker implements Speaker {
	private final List<String> spoken = new CopyOnWriteArrayList<>();

	@Override
	public void speak(String text, boolean interrupt) {
		spoken.add(text);
	}

	public List<String> spoken() {
		return List.copyOf(spoken);
	}

	public void clear() {
		spoken.clear();
	}
}
```

- [ ] **Step 3: Write the failing gametest `TitleNarratorGameTest`**

This runs the mod spec's testing checklist (items 1–5, 8) through real `/title` commands in a singleplayer world. Commands passed to one `run(...)` call execute in the same server tick, like a datapack function would.

```java
package dev.samsside.titlenarrator.gametest;

import dev.samsside.titlenarrator.TitleNarratorClient;
import dev.samsside.titlenarrator.config.TitleNarratorConfig;
import dev.samsside.titlenarrator.core.TitleNarrator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

public final class TitleNarratorGameTest implements FabricClientGameTest {
	private final RecordingSpeaker speaker = new RecordingSpeaker();
	private final AtomicLong clock = new AtomicLong(1_000_000);

	@Override
	public void runTest(ClientGameTestContext context) {
		TitleNarratorClient.setTestOverrides(speaker, clock::get);
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			fresh(context, world);
			run(world, "title @a title \"Hello\"");
			expect("plain title", "Hello");

			fresh(context, world);
			run(world, "title @a subtitle \"World\"", "title @a title \"Hello\"");
			expect("subtitle then title", "Hello. World");

			fresh(context, world);
			run(world, "title @a title \"Spam\"");
			run(world, "title @a title \"Spam\"");
			run(world, "title @a title \"Spam\"");
			expect("repeats inside window", "Spam");
			clock.addAndGet(new TitleNarratorConfig().dedupeWindowMs);
			run(world, "title @a title \"Spam\"");
			expect("repeat after window", "Spam", "Spam");

			fresh(context, world);
			run(world, "title @a title {\"text\":\"Coloured\",\"color\":\"red\",\"bold\":true}");
			expect("formatted component", "Coloured");

			fresh(context, world);
			run(world, "title @a title \"Shown\"");
			run(world, "title @a clear");
			run(world, "title @a reset");
			run(world, "title @a title \"\"");
			expect("clear, reset and empty title", "Shown");

			fresh(context, world);
			run(world, "title @a title \"Boss\"");
			run(world, "title @a subtitle \"Phase 2\"");
			context.waitTicks(TitleNarrator.LATE_SUBTITLE_DELAY_TICKS + 1);
			expect("late subtitle", "Boss", "Phase 2");

			fresh(context, world);
			run(world, "title @a title \"First\"");
			run(world, "title @a subtitle \"Second sub\"", "title @a title \"Second\"");
			context.waitTicks(TitleNarrator.LATE_SUBTITLE_DELAY_TICKS + 1);
			expect("next title while previous visible", "First", "Second. Second sub");

			fresh(context, world);
			run(world, "title @a actionbar \"Bar\"");
			expect("action bar off by default");
			context.runOnClient(mc -> TitleNarratorClient.config().narrateActionBar = true);
			run(world, "title @a actionbar \"Bar\"");
			run(world, "title @a actionbar \"Bar\"");
			expect("action bar enabled", "Bar");

			fresh(context, world);
			run(world, "title @a title \"ᴄʀᴀꜰᴛᴇᴅ ★\"");
			expect("small caps", "crafted");

			fresh(context, world);
			context.runOnClient(mc -> TitleNarratorClient.config().enabled = false);
			run(world, "title @a title \"Muted\"");
			expect("disabled");

			fresh(context, world);
		} finally {
			TitleNarratorClient.setTestOverrides(null, null);
		}
	}

	/** Clears the HUD, restores default settings and forgets previous speech. */
	private void fresh(ClientGameTestContext context, TestSingleplayerContext world) {
		run(world, "title @a clear");
		context.waitTicks(TitleNarrator.LATE_SUBTITLE_DELAY_TICKS + 1);
		context.runOnClient(mc -> {
			TitleNarratorClient.config().copyFrom(new TitleNarratorConfig());
			Objects.requireNonNull(TitleNarratorClient.narrator()).reset();
		});
		speaker.clear();
	}

	/** Runs the commands in one server tick and waits until the client has handled the resulting packets. */
	private static void run(TestSingleplayerContext world, String... commands) {
		world.getServer().runOnServer(server -> {
			for (String command : commands) {
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
			}
		});
		world.getConnection().waitForClientboundPackets();
	}

	private void expect(String scenario, String... expected) {
		List<String> actual = speaker.spoken();
		if (!actual.equals(List.of(expected))) {
			throw new AssertionError(scenario + ": expected " + List.of(expected) + " but spoke " + actual);
		}
	}
}
```

- [ ] **Step 4: Run the gametest to verify it fails**

Run: `./gradlew runClientGameTest`
Expected: FAIL — compilation errors: `TitleNarratorClient.setTestOverrides`, `config()`, `narrator()` do not exist.

- [ ] **Step 5: Implement `TitleNarratorClient`**

```java
package dev.samsside.titlenarrator;

import dev.samsside.titlenarrator.config.TitleNarratorConfig;
import dev.samsside.titlenarrator.core.TitleNarrator;
import dev.samsside.titlenarrator.speech.DirectSpeaker;
import dev.samsside.titlenarrator.speech.GameNarratorSpeaker;
import dev.samsside.titlenarrator.speech.Speaker;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.LongSupplier;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

public final class TitleNarratorClient implements ClientModInitializer {
	private static @Nullable TitleNarratorConfig config;
	private static @Nullable Path configPath;
	private static @Nullable TitleNarrator narrator;
	private static volatile @Nullable Speaker speakerOverride;
	private static volatile @Nullable LongSupplier clockOverride;

	@Override
	public void onInitializeClient() {
		configPath = FabricLoader.getInstance().getConfigDir().resolve("titlenarrator.json");
		config = TitleNarratorConfig.load(configPath);

		Speaker gameSpeaker = new GameNarratorSpeaker();
		Speaker directSpeaker = new DirectSpeaker();
		TitleNarrator created = new TitleNarrator(
				TitleNarratorClient::config,
				() -> {
					Speaker override = speakerOverride;
					if (override != null) {
						return override;
					}
					return config().bypassNarratorSetting ? directSpeaker : gameSpeaker;
				},
				() -> {
					LongSupplier override = clockOverride;
					return override != null ? override.getAsLong() : System.currentTimeMillis();
				});
		narrator = created;
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> created.onClientTick());
	}

	public static TitleNarratorConfig config() {
		return Objects.requireNonNull(config, "Title Narrator is not initialised");
	}

	public static @Nullable TitleNarrator narrator() {
		return narrator;
	}

	public static void saveConfig() {
		if (config != null && configPath != null) {
			config.save(configPath);
		}
	}

	/** Gametest hook: route speech to {@code speaker} and read time from {@code clockMillis}. Pass nulls to restore. */
	public static void setTestOverrides(@Nullable Speaker speaker, @Nullable LongSupplier clockMillis) {
		speakerOverride = speaker;
		clockOverride = clockMillis;
	}
}
```

- [ ] **Step 6: Run the gametest to verify the mixin is still missing**

Run: `./gradlew runClientGameTest`
Expected: FAIL — `AssertionError: plain title: expected [Hello] but spoke []` (nothing hooks the HUD yet). A `Failed to load library flite` error in the log is expected on Linux and harmless.

- [ ] **Step 7: Write `HudMixin`**

```java
package dev.samsside.titlenarrator.mixin;

import dev.samsside.titlenarrator.TitleNarratorClient;
import dev.samsside.titlenarrator.core.TitleNarrator;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class HudMixin {
	@Shadow
	private @Nullable Component subtitle;

	@Shadow
	private int titleTime;

	@Inject(method = "setTitle", at = @At("TAIL"))
	private void titlenarrator$onSetTitle(Component title, CallbackInfo ci) {
		TitleNarrator narrator = TitleNarratorClient.narrator();
		if (narrator != null) {
			narrator.onTitle(title.getString(), subtitle == null ? null : subtitle.getString());
		}
	}

	@Inject(method = "setSubtitle", at = @At("HEAD"))
	private void titlenarrator$onSetSubtitle(Component newSubtitle, CallbackInfo ci) {
		TitleNarrator narrator = TitleNarratorClient.narrator();
		if (narrator != null) {
			narrator.onSubtitle(newSubtitle.getString(), titleTime > 0);
		}
	}

	@Inject(method = "setOverlayMessage", at = @At("TAIL"))
	private void titlenarrator$onSetOverlayMessage(Component message, boolean animateColor, CallbackInfo ci) {
		TitleNarrator narrator = TitleNarratorClient.narrator();
		if (narrator != null) {
			narrator.onActionBar(message.getString());
		}
	}
}
```

- [ ] **Step 8: Register it in `titlenarrator.client.mixins.json`**

```json
	"client": [
		"GameNarratorAccessor",
		"HudMixin"
	],
```

- [ ] **Step 9: Run the gametest to verify it passes**

Run: `./gradlew runClientGameTest`
Expected: `BUILD SUCCESSFUL`. If a scenario fails, the `AssertionError` names it and shows what was spoken — fix the code, not the expectation.

- [ ] **Step 10: Run the full build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 11: Commit**

```bash
git add src/client src/gametest
git commit -m "feat: hook Hud titles and wire narration into the client"
```

---

### Task 9: Toggle keybind with toast

**Files:**
- Create: `src/client/java/dev/samsside/titlenarrator/ToggleKeybind.java`
- Create: `src/client/resources/assets/titlenarrator/lang/en_us.json`
- Modify: `src/client/java/dev/samsside/titlenarrator/TitleNarratorClient.java` (call `ToggleKeybind.register()` at the end of `onInitializeClient`)
- Create: `src/gametest/java/dev/samsside/titlenarrator/gametest/ToggleKeybindGameTest.java`
- Modify: `src/gametest/resources/fabric.mod.json` (add the entrypoint)

**Interfaces:**
- Consumes: `TitleNarratorClient.config()`, `saveConfig()`, `setTestOverrides(...)` (Task 8); `RecordingSpeaker` (Task 8). Verified 26.2 API: `KeyMappingHelper.registerKeyMapping(KeyMapping)` (package `net.fabricmc.fabric.api.client.keymapping.v1`), `KeyMapping(String, InputConstants.Type, int, KeyMapping.Category)`, `KeyMapping.Category.register(Identifier)`, `KeyMapping#consumeClick()/#setKey(InputConstants.Key)`, `KeyMapping.resetMapping()`, `InputConstants.UNKNOWN`, `InputConstants.Type.KEYSYM.getOrCreate(int)`, `SystemToast.addOrUpdate(ToastManager, SystemToastId, Component, Component)`, `Minecraft.gui.toastManager()`, `TestInput#pressKey(KeyMapping)`.
- Produces: `public static final KeyMapping ToggleKeybind.TOGGLE`, `public static void ToggleKeybind.register()`.

The key is **unbound by default** so it cannot clash with other mods; players bind it under Options → Controls → Key Binds → Title Narrator.

- [ ] **Step 1: Add the gametest entrypoint to `src/gametest/resources/fabric.mod.json`**

```json
		"fabric-client-gametest": [
			"dev.samsside.titlenarrator.gametest.TitleNarratorGameTest",
			"dev.samsside.titlenarrator.gametest.ToggleKeybindGameTest"
		]
```

- [ ] **Step 2: Write the failing gametest `ToggleKeybindGameTest`**

```java
package dev.samsside.titlenarrator.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.samsside.titlenarrator.ToggleKeybind;
import dev.samsside.titlenarrator.TitleNarratorClient;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class ToggleKeybindGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		RecordingSpeaker speaker = new RecordingSpeaker();
		TitleNarratorClient.setTestOverrides(speaker, () -> 0L);
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			context.runOnClient(mc -> {
				ToggleKeybind.TOGGLE.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_J));
				KeyMapping.resetMapping();
				TitleNarratorClient.config().enabled = true;
			});

			context.getInput().pressKey(ToggleKeybind.TOGGLE);
			context.waitTick();
			if (context.computeOnClient(mc -> TitleNarratorClient.config().enabled)) {
				throw new AssertionError("first press should turn narration off");
			}
			world.getServer().runCommand("title @a title \"Muted\"");
			world.getConnection().waitForClientboundPackets();
			if (!speaker.spoken().isEmpty()) {
				throw new AssertionError("nothing should be spoken while off, but spoke " + speaker.spoken());
			}

			context.getInput().pressKey(ToggleKeybind.TOGGLE);
			context.waitTick();
			if (!context.computeOnClient(mc -> TitleNarratorClient.config().enabled)) {
				throw new AssertionError("second press should turn narration back on");
			}
			world.getServer().runCommand("title @a title \"Loud\"");
			world.getConnection().waitForClientboundPackets();
			if (!speaker.spoken().equals(List.of("Loud"))) {
				throw new AssertionError("expected [Loud] but spoke " + speaker.spoken());
			}
		} finally {
			context.runOnClient(mc -> {
				ToggleKeybind.TOGGLE.setKey(InputConstants.UNKNOWN);
				KeyMapping.resetMapping();
			});
			TitleNarratorClient.setTestOverrides(null, null);
		}
	}
}
```

- [ ] **Step 3: Run the gametest to verify it fails**

Run: `./gradlew runClientGameTest`
Expected: FAIL — compilation error `cannot find symbol: class ToggleKeybind`.

- [ ] **Step 4: Write `ToggleKeybind`**

```java
package dev.samsside.titlenarrator;

import com.mojang.blaze3d.platform.InputConstants;
import dev.samsside.titlenarrator.config.TitleNarratorConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Keybind that turns narration on and off, confirmed with a toast (not the action bar, which we might narrate). */
public final class ToggleKeybind {
	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("titlenarrator", "main"));
	public static final KeyMapping TOGGLE = new KeyMapping(
			"key.titlenarrator.toggle", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId();

	private ToggleKeybind() {
	}

	public static void register() {
		KeyMappingHelper.registerKeyMapping(TOGGLE);
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			while (TOGGLE.consumeClick()) {
				toggle(minecraft);
			}
		});
	}

	private static void toggle(Minecraft minecraft) {
		TitleNarratorConfig config = TitleNarratorClient.config();
		config.enabled = !config.enabled;
		TitleNarratorClient.saveConfig();
		SystemToast.addOrUpdate(minecraft.gui.toastManager(), TOAST,
				Component.translatable("titlenarrator.toast.title"),
				Component.translatable(config.enabled ? "titlenarrator.toast.on" : "titlenarrator.toast.off"));
	}
}
```

- [ ] **Step 5: Call it from `TitleNarratorClient.onInitializeClient`** — add as the last line of the method:

```java
		ToggleKeybind.register();
```

- [ ] **Step 6: Write `src/client/resources/assets/titlenarrator/lang/en_us.json`**

```json
{
	"key.category.titlenarrator.main": "Title Narrator",
	"key.titlenarrator.toggle": "Toggle title narration",
	"titlenarrator.toast.title": "Title Narrator",
	"titlenarrator.toast.on": "Narration on",
	"titlenarrator.toast.off": "Narration off"
}
```

- [ ] **Step 7: Run the gametests to verify they pass**

Run: `./gradlew runClientGameTest`
Expected: `BUILD SUCCESSFUL` (both `TitleNarratorGameTest` and `ToggleKeybindGameTest` pass).

- [ ] **Step 8: Commit**

```bash
git add src/client src/gametest
git commit -m "feat: add toggle keybind with toast confirmation"
```

---

### Task 10: YACL config screen via Mod Menu (optional dependencies)

**Files:**
- Create: `src/client/java/dev/samsside/titlenarrator/config/YaclScreenFactory.java`
- Create: `src/client/java/dev/samsside/titlenarrator/config/ModMenuIntegration.java`
- Modify: `src/main/resources/fabric.mod.json` (add `modmenu` entrypoint)
- Modify: `src/client/resources/assets/titlenarrator/lang/en_us.json` (config strings)
- Create: `src/gametest/java/dev/samsside/titlenarrator/gametest/ConfigScreenGameTest.java`
- Modify: `src/gametest/resources/fabric.mod.json` (add the entrypoint)

**Interfaces:**
- Consumes: `TitleNarratorConfig` fields and `MAX_DEDUPE_WINDOW_MS` (Task 5), `TitleNarratorClient.config()/saveConfig()` (Task 8). Verified APIs: YACL `dev.isxander.yacl3.api.{YetAnotherConfigLib, ConfigCategory, Option, OptionDescription}`, `dev.isxander.yacl3.api.controller.{TickBoxControllerBuilder, LongSliderControllerBuilder}`; Mod Menu `com.terraformersmc.modmenu.api.{ModMenuApi, ConfigScreenFactory}`; YACL mod id `yet_another_config_lib_v3`.
- Produces: `public static Screen YaclScreenFactory.create(@Nullable Screen parent)`; `ModMenuIntegration implements ModMenuApi`.

Optionality rules: `ModMenuIntegration` is only loaded by Mod Menu (so it is safe to reference Mod Menu types), and it only reaches `YaclScreenFactory` after checking that YACL is loaded. No other class references YACL.

- [ ] **Step 1: Add the gametest entrypoint to `src/gametest/resources/fabric.mod.json`**

```json
		"fabric-client-gametest": [
			"dev.samsside.titlenarrator.gametest.TitleNarratorGameTest",
			"dev.samsside.titlenarrator.gametest.ToggleKeybindGameTest",
			"dev.samsside.titlenarrator.gametest.ConfigScreenGameTest"
		]
```

- [ ] **Step 2: Write the failing gametest `ConfigScreenGameTest`** (YACL and Mod Menu are on the dev runtime classpath via `localRuntime`)

```java
package dev.samsside.titlenarrator.gametest;

import dev.samsside.titlenarrator.config.ModMenuIntegration;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.Screen;

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
	}
}
```

- [ ] **Step 3: Run the gametest to verify it fails**

Run: `./gradlew runClientGameTest`
Expected: FAIL — compilation error `cannot find symbol: class ModMenuIntegration`.

- [ ] **Step 4: Write `YaclScreenFactory`**

```java
package dev.samsside.titlenarrator.config;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.LongSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import dev.samsside.titlenarrator.TitleNarratorClient;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** The only class that touches YACL; load it only after checking YACL is installed. */
public final class YaclScreenFactory {
	private YaclScreenFactory() {
	}

	public static Screen create(@Nullable Screen parent) {
		TitleNarratorConfig config = TitleNarratorClient.config();
		TitleNarratorConfig defaults = new TitleNarratorConfig();
		return YetAnotherConfigLib.createBuilder()
				.title(Component.translatable("titlenarrator.config.title"))
				.category(ConfigCategory.createBuilder()
						.name(Component.translatable("titlenarrator.config.category"))
						.option(toggle("enabled", defaults.enabled, () -> config.enabled, v -> config.enabled = v))
						.option(toggle("narrateTitles", defaults.narrateTitles, () -> config.narrateTitles, v -> config.narrateTitles = v))
						.option(toggle("narrateSubtitles", defaults.narrateSubtitles, () -> config.narrateSubtitles, v -> config.narrateSubtitles = v))
						.option(toggle("lateSubtitles", defaults.lateSubtitles, () -> config.lateSubtitles, v -> config.lateSubtitles = v))
						.option(toggle("narrateActionBar", defaults.narrateActionBar, () -> config.narrateActionBar, v -> config.narrateActionBar = v))
						.option(toggle("sanitiseText", defaults.sanitiseText, () -> config.sanitiseText, v -> config.sanitiseText = v))
						.option(Option.<Long>createBuilder()
								.name(Component.translatable("titlenarrator.config.dedupeWindowMs"))
								.description(OptionDescription.of(Component.translatable("titlenarrator.config.dedupeWindowMs.desc")))
								.binding(defaults.dedupeWindowMs, () -> config.dedupeWindowMs, v -> config.dedupeWindowMs = v)
								.controller(option -> LongSliderControllerBuilder.create(option)
										.range(0L, TitleNarratorConfig.MAX_DEDUPE_WINDOW_MS)
										.step(250L))
								.build())
						.option(toggle("interrupt", defaults.interrupt, () -> config.interrupt, v -> config.interrupt = v))
						.option(toggle("bypassNarratorSetting", defaults.bypassNarratorSetting, () -> config.bypassNarratorSetting, v -> config.bypassNarratorSetting = v))
						.build())
				.save(TitleNarratorClient::saveConfig)
				.build()
				.generateScreen(parent);
	}

	private static Option<Boolean> toggle(String key, boolean defaultValue, Supplier<Boolean> getter, Consumer<Boolean> setter) {
		return Option.<Boolean>createBuilder()
				.name(Component.translatable("titlenarrator.config." + key))
				.description(OptionDescription.of(Component.translatable("titlenarrator.config." + key + ".desc")))
				.binding(defaultValue, getter, setter)
				.controller(TickBoxControllerBuilder::create)
				.build();
	}
}
```

- [ ] **Step 5: Write `ModMenuIntegration`**

```java
package dev.samsside.titlenarrator.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

/** Mod Menu entrypoint. Offers the config screen only when YACL is installed; otherwise no config button. */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		if (!FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3")) {
			return parent -> null;
		}
		return YaclScreenFactory::create;
	}
}
```

- [ ] **Step 6: Add the `modmenu` entrypoint to `src/main/resources/fabric.mod.json`**

```json
	"entrypoints": {
		"client": [
			"dev.samsside.titlenarrator.TitleNarratorClient"
		],
		"modmenu": [
			"dev.samsside.titlenarrator.config.ModMenuIntegration"
		]
	},
```

- [ ] **Step 7: Add the config strings to `en_us.json`** (the full file after this step)

```json
{
	"key.category.titlenarrator.main": "Title Narrator",
	"key.titlenarrator.toggle": "Toggle title narration",
	"titlenarrator.toast.title": "Title Narrator",
	"titlenarrator.toast.on": "Narration on",
	"titlenarrator.toast.off": "Narration off",
	"titlenarrator.config.title": "Title Narrator",
	"titlenarrator.config.category": "General",
	"titlenarrator.config.enabled": "Enable narration",
	"titlenarrator.config.enabled.desc": "Master switch. The toggle keybind flips this.",
	"titlenarrator.config.narrateTitles": "Speak titles",
	"titlenarrator.config.narrateTitles.desc": "Speak the large text shown in the middle of the screen.",
	"titlenarrator.config.narrateSubtitles": "Speak subtitles",
	"titlenarrator.config.narrateSubtitles.desc": "Say the subtitle after the title, e.g. \"Hello. World\".",
	"titlenarrator.config.lateSubtitles": "Speak late subtitles",
	"titlenarrator.config.lateSubtitles.desc": "Speak a subtitle on its own when it arrives after its title is already on screen.",
	"titlenarrator.config.narrateActionBar": "Speak action bar",
	"titlenarrator.config.narrateActionBar.desc": "Speak messages shown above the hotbar. Off by default because some servers update them constantly.",
	"titlenarrator.config.sanitiseText": "Clean up stylised text",
	"titlenarrator.config.sanitiseText.desc": "Turn small-caps letters into normal ones and drop symbols and emoji the voice would read out literally.",
	"titlenarrator.config.dedupeWindowMs": "Repeat suppression (ms)",
	"titlenarrator.config.dedupeWindowMs.desc": "The same text is not spoken again until this many milliseconds have passed.",
	"titlenarrator.config.interrupt": "Interrupt current speech",
	"titlenarrator.config.interrupt.desc": "On: a new title cuts off whatever is being spoken. Off: it waits its turn.",
	"titlenarrator.config.bypassNarratorSetting": "Speak even when Narrator is Off",
	"titlenarrator.config.bypassNarratorSetting.desc": "Use text-to-speech directly, ignoring the vanilla Narrator option."
}
```

- [ ] **Step 8: Run the gametests to verify they pass**

Run: `./gradlew runClientGameTest`
Expected: `BUILD SUCCESSFUL`; a screenshot `titlenarrator-config-screen.png` appears under `build/run/clientGameTest/screenshots/` (or the run directory Loom prints). Open it and check that all nine options are listed with readable labels (no raw `titlenarrator.config.…` keys).

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/fabric.mod.json src/client src/gametest
git commit -m "feat: add optional YACL config screen via Mod Menu"
```

---

### Task 11: Multiplayer gametest against a dedicated server

**Files:**
- Create: `src/gametest/java/dev/samsside/titlenarrator/gametest/MultiplayerGameTest.java`
- Modify: `src/gametest/resources/fabric.mod.json` (add the entrypoint)

**Interfaces:**
- Consumes: `RecordingSpeaker` (Task 8), `TitleNarratorClient.setTestOverrides/narrator` (Task 8). Verified API: `ClientGameTestContext#worldBuilder().createServer()` → `TestDedicatedServerContext` (`runCommand`, `connect()` → `TestDedicatedServerConnection`, both `AutoCloseable`).
- Produces: automated coverage of mod spec checklist item 7 — a dedicated server that does **not** have the mod (the mod is `environment: client`, so the server side never loads it) sends titles and the client narrates them.

- [ ] **Step 1: Add the entrypoint to `src/gametest/resources/fabric.mod.json`**

```json
		"fabric-client-gametest": [
			"dev.samsside.titlenarrator.gametest.TitleNarratorGameTest",
			"dev.samsside.titlenarrator.gametest.ToggleKeybindGameTest",
			"dev.samsside.titlenarrator.gametest.ConfigScreenGameTest",
			"dev.samsside.titlenarrator.gametest.MultiplayerGameTest"
		]
```

- [ ] **Step 2: Write `MultiplayerGameTest`**

```java
package dev.samsside.titlenarrator.gametest;

import dev.samsside.titlenarrator.TitleNarratorClient;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;

public final class MultiplayerGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		RecordingSpeaker speaker = new RecordingSpeaker();
		TitleNarratorClient.setTestOverrides(speaker, () -> 0L);
		try (TestDedicatedServerContext server = context.worldBuilder().createServer();
				TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();
			context.runOnClient(mc -> Objects.requireNonNull(TitleNarratorClient.narrator()).reset());

			server.runCommand("title @a subtitle \"From the server\"");
			server.runCommand("title @a title \"Remote\"");
			connection.waitForClientboundPackets();

			if (!speaker.spoken().equals(List.of("Remote. From the server"))) {
				throw new AssertionError("expected [Remote. From the server] but spoke " + speaker.spoken());
			}
		} finally {
			TitleNarratorClient.setTestOverrides(null, null);
		}
	}
}
```

- [ ] **Step 3: Run the gametests**

Run: `./gradlew runClientGameTest`
Expected: `BUILD SUCCESSFUL` (all four gametests pass). This test exercises existing code, so it should pass the first time; if it fails, treat it as a real bug in Tasks 6–8 and use superpowers:systematic-debugging.

- [ ] **Step 4: Commit**

```bash
git add src/gametest
git commit -m "test: narrate titles sent by a dedicated server without the mod"
```

---

### Task 12: README, spec deviations and final verification

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: everything above; the "Verified against 26.2" table in the design spec.
- Produces: the user-facing README and a verified release jar.

- [ ] **Step 1: Write `README.md`**

````markdown
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
````

- [ ] **Step 2: Run the full automated verification**

Run: `./gradlew clean build runClientGameTest`
Expected: `BUILD SUCCESSFUL`; `build/libs/title-narrator-0.1.0.jar` exists.

Run: `unzip -p build/libs/title-narrator-0.1.0.jar fabric.mod.json | grep -E '"environment"|"modmenu"|"suggests"'`
Expected: `"environment": "client"`, the `modmenu` entrypoint, and the `suggests` block — and **no** YACL or Mod Menu under `depends`.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: add README with Narrator, Linux TTS, settings and spec deviations"
```

- [ ] **Step 4: Hand the manual checklist to the user**

These need real speakers and a normal game install, so the executor lists them for the user rather than claiming them. Report each as passed / failed / not run:

1. **Audible speech** (Windows or macOS, Narrator = System): install the jar + Fabric API in a 26.2 instance, run `/title @s title "Hello"` → you hear "Hello"; `/title @s subtitle "World"` then `/title @s title "Hello"` → "Hello. World".
2. **Narrator Off**: set Narrator to Off → silence; enable *Speak even when Narrator is Off* → you hear it.
3. **Interrupt vs queue**: send two long titles back to back with *Interrupt current speech* on (second cuts off first), then off (second waits).
4. **Without optional mods**: only Fabric API + Title Narrator → game starts, titles are spoken, no errors in the log.
5. **Mod Menu without YACL**: game starts; Title Narrator has no config button and nothing crashes.
6. **Real multiplayer server** (e.g. Paper) sending titles → spoken, with nothing installed on the server.
7. **Keybind toast**: bind the key, press it → "Narration off" / "Narration on" toast appears.
