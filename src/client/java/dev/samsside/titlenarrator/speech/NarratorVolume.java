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
