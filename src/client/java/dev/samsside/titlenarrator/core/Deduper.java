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
