package dev.samsside.titlenarrator.speech;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

import com.mojang.text2speech.OperatingSystem;
import org.junit.jupiter.api.Test;

class SpeechOutputTest {
	private static final SpeechOutput BACKGROUND = (text, interrupt, volume) -> {
	};
	private static final SpeechOutput CALLER = (text, interrupt, volume) -> {
	};

	@Test
	void windowsSpeaksOnBackgroundThread() {
		assertSame(BACKGROUND, SpeechOutput.forPlatform(OperatingSystem.WINDOWS, () -> BACKGROUND, () -> CALLER));
	}

	@Test
	void otherPlatformsKeepVanillaEngineAndNeverStartThread() {
		for (OperatingSystem os : new OperatingSystem[] {OperatingSystem.LINUX, OperatingSystem.MAC_OS,
				OperatingSystem.UNSUPPORTED}) {
			SpeechOutput chosen = SpeechOutput.forPlatform(os, () -> fail("background output must not be created"),
					() -> CALLER);
			assertSame(CALLER, chosen, os.name());
		}
	}
}
