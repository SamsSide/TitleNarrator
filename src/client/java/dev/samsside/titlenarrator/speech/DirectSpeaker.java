package dev.samsside.titlenarrator.speech;

import java.util.function.Supplier;
import net.minecraft.client.Minecraft;

/** Speaks regardless of the Narrator option, through the platform's {@link SpeechOutput}. */
public final class DirectSpeaker implements Speaker {
	private final SpeechOutput output;
	private final Supplier<Float> volume;

	public DirectSpeaker(SpeechOutput output, Supplier<Float> volume) {
		this.output = output;
		this.volume = volume;
	}

	@Override
	public void speak(String text, boolean interrupt) {
		// Vanilla's engine being inactive means the platform has no usable TTS, so ours would not work either.
		if (!Minecraft.getInstance().getNarrator().isActive()) {
			SpeechThread.warnUnavailable();
			return;
		}
		output.submit(text, interrupt, volume.get());
	}
}
