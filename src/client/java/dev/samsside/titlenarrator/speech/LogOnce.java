package dev.samsside.titlenarrator.speech;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logs a message the first time its key is seen and ignores it for the rest of the session. */
public final class LogOnce {
	private static final Logger LOGGER = LoggerFactory.getLogger("titlenarrator");
	private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

	private LogOnce() {
	}

	public static boolean warn(String key, String message, Object... args) {
		if (!SEEN.add(key)) {
			return false;
		}
		LOGGER.warn(message, args);
		return true;
	}

	public static boolean info(String key, String message, Object... args) {
		if (!SEEN.add(key)) {
			return false;
		}
		LOGGER.info(message, args);
		return true;
	}
}
