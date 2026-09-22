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
