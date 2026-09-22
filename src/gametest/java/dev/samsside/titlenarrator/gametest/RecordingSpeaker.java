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
