package dev.samsside.titlenarrator.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.text2speech.Narrator;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SpeechThreadTest {
	private SpeechThread speech;

	@AfterEach
	void tearDown() {
		if (speech != null) {
			speech.close();
		}
	}

	/** Records every call with the thread it ran on; can block the first say until released. */
	static final class FakeEngine implements Narrator {
		final List<String> calls = new CopyOnWriteArrayList<>();
		final List<String> threads = new CopyOnWriteArrayList<>();
		final CountDownLatch release;
		final CountDownLatch firstSayStarted = new CountDownLatch(1);
		volatile CountDownLatch spoken;
		volatile boolean active = true;

		FakeEngine(int expectedSays, boolean blockFirstSay) {
			spoken = new CountDownLatch(expectedSays);
			release = new CountDownLatch(blockFirstSay ? 1 : 0);
		}

		@Override
		public void say(String text, boolean interrupt, float volume) {
			threads.add(Thread.currentThread().getName());
			if ("boom".equals(text)) {
				spoken.countDown();
				throw new IllegalStateException("engine failure");
			}
			calls.add("say:" + text + ":" + interrupt + ":" + volume);
			firstSayStarted.countDown();
			try {
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			spoken.countDown();
		}

		@Override
		public void clear() {
			threads.add(Thread.currentThread().getName());
			calls.add("clear");
		}

		@Override
		public boolean active() {
			return active;
		}

		@Override
		public void destroy() {
			calls.add("destroy");
		}
	}

	@Test
	void submitDoesNotWaitForEngine() throws InterruptedException {
		FakeEngine engine = new FakeEngine(2, true);
		speech = new SpeechThread(() -> engine);
		assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
			speech.submit("first", false, 1.0f);
			assertTrue(engine.firstSayStarted.await(2, TimeUnit.SECONDS));
			speech.submit("second", false, 1.0f);
		});
		engine.release.countDown();
		assertTrue(engine.spoken.await(2, TimeUnit.SECONDS));
	}

	@Test
	void queuedRequestsAreSpokenInOrderOnWorkerThread() throws InterruptedException {
		FakeEngine engine = new FakeEngine(3, false);
		speech = new SpeechThread(() -> engine);
		speech.submit("a", false, 0.5f);
		speech.submit("b", false, 0.5f);
		speech.submit("c", false, 0.5f);
		assertTrue(engine.spoken.await(2, TimeUnit.SECONDS));
		assertEquals(List.of("say:a:false:0.5", "say:b:false:0.5", "say:c:false:0.5"), engine.calls);
		assertTrue(engine.threads.stream().allMatch(SpeechThread.THREAD_NAME::equals));
	}

	@Test
	void interruptDropsQueuedRequests() throws InterruptedException {
		FakeEngine engine = new FakeEngine(2, true);
		speech = new SpeechThread(() -> engine);
		speech.submit("a", false, 1.0f);
		assertTrue(engine.firstSayStarted.await(2, TimeUnit.SECONDS));
		speech.submit("b", false, 1.0f);
		speech.submit("c", false, 1.0f);
		speech.submit("d", true, 1.0f);
		engine.release.countDown();
		assertTrue(engine.spoken.await(2, TimeUnit.SECONDS));
		assertEquals(List.of("say:a:false:1.0", "clear", "say:d:true:1.0"), engine.calls);
	}

	@Test
	void engineIsCreatedLazilyOnWorkerThread() throws InterruptedException {
		FakeEngine engine = new FakeEngine(1, false);
		AtomicInteger created = new AtomicInteger();
		List<String> creatingThread = new CopyOnWriteArrayList<>();
		speech = new SpeechThread(() -> {
			created.incrementAndGet();
			creatingThread.add(Thread.currentThread().getName());
			return engine;
		});
		Thread.sleep(50);
		assertEquals(0, created.get());
		speech.submit("hello", false, 1.0f);
		assertTrue(engine.spoken.await(2, TimeUnit.SECONDS));
		assertEquals(1, created.get());
		assertEquals(List.of(SpeechThread.THREAD_NAME), creatingThread);
	}

	@Test
	void engineFailureDoesNotStopWorker() throws InterruptedException {
		FakeEngine engine = new FakeEngine(2, false);
		speech = new SpeechThread(() -> engine);
		speech.submit("boom", false, 1.0f);
		speech.submit("ok", false, 1.0f);
		assertTrue(engine.spoken.await(2, TimeUnit.SECONDS));
		assertEquals(List.of("say:ok:false:1.0"), engine.calls);
	}

	@Test
	void inactiveEngineIsNeverSpoken() throws InterruptedException {
		FakeEngine engine = new FakeEngine(1, false);
		engine.active = false;
		speech = new SpeechThread(() -> engine);
		speech.submit("hello", false, 1.0f);
		speech.close();
		assertFalse(engine.calls.stream().anyMatch(c -> c.startsWith("say")));
	}

	@Test
	void factoryFailureIsSwallowedAndNotRetried() throws InterruptedException {
		AtomicInteger attempts = new AtomicInteger();
		speech = new SpeechThread(() -> {
			attempts.incrementAndGet();
			throw new UnsatisfiedLinkError("no tts library");
		});
		speech.submit("one", false, 1.0f);
		speech.submit("two", false, 1.0f);
		speech.close();
		assertEquals(1, attempts.get());
	}

	@Test
	void closeDestroysEngineOnWorkerThread() throws InterruptedException {
		FakeEngine engine = new FakeEngine(1, false);
		speech = new SpeechThread(() -> engine);
		speech.submit("hello", false, 1.0f);
		assertTrue(engine.spoken.await(2, TimeUnit.SECONDS));
		speech.close();
		assertEquals("destroy", engine.calls.getLast());
	}

	@Test
	void workerIsNamedDaemonThread() {
		speech = new SpeechThread(() -> new FakeEngine(0, false));
		Thread worker = Thread.getAllStackTraces().keySet().stream()
				.filter(t -> SpeechThread.THREAD_NAME.equals(t.getName()))
				.findFirst()
				.orElseThrow();
		assertTrue(worker.isDaemon());
	}
}
