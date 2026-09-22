package dev.samsside.titlenarrator.speech;

import com.mojang.text2speech.OperatingSystem;
import java.util.function.Supplier;

/** Where speech that has already passed the speakers' checks is sent. */
@FunctionalInterface
public interface SpeechOutput {
	void submit(String text, boolean interrupt, float volume);

	/**
	 * Only Windows' engine blocks the calling thread (synchronous SAPI calls), so only Windows speaks on a
	 * {@link SpeechThread}. Linux's engine is already asynchronous, and macOS's needs the main thread's run loop to
	 * report when it has finished speaking, so both keep using vanilla's engine directly.
	 */
	static SpeechOutput forPlatform(OperatingSystem os, Supplier<SpeechOutput> backgroundThread,
			Supplier<SpeechOutput> callerThread) {
		return os == OperatingSystem.WINDOWS ? backgroundThread.get() : callerThread.get();
	}
}
