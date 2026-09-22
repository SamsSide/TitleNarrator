package dev.samsside.titlenarrator.speech;

import com.mojang.text2speech.Narrator;
import dev.samsside.titlenarrator.mixin.GameNarratorAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

/** Speaks through the underlying text-to-speech engine, ignoring the Narrator option. */
public final class DirectSpeaker implements Speaker {
	@Override
	public void speak(String text, boolean interrupt) {
		Minecraft minecraft = Minecraft.getInstance();
		Narrator tts = ((GameNarratorAccessor) minecraft.getNarrator()).titlenarrator$getNarrator();
		if (!tts.active()) {
			GameNarratorSpeaker.warnUnavailable();
			return;
		}
		if (interrupt) {
			tts.clear();
		}
		tts.say(text, interrupt, minecraft.options.getFinalSoundSourceVolume(SoundSource.VOICE));
	}
}
