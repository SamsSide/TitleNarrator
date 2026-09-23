package dev.samsside.titlenarrator.speech;

import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.NarratorStatus;

/**
 * Speaks only when the vanilla Narrator option is All or System, through the platform's {@link SpeechOutput}.
 */
public final class GameNarratorSpeaker implements Speaker {
	private final SpeechOutput output;
	private final Supplier<Float> volume;

	public GameNarratorSpeaker(SpeechOutput output, Supplier<Float> volume) {
		this.output = output;
		this.volume = volume;
	}

	@Override
	public void speak(String text, boolean interrupt) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.getNarrator().isActive()) {
			SpeechThread.warnUnavailable();
			return;
		}
		NarratorStatus status = minecraft.options.narrator().get();
		if (!status.shouldNarrateSystem()) {
			LogOnce.info("narrator-option", "[Title Narrator] Narrator option is {}; titles are only spoken when it is "
					+ "All or System, or when 'Speak even when Narrator is Off' is enabled", status);
			return;
		}
		output.submit(text, interrupt, volume.get());
	}

	/** Whether vanilla's own system narration (e.g. {@code saySystemQueued}) would actually be spoken right now. */
	public static boolean vanillaSpeaksSystemMessages() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.getNarrator().isActive() && minecraft.options.narrator().get().shouldNarrateSystem();
	}
}
