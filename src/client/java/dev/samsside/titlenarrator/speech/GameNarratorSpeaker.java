package dev.samsside.titlenarrator.speech;

import net.minecraft.client.GameNarrator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.NarratorStatus;
import net.minecraft.network.chat.Component;

/** Speaks through the vanilla narrator, which stays silent unless the Narrator option is All or System. */
public final class GameNarratorSpeaker implements Speaker {
	@Override
	public void speak(String text, boolean interrupt) {
		Minecraft minecraft = Minecraft.getInstance();
		GameNarrator narrator = minecraft.getNarrator();
		if (!narrator.isActive()) {
			warnUnavailable();
			return;
		}
		NarratorStatus status = minecraft.options.narrator().get();
		if (!status.shouldNarrateSystem()) {
			LogOnce.info("narrator-option", "[Title Narrator] Narrator option is {}; titles are only spoken when it is "
					+ "All or System, or when 'Speak even when Narrator is Off' is enabled", status);
			return;
		}
		if (interrupt) {
			narrator.saySystemNow(text);
		} else {
			narrator.saySystemQueued(Component.literal(text));
		}
	}

	/** Whether vanilla's own system narration (e.g. {@code saySystemQueued}) would actually be spoken right now. */
	public static boolean vanillaSpeaksSystemMessages() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.getNarrator().isActive() && minecraft.options.narrator().get().shouldNarrateSystem();
	}

	static void warnUnavailable() {
		LogOnce.warn("tts-unavailable", "[Title Narrator] Text-to-speech is unavailable on this system, so titles "
				+ "will not be spoken. On Linux, install the flite library (see README).");
	}
}
