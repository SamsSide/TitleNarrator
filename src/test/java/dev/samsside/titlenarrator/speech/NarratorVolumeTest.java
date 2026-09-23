package dev.samsside.titlenarrator.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NarratorVolumeTest {
	@Test
	void convertsPercentToEngineVolume() {
		assertEquals(0f, NarratorVolume.fromPercent(0));
		assertEquals(0.5f, NarratorVolume.fromPercent(50));
		assertEquals(1f, NarratorVolume.fromPercent(100));
	}

	@Test
	void clampsOutOfRangePercent() {
		assertEquals(1f, NarratorVolume.fromPercent(150));
		assertEquals(0f, NarratorVolume.fromPercent(-10));
	}
}
