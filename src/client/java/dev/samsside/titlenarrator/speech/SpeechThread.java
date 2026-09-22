package dev.samsside.titlenarrator.speech;

import com.mojang.text2speech.Narrator;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Speaks on a dedicated background thread with its own text-to-speech engine, so slow native calls
 * (Windows SAPI blocks for a noticeable time) never stall the render thread.
 */
public final class SpeechThread implements SpeechOutput {
	public static final String THREAD_NAME = "Title Narrator Speech";

	private record Request(String text, boolean interrupt, float volume) {
	}

	private static final Request STOP = new Request("", false, 0);

	private final Supplier<Narrator> engineFactory;
	private final LinkedBlockingDeque<Request> queue = new LinkedBlockingDeque<>();
	private final Thread worker;
	/** Only touched on the worker thread. */
	private @Nullable Narrator engine;
	private boolean engineFailed;

	public SpeechThread(Supplier<Narrator> engineFactory) {
		this.engineFactory = engineFactory;
		this.worker = new Thread(this::run, THREAD_NAME);
		this.worker.setDaemon(true);
		this.worker.start();
	}

	/** Queues {@code text} and returns immediately; an interrupting request drops whatever is still waiting. */
	@Override
	public void submit(String text, boolean interrupt, float volume) {
		if (interrupt) {
			queue.clear();
		}
		queue.add(new Request(text, interrupt, volume));
	}

	/** Speaks what is already queued, then stops the worker and releases the engine. */
	public void close() {
		queue.add(STOP);
		try {
			worker.join(TimeUnit.SECONDS.toMillis(2));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void run() {
		try {
			while (true) {
				Request request = queue.take();
				if (request == STOP) {
					return;
				}
				speak(request);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			if (engine != null) {
				engine.destroy();
			}
		}
	}

	private void speak(Request request) {
		try {
			Narrator tts = engine();
			if (tts == null || !tts.active()) {
				warnUnavailable();
				return;
			}
			if (request.interrupt()) {
				tts.clear();
			}
			tts.say(request.text(), request.interrupt(), request.volume());
		} catch (RuntimeException e) {
			LogOnce.warn("speak-failed", "[Title Narrator] Speaking failed; further failures will not be logged", e);
		}
	}

	static void warnUnavailable() {
		LogOnce.warn("tts-unavailable", "[Title Narrator] Text-to-speech is unavailable on this system, so titles "
				+ "will not be spoken. On Linux, install the flite library (see README).");
	}

	private @Nullable Narrator engine() {
		if (engine == null && !engineFailed) {
			try {
				engine = engineFactory.get();
			} catch (RuntimeException | LinkageError e) {
				engineFailed = true;
				LogOnce.warn("engine-failed", "[Title Narrator] Could not start text-to-speech", e);
			}
		}
		return engine;
	}
}
