package dev.samsside.titlenarrator.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DeduperTest {
	private final AtomicLong clock = new AtomicLong(10_000);

	@Test
	void firstOccurrenceIsSpoken() {
		assertTrue(new Deduper(clock::get, false).shouldSpeak("Hello", 3000));
	}

	@Test
	void fixedWindowSuppressesRepeatsUntilWindowElapses() {
		Deduper deduper = new Deduper(clock::get, false);
		assertTrue(deduper.shouldSpeak("Spam", 3000));
		clock.addAndGet(2999);
		assertFalse(deduper.shouldSpeak("Spam", 3000));
		clock.addAndGet(1);
		assertTrue(deduper.shouldSpeak("Spam", 3000));
	}

	@Test
	void fixedWindowDoesNotExtendOnSuppressedRepeats() {
		Deduper deduper = new Deduper(clock::get, false);
		assertTrue(deduper.shouldSpeak("Spam", 3000));
		clock.addAndGet(2000);
		assertFalse(deduper.shouldSpeak("Spam", 3000));
		clock.addAndGet(1000);
		assertTrue(deduper.shouldSpeak("Spam", 3000));
	}

	@Test
	void slidingWindowExtendsWhileTextKeepsArriving() {
		Deduper deduper = new Deduper(clock::get, true);
		assertTrue(deduper.shouldSpeak("Bar", 3000));
		clock.addAndGet(2000);
		assertFalse(deduper.shouldSpeak("Bar", 3000));
		clock.addAndGet(2000);
		assertFalse(deduper.shouldSpeak("Bar", 3000));
		clock.addAndGet(3000);
		assertTrue(deduper.shouldSpeak("Bar", 3000));
	}

	@Test
	void differentTextIsAlwaysSpoken() {
		Deduper deduper = new Deduper(clock::get, false);
		assertTrue(deduper.shouldSpeak("A", 3000));
		assertTrue(deduper.shouldSpeak("B", 3000));
		assertTrue(deduper.shouldSpeak("A", 3000));
	}

	@Test
	void zeroWindowDisablesSuppression() {
		Deduper deduper = new Deduper(clock::get, false);
		assertTrue(deduper.shouldSpeak("Spam", 0));
		assertTrue(deduper.shouldSpeak("Spam", 0));
	}

	@Test
	void resetForgetsLastText() {
		Deduper deduper = new Deduper(clock::get, false);
		assertTrue(deduper.shouldSpeak("Spam", 3000));
		deduper.reset();
		assertTrue(deduper.shouldSpeak("Spam", 3000));
	}
}
