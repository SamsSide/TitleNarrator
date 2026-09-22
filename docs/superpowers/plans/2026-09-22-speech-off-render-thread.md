# Speech Off the Render Thread Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the frame stutter on Windows when a title is narrated by moving every text-to-speech engine call off the render thread.

**Architecture:** A new `SpeechThread` owns a daemon thread named `Title Narrator Speech` and its own `com.mojang.text2speech.Narrator` engine, created lazily *on that thread* (so the Windows SAPI COM object lives there). Both `Speaker` implementations keep their gating checks (Narrator option, TTS availability, voice volume) on the client thread and then only enqueue a request with `SpeechThread.submit(...)`. An interrupting request drops anything still queued.

**Tech Stack:** Java 25, Fabric (Minecraft 26.2), `com.mojang:text2speech:1.19.12`, JUnit 5 via `fabric-loader-junit`, Fabric client gametests.

**Spec:** Approved in-chat design (bounded change, no spec file). Summary:
- Root cause: `GameNarrator.saySystemNow`/`saySystemQueued` and `DirectSpeaker` call `Narrator.clear()`/`say()` on the render thread; on Windows, `NarratorWindows` makes synchronous native SAPI COM calls (`Skip`, `SetVolume`, `Speak`), which block the frame. (`NarratorLinux` is already async internally.)
- Decision: the mod uses **its own engine on its own thread**. Accepted trade-off: a title no longer cuts off vanilla's own narration (chat/menus); the two may overlap.
- What gets spoken, dedupe, interrupt and late-subtitle behaviour are unchanged.

## Global Constraints

- Minecraft 26.2, Fabric; client-only mod; no new dependencies.
- The worker thread name is exactly `Title Narrator Speech`, and it is a daemon thread.
- No engine method (`say`, `clear`, `destroy`, construction) may run on the client/render thread.
- The engine is created lazily on the first request (a game that never narrates never creates a second voice).
- Code style: tabs, `titlenarrator$` prefix on mixin members, `LogOnce` for repeated warnings, `[Title Narrator]` prefix on log messages, jspecify `@Nullable`.
- No Claude attribution in commits (no `Co-Authored-By`/`Claude-Session` trailers).

## Review Focus

1. **TTS unavailable** (engine `active()` is false, e.g. Linux without flite): no exception, `say` is never called, one warning is logged. Pinned in Task 1 (`inactiveEngineIsNeverSpoken`).
2. **Engine factory throws** (native library fails to load): the worker survives, later submits don't crash, and the factory isn't retried on every request. Pinned in Task 1 (`factoryFailureIsSwallowedAndNotRetried`).
3. **Burst of titles while the engine is busy** (a command-block clock firing every tick): with interrupt on, only the newest line is spoken after the current one; with interrupt off, all lines are spoken in order. Pinned in Task 1 (`interruptDropsQueuedRequests`, `queuedRequestsAreSpokenInOrder`).
4. **Game exit while speech is queued:** the thread is a daemon, so it never keeps the JVM alive. Pinned in Task 1 (`workerIsNamedDaemonThread`).
5. **Slow engine call** (SAPI waking the audio device): `submit` returns immediately. Pinned in Task 1 (`submitDoesNotWaitForEngine`).

---

## File Structure

- Create `src/client/java/dev/samsside/titlenarrator/speech/SpeechThread.java`: the queue, the worker thread and engine ownership. It has no Minecraft dependencies apart from the `Narrator` interface, so it can be unit-tested.
- Create `src/test/java/dev/samsside/titlenarrator/speech/SpeechThreadTest.java`: unit tests with a fake engine.
- Modify `src/client/java/dev/samsside/titlenarrator/speech/GameNarratorSpeaker.java`: gate on the client thread, then `submit`.
- Modify `src/client/java/dev/samsside/titlenarrator/speech/DirectSpeaker.java`: same; no longer reaches into vanilla's engine.
- Delete `src/client/java/dev/samsside/titlenarrator/mixin/GameNarratorAccessor.java` and its entry in `src/client/resources/titlenarrator.client.mixins.json` (it has no users after this change).
- Modify `src/client/java/dev/samsside/titlenarrator/TitleNarratorClient.java`: create one shared `SpeechThread`.
- Modify `README.md`: one note on background speech and the overlap trade-off.

---

### Task 1: `SpeechThread`

**Files:**
- Create: `src/client/java/dev/samsside/titlenarrator/speech/SpeechThread.java`
- Test: `src/test/java/dev/samsside/titlenarrator/speech/SpeechThreadTest.java`

**Interfaces:**
- Consumes: `com.mojang.text2speech.Narrator` (`say(String, boolean, float)`, `clear()`, `active()`, `destroy()`), `LogOnce.warn(String key, String message, Object... args)`.
- Produces:
  - `public SpeechThread(Supplier<Narrator> engineFactory)`: starts the daemon thread `Title Narrator Speech`.
  - `public void submit(String text, boolean interrupt, float volume)`: never blocks; with `interrupt`, it first drops the requests still queued.
  - `public void close()`: stops the worker, destroys the engine on the worker thread, and waits up to 2 s. Used by tests; the game relies on the daemon flag.
  - `public static final String THREAD_NAME = "Title Narrator Speech";`
  - `static void warnUnavailable()`: moved here from `GameNarratorSpeaker` (same `tts-unavailable` key and message). Until Task 2 removes the old copy, both exist; that's fine.

- [ ] **Step 1: Write the failing tests**

```java
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
```

`inactiveEngineIsNeverSpoken` and `factoryFailureIsSwallowedAndNotRetried` rely on `close()` draining the requests already queued before it stops (see Step 3), so no sleeps are needed.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.samsside.titlenarrator.speech.SpeechThreadTest'`
Expected: compilation FAILS with `cannot find symbol: class SpeechThread`.
(If it fails instead with `package com.mojang.text2speech does not exist`, the test classpath lacks Minecraft's libraries. Stop and report; don't add dependencies.)

- [ ] **Step 3: Write the implementation**

```java
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
public final class SpeechThread {
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
```

Note: `STOP` is compared by identity (`==`), so a real request with empty text is never mistaken for it. A `STOP` added by `close()` can be dropped by an interrupting `submit` that races with it. Only tests call `close()` and they never submit concurrently, so this is acceptable. Don't add locking.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'dev.samsside.titlenarrator.speech.SpeechThreadTest'`
Expected: 9 tests PASS. Then run `./gradlew test`: all unit tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/dev/samsside/titlenarrator/speech/SpeechThread.java src/test/java/dev/samsside/titlenarrator/speech/SpeechThreadTest.java
git commit -m "feat: add background speech thread with its own TTS engine"
```

---

### Task 2: Route both speakers through `SpeechThread`

**Files:**
- Modify: `src/client/java/dev/samsside/titlenarrator/speech/GameNarratorSpeaker.java`
- Modify: `src/client/java/dev/samsside/titlenarrator/speech/DirectSpeaker.java`
- Modify: `src/client/java/dev/samsside/titlenarrator/TitleNarratorClient.java:28-30`
- Delete: `src/client/java/dev/samsside/titlenarrator/mixin/GameNarratorAccessor.java`
- Modify: `src/client/resources/titlenarrator.client.mixins.json` (remove the `"GameNarratorAccessor",` line)
- Modify: `README.md`

**Interfaces:**
- Consumes: `SpeechThread(Supplier<Narrator>)` and `SpeechThread.submit(String, boolean, float)` from Task 1.
- Produces: `public GameNarratorSpeaker(SpeechThread speech)`, `public DirectSpeaker(SpeechThread speech)`. `GameNarratorSpeaker.vanillaSpeaksSystemMessages()` keeps its signature (HudMixin uses it). `warnUnavailable()` moves from `GameNarratorSpeaker` to `SpeechThread` (package-private static, created in Task 1).

The speakers can't be unit-tested (they need `Minecraft.getInstance()`), and the gametests replace the speaker through `setTestOverrides`. So this task is verified by the full build, the gametests, and the manual Windows check in Task 3.

- [ ] **Step 1: Rewrite `GameNarratorSpeaker`**

```java
package dev.samsside.titlenarrator.speech;

import net.minecraft.client.Minecraft;
import net.minecraft.client.NarratorStatus;
import net.minecraft.sounds.SoundSource;

/**
 * Speaks only when the vanilla Narrator option is All or System. The speech itself runs on the
 * {@link SpeechThread}, never on the render thread.
 */
public final class GameNarratorSpeaker implements Speaker {
	private final SpeechThread speech;

	public GameNarratorSpeaker(SpeechThread speech) {
		this.speech = speech;
	}

	@Override
	public void speak(String text, boolean interrupt) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.getNarrator().isActive()) {
			SpeechThread.warnUnavailable();
			return;
		}
		NarratorStatus status = minecraft.options.narrator().get();
		if (!status.shouldNarrateSystem()) {
			LogOnce.info("narrator-option", "[Title Narrator] Narrator option is {}; titles are only spoken when it is "
					+ "All or System, or when 'Speak even when Narrator is Off' is enabled", status);
			return;
		}
		speech.submit(text, interrupt, voiceVolume());
	}

	/** Whether vanilla's own system narration (e.g. {@code saySystemQueued}) would actually be spoken right now. */
	public static boolean vanillaSpeaksSystemMessages() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.getNarrator().isActive() && minecraft.options.narrator().get().shouldNarrateSystem();
	}

	static float voiceVolume() {
		return Minecraft.getInstance().options.getFinalSoundSourceVolume(SoundSource.VOICE);
	}
}
```

- [ ] **Step 2: Rewrite `DirectSpeaker`**

```java
package dev.samsside.titlenarrator.speech;

import net.minecraft.client.Minecraft;

/** Speaks regardless of the Narrator option, on the {@link SpeechThread}. */
public final class DirectSpeaker implements Speaker {
	private final SpeechThread speech;

	public DirectSpeaker(SpeechThread speech) {
		this.speech = speech;
	}

	@Override
	public void speak(String text, boolean interrupt) {
		// Vanilla's engine being inactive means the platform has no usable TTS, so ours would not work either.
		if (!Minecraft.getInstance().getNarrator().isActive()) {
			SpeechThread.warnUnavailable();
			return;
		}
		speech.submit(text, interrupt, GameNarratorSpeaker.voiceVolume());
	}
}
```

- [ ] **Step 3: Wire up `TitleNarratorClient`**

In `onInitializeClient`, replace:

```java
		Speaker gameSpeaker = new GameNarratorSpeaker();
		Speaker directSpeaker = new DirectSpeaker();
```

with:

```java
		SpeechThread speech = new SpeechThread(Narrator::getNarrator);
		Speaker gameSpeaker = new GameNarratorSpeaker(speech);
		Speaker directSpeaker = new DirectSpeaker(speech);
```

Add the imports `com.mojang.text2speech.Narrator` and `dev.samsside.titlenarrator.speech.SpeechThread`, keeping the import block sorted.

- [ ] **Step 4: Remove the now-unused accessor mixin**

```bash
git rm src/client/java/dev/samsside/titlenarrator/mixin/GameNarratorAccessor.java
```

Then remove the line `		"GameNarratorAccessor",` from `src/client/resources/titlenarrator.client.mixins.json`, so the `client` array is `["ChatListenerMixin", "HudMixin"]`.

- [ ] **Step 5: Document it in `README.md`**

At the end of the "The Narrator setting" section (after the paragraph ending "Narration volume follows the **Voice/Speech** volume slider."), add:

```markdown
Titles are spoken on a background thread with the mod's own voice, so narrating never makes the game stutter. Because of that, a title doesn't cut off something vanilla's narrator is already reading (such as chat); the two can overlap.
```

- [ ] **Step 6: Build and run every test**

Run: `./gradlew build runGametest`
Expected: BUILD SUCCESSFUL; all unit tests and all gametests PASS. No mixin errors about `GameNarratorAccessor` in the log.

- [ ] **Step 7: Commit**

```bash
git add -A src README.md
git commit -m "fix: speak titles on a background thread to stop render-thread stutter"
```

---

### Task 3: Verify on Windows (manual, by the user)

**Files:** none.

- [ ] **Step 1: Build the jar**

Run: `./gradlew build`, then copy `build/libs/title-narrator-<version>.jar` (not `-sources`) to the Windows `mods` folder, replacing the old jar.

- [ ] **Step 2: Reproduce the original scenario**

On Windows, with the Narrator option set to **System** (and again with **Speak even when Narrator is Off** enabled), step on the pressure plate that fires the `/title` command block several times, including repeatedly in quick succession.
Expected: the title is spoken as before, with no frame hitch (check with F3's frame-time graph: no spike when the title appears). With the interrupt setting on, a rapid repeat cuts off the previous line.

- [ ] **Step 3: Report the result.** If a hitch remains, capture the F3 frame-time graph and `logs/latest.log`, then return to `superpowers:systematic-debugging`. Don't guess a second fix.
