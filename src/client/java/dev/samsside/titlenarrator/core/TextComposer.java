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
