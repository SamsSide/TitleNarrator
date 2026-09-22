package dev.samsside.titlenarrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextSanitiserTest {
	@Test
	void plainTextIsUnchanged() {
		assertEquals("Hello, World!", TextSanitiser.sanitise("Hello, World!"));
		assertEquals("Chapter 1: The End", TextSanitiser.sanitise("Chapter 1: The End"));
		assertEquals("Player's Base (PvP) & 50% off $5 1/2 +1",
				TextSanitiser.sanitise("Player's Base (PvP) & 50% off $5 1/2 +1"));
	}

	@Test
	void smallCapsBecomeAscii() {
		assertEquals("crafted", TextSanitiser.sanitise("ᴄʀᴀꜰᴛᴇᴅ"));
		assertEquals("abcdefghijklmnopqrstuvwxyz", TextSanitiser.sanitise("ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ"));
		assertEquals("q", TextSanitiser.sanitise("ꞯ"));
	}

	@Test
	void decorativeSymbolsAndEmojiAreRemoved() {
		assertEquals("Welcome", TextSanitiser.sanitise("★ Welcome ★"));
		assertEquals("Level Up!", TextSanitiser.sanitise("Level Up! 🎉"));
		assertEquals("Wave 3 Boss", TextSanitiser.sanitise("Wave 3 » Boss"));
		assertEquals("ARENA", TextSanitiser.sanitise("=== ARENA ==="));
		assertEquals("Boss", TextSanitiser.sanitise("<Boss>"));
		assertEquals("Hello!", TextSanitiser.sanitise("Hello ★!"));
	}

	@Test
	void symbolOnlyTextBecomesEmpty() {
		assertEquals("", TextSanitiser.sanitise("★★★"));
		assertEquals("", TextSanitiser.sanitise("❤️"));
		assertEquals("", TextSanitiser.sanitise("***"));
	}

	@Test
	void privateUseGlyphsAreRemoved() {
		assertEquals("Custom", TextSanitiser.sanitise(" Custom"));
		assertEquals("", TextSanitiser.sanitise(""));
	}

	@Test
	void legacyFormattingCodesAreStripped() {
		assertEquals("Gold", TextSanitiser.sanitise("§6§lGold"));
		assertEquals("Red and Blue", TextSanitiser.sanitise("§cRed§r and §9Blue"));
		assertEquals("A", TextSanitiser.sanitise("A§"));
	}

	@Test
	void compatibilityFormsAreNormalised() {
		assertEquals("Fullwidth", TextSanitiser.sanitise("Ｆｕｌｌｗｉｄｔｈ"));
		assertEquals("Bold", TextSanitiser.sanitise("𝐁𝐨𝐥𝐝"));
		assertEquals("Café", TextSanitiser.sanitise("Café"));
	}

	@Test
	void smartPunctuationBecomesAscii() {
		assertEquals("It's \"quoted\" - ok", TextSanitiser.sanitise("It’s “quoted” – ok"));
	}

	@Test
	void whitespaceIsCollapsedAndTrimmed() {
		assertEquals("lots of space", TextSanitiser.sanitise("  lots \t  of\n space "));
	}

	@Test
	void underscoresBecomeSpaces() {
		assertEquals("Player Name", TextSanitiser.sanitise("Player_Name"));
	}
}
