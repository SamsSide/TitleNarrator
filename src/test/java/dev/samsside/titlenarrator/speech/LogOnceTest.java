package dev.samsside.titlenarrator.speech;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogOnceTest {
	@Test
	void logsEachKeyOnlyOnce() {
		assertTrue(LogOnce.warn("logonce-test-a", "first {}", 1));
		assertFalse(LogOnce.warn("logonce-test-a", "second {}", 2));
		assertTrue(LogOnce.info("logonce-test-b", "other key"));
	}
}
