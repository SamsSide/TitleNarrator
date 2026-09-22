package dev.samsside.titlenarrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextComposerTest {
	@Test
	void titleOnly() {
		assertEquals("Hello", TextComposer.compose("Hello", null));
	}

	@Test
	void titleAndSubtitleJoinedWithFullStop() {
		assertEquals("Hello. World", TextComposer.compose("Hello", "World"));
	}

	@Test
	void blankSubtitleIsDropped() {
		assertEquals("Hello", TextComposer.compose("Hello", "   "));
	}

	@Test
	void titleEndingInPunctuationIsNotDoubled() {
		assertEquals("Victory! Well done", TextComposer.compose("Victory!", "Well done"));
		assertEquals("Ready? Go", TextComposer.compose("Ready?", "Go"));
		assertEquals("The end. Thanks", TextComposer.compose("The end.", "Thanks"));
	}

	@Test
	void blankTitleFallsBackToSubtitle() {
		assertEquals("World", TextComposer.compose("", "World"));
		assertEquals("World", TextComposer.compose(null, "World"));
	}

	@Test
	void bothBlankIsEmpty() {
		assertEquals("", TextComposer.compose(" ", null));
		assertEquals("", TextComposer.compose(null, null));
	}

	@Test
	void surroundingWhitespaceIsStripped() {
		assertEquals("Hello. World", TextComposer.compose("  Hello  ", " World "));
	}
}
