package dev.samsside.titlenarrator.speech;

/** Something that can say text out loud. */
@FunctionalInterface
public interface Speaker {
	/**
	 * @param interrupt true to cut off whatever is currently being spoken, false to queue after it
	 */
	void speak(String text, boolean interrupt);
}
