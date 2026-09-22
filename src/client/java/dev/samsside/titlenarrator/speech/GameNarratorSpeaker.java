package dev.samsside.titlenarrator.speech;

import net.minecraft.client.Minecraft;
import net.minecraft.client.NarratorStatus;
import net.minecraft.sounds.SoundSource;

/**
 * Speaks only when the vanilla Narrator option is All or System. The speech itself runs on the
 * {@link SpeechThread}, never on the render thread.
 */
public final class GameNarratorSpeaker implements Speaker {
	private final SpeechThread speech;

	public GameNarratorSpeaker(SpeechThread speech) {
		this.speech = speech;
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
		speech.submit(text, interrupt, voiceVolume());
	}

	/** Whether vanilla's own system narration (e.g. {@code saySystemQueued}) would actually be spoken right now. */
	public static boolean vanillaSpeaksSystemMessages() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.getNarrator().isActive() && minecraft.options.narrator().get().shouldNarrateSystem();
	}

	static float voiceVolume() {
		return Minecraft.getInstance().options.getFinalSoundSourceVolume(SoundSource.VOICE);
	}
}
