package dev.samsside.titlenarrator.speech;

import com.mojang.text2speech.Narrator;
import dev.samsside.titlenarrator.mixin.GameNarratorAccessor;
import net.minecraft.client.Minecraft;

/** Speaks on the calling thread through vanilla's own text-to-speech engine, as vanilla's narrator does. */
public final class VanillaEngineOutput implements SpeechOutput {
	@Override
	public void submit(String text, boolean interrupt, float volume) {
		Narrator tts = ((GameNarratorAccessor) Minecraft.getInstance().getNarrator()).titlenarrator$getNarrator();
		if (interrupt) {
			tts.clear();
		}
		tts.say(text, interrupt, volume);
	}
}
