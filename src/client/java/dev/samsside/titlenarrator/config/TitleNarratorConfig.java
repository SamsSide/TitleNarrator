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
