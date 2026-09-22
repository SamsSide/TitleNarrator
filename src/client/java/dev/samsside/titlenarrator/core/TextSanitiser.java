package dev.samsside.titlenarrator.core;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Turns stylised on-screen text into something a text-to-speech voice reads naturally. */
public final class TextSanitiser {
	/** Unicode small capitals, plus 'ǫ' which small-caps generators use for q. There is no small-cap x. */
	private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫꞯʀꜱᴛᴜᴠᴡʏᴢ";
	private static final String SMALL_CAPS_ASCII = "abcdefghijklmnopqqrstuvwyz";
	private static final String KEPT_PUNCTUATION = ".,!?'\"-:;()&%$/+";
	private static final Pattern LEGACY_FORMATTING = Pattern.compile("§.", Pattern.DOTALL);
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");
	private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile(" ([.,!?:;])");

	private TextSanitiser() {
	}

	/** Removes legacy {@code §x} formatting codes, which the game renders as formatting, never as text. */
	public static String stripLegacyFormatting(String input) {
		return LEGACY_FORMATTING.matcher(input).replaceAll("");
	}

	public static String sanitise(String input) {
		String text = Normalizer.normalize(stripLegacyFormatting(input), Normalizer.Form.NFKC);
		StringBuilder out = new StringBuilder(text.length());
		text.codePoints().forEach(cp -> out.appendCodePoint(map(cp)));
		String collapsed = WHITESPACE.matcher(out).replaceAll(" ").strip();
		return SPACE_BEFORE_PUNCTUATION.matcher(collapsed).replaceAll("$1");
	}

	private static int map(int cp) {
		int smallCap = SMALL_CAPS.indexOf(cp);
		if (smallCap >= 0) {
			return SMALL_CAPS_ASCII.charAt(smallCap);
		}
		return switch (cp) {
			case '‘', '’', '‚' -> '\'';
			case '“', '”', '„' -> '"';
			case '–', '—', '‐', '‑', '−' -> '-';
			default -> isSpoken(cp) ? cp : ' ';
		};
	}

	private static boolean isSpoken(int cp) {
		if (Character.isLetterOrDigit(cp) || Character.isWhitespace(cp) || KEPT_PUNCTUATION.indexOf(cp) >= 0) {
			return true;
		}
		int type = Character.getType(cp);
		boolean combiningMark = type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK;
		boolean variationSelector = (cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0xE0100 && cp <= 0xE01EF);
		return combiningMark && !variationSelector;
	}
}
