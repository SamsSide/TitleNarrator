package dev.samsside.titlenarrator.speech;

import net.minecraft.client.Minecraft;

/** Speaks regardless of the Narrator option, on the {@link SpeechThread}. */
public final class DirectSpeaker implements Speaker {
	private final SpeechThread speech;

	public DirectSpeaker(SpeechThread speech) {
		this.speech = speech;
	}

	@Override
	public void speak(String text, boolean interrupt) {
		// Vanilla's engine being inactive means the platform has no usable TTS, so ours would not work either.
		if (!Minecraft.getInstance().getNarrator().isActive()) {
			SpeechThread.warnUnavailable();
			return;
		}
		speech.submit(text, interrupt, GameNarratorSpeaker.voiceVolume());
	}
}
