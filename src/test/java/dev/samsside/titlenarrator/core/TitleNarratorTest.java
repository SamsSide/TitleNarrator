package dev.samsside.titlenarrator.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.samsside.titlenarrator.config.TitleNarratorConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TitleNarratorTest {
	private final List<String> spoken = new ArrayList<>();
	private final List<Boolean> interrupts = new ArrayList<>();
	private final AtomicLong clock = new AtomicLong(1_000_000);
	private final TitleNarratorConfig config = new TitleNarratorConfig();
	private final TitleNarrator narrator = new TitleNarrator(() -> config, () -> (text, interrupt) -> {
		spoken.add(text);
		interrupts.add(interrupt);
	}, clock::get);

	private void ticks(int count) {
		for (int i = 0; i < count; i++) {
			narrator.onClientTick();
		}
	}

	@Test
	void speaksTitleWithSubtitle() {
		narrator.onTitle("Hello", "World");
		assertEquals(List.of("Hello. World"), spoken);
	}

	@Test
	void speaksTitleAlone() {
		narrator.onTitle("Hello", null);
		assertEquals(List.of("Hello"), spoken);
	}

	@Test
	void blankTitleIsSilent() {
		narrator.onTitle("", "World");
		narrator.onTitle("   ", null);
		assertEquals(List.of(), spoken);
	}

	@Test
	void subtitleOmittedWhenSubtitlesDisabled() {
		config.narrateSubtitles = false;
		narrator.onTitle("Hello", "World");
		assertEquals(List.of("Hello"), spoken);
	}

	@Test
	void titlesDisabledIsSilent() {
		config.narrateTitles = false;
		narrator.onTitle("Hello", "World");
		assertEquals(List.of(), spoken);
	}

	@Test
	void masterSwitchSilencesEverything() {
		config.enabled = false;
		config.narrateActionBar = true;
		narrator.onTitle("Hello", null);
		narrator.onActionBar("Bar", false);
		narrator.onSubtitle("Late", true);
		ticks(TitleNarrator.LATE_SUBTITLE_DELAY_TICKS + 1);
		assertEquals(List.of(), spoken);
	}

	@Test
	void repeatedTitleSpokenOncePerWindow() {
		narrator.onTitle("Spam", null);
		narrator.onTitle("Spam", null);
		narrator.onTitle("Spam", null);
		assertEquals(List.of("Spam"), spoken);
		clock.addAndGet(config.dedupeWindowMs);
		narrator.onTitle("Spam", null);
		assertEquals(List.of("Spam", "Spam"), spoken);
	}

	@Test
	void sanitisesWhenEnabled() {
		narrator.onTitle("ᴄʀᴀꜰᴛᴇᴅ ★", null);
		assertEquals(List.of("crafted"), spoken);
	}

	@Test
	void leavesTextAloneWhenSanitisingDisabled() {
		config.sanitiseText = false;
		narrator.onTitle("ᴄʀᴀꜰᴛᴇᴅ ★", null);
		assertEquals(List.of("ᴄʀᴀꜰᴛᴇᴅ ★"), spoken);
	}

	@Test
	void legacyFormattingCodesStrippedEvenWhenSanitisingDisabled() {
		config.sanitiseText = false;
		narrator.onTitle("§6§lGold ★", "§7sub");
		assertEquals(List.of("Gold ★. sub"), spoken);
	}

	@Test
	void symbolOnlyTitleIsSilent() {
		narrator.onTitle("★★★", null);
		assertEquals(List.of(), spoken);
	}

	@Test
	void symbolOnlyTitleWithSubtitleSpeaksSubtitle() {
		narrator.onTitle("★★★", "Boss");
		assertEquals(List.of("Boss"), spoken);
	}

	@Test
	void passesInterruptSetting() {
		narrator.onTitle("One", null);
		config.interrupt = false;
		narrator.onTitle("Two", null);
		assertEquals(List.of(true, false), interrupts);
	}

	@Test
	void lateSubtitleIsSpokenAfterDelay() {
		narrator.onTitle("Boss", null);
		narrator.onSubtitle("Phase 2", true);
		ticks(TitleNarrator.LATE_SUBTITLE_DELAY_TICKS - 1);
		assertEquals(List.of("Boss"), spoken);
		ticks(1);
		assertEquals(List.of("Boss", "Phase 2"), spoken);
		ticks(5);
		assertEquals(List.of("Boss", "Phase 2"), spoken);
	}

	@Test
	void lateSubtitleIsCancelledByFollowingTitle() {
		narrator.onTitle("First", null);
		narrator.onSubtitle("Second sub", true);
		narrator.onTitle("Second", "Second sub");
		ticks(TitleNarrator.LATE_SUBTITLE_DELAY_TICKS + 3);
		assertEquals(List.of("First", "Second. Second sub"), spoken);
	}

	@Test
	void subtitleWithoutVisibleTitleWaitsForTitle() {
		narrator.onSubtitle("World", false);
		ticks(TitleNarrator.LATE_SUBTITLE_DELAY_TICKS + 1);
		assertEquals(List.of(), spoken);
	}

	@Test
	void lateSubtitlesCanBeDisabled() {
		config.lateSubtitles = false;
		narrator.onSubtitle("Phase 2", true);
		ticks(TitleNarrator.LATE_SUBTITLE_DELAY_TICKS + 1);
		assertEquals(List.of(), spoken);
	}

	@Test
	void actionBarOffByDefault() {
		narrator.onActionBar("Bar", false);
		assertEquals(List.of(), spoken);
	}

	@Test
	void actionBarRepeatsSuppressedWhileStillBeingSent() {
		config.narrateActionBar = true;
		narrator.onActionBar("Bar", false);
		for (int i = 0; i < 5; i++) {
			clock.addAndGet(1000);
			narrator.onActionBar("Bar", false);
		}
		assertEquals(List.of("Bar"), spoken);
		clock.addAndGet(config.dedupeWindowMs);
		narrator.onActionBar("Bar", false);
		assertEquals(List.of("Bar", "Bar"), spoken);
	}

	@Test
	void actionBarLeftToVanillaWhenVanillaWillSpeakIt() {
		config.narrateActionBar = true;
		narrator.onActionBar("Respawn point set", true);
		assertEquals(List.of(), spoken);
		narrator.onActionBar("Respawn point set", false);
		assertEquals(List.of("Respawn point set"), spoken);
	}

	@Test
	void blankActionBarIsSilent() {
		config.narrateActionBar = true;
		narrator.onActionBar("  ", false);
		assertEquals(List.of(), spoken);
	}

	@Test
	void speakerFailureIsSwallowed() {
		TitleNarrator failing = new TitleNarrator(() -> config, () -> (text, interrupt) -> {
			throw new IllegalStateException("native TTS exploded");
		}, clock::get);
		assertDoesNotThrow(() -> failing.onTitle("Hello", null));
	}

	@Test
	void resetAllowsImmediateRepeatAndDropsPendingSubtitle() {
		narrator.onTitle("Spam", null);
		narrator.onSubtitle("Pending", true);
		narrator.reset();
		narrator.onTitle("Spam", null);
		ticks(TitleNarrator.LATE_SUBTITLE_DELAY_TICKS + 1);
		assertEquals(List.of("Spam", "Spam"), spoken);
	}
}
