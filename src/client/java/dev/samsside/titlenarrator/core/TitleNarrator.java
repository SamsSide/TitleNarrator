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

	/**
	 * @param vanillaWillSpeak true when vanilla narrates this message itself (overlay system chat with the
	 *                         Narrator on), so speaking it here would say it twice
	 */
	public void onActionBar(String message, boolean vanillaWillSpeak) {
		TitleNarratorConfig cfg = config.get();
		if (!cfg.enabled || !cfg.narrateActionBar || vanillaWillSpeak) {
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
		return cfg.sanitiseText ? TextSanitiser.sanitise(text) : TextSanitiser.stripLegacyFormatting(text).strip();
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
