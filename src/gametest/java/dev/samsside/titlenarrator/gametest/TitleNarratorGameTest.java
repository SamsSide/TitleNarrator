package dev.samsside.titlenarrator.gametest;

import dev.samsside.titlenarrator.TitleNarratorClient;
import dev.samsside.titlenarrator.config.TitleNarratorConfig;
import dev.samsside.titlenarrator.core.TitleNarrator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

public final class TitleNarratorGameTest implements FabricClientGameTest {
	private final RecordingSpeaker speaker = new RecordingSpeaker();
	private final AtomicLong clock = new AtomicLong(1_000_000);

	@Override
	public void runTest(ClientGameTestContext context) {
		TitleNarratorClient.setTestOverrides(speaker, clock::get);
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			fresh(context, world);
			run(world, "title @a title \"Hello\"");
			expect("plain title", "Hello");

			fresh(context, world);
			run(world, "title @a subtitle \"World\"", "title @a title \"Hello\"");
			expect("subtitle then title", "Hello. World");

			fresh(context, world);
			run(world, "title @a title \"Spam\"");
			run(world, "title @a title \"Spam\"");
			run(world, "title @a title \"Spam\"");
			expect("repeats inside window", "Spam");
			clock.addAndGet(new TitleNarratorConfig().dedupeWindowMs);
			run(world, "title @a title \"Spam\"");
			expect("repeat after window", "Spam", "Spam");

			fresh(context, world);
			run(world, "title @a title {\"text\":\"Coloured\",\"color\":\"red\",\"bold\":true}");
			expect("formatted component", "Coloured");

			fresh(context, world);
			run(world, "title @a title \"Shown\"");
			run(world, "title @a clear");
			run(world, "title @a reset");
			run(world, "title @a title \"\"");
			expect("clear, reset and empty title", "Shown");

			fresh(context, world);
			run(world, "title @a title \"Boss\"");
			run(world, "title @a subtitle \"Phase 2\"");
			context.waitTicks(TitleNarrator.LATE_SUBTITLE_DELAY_TICKS + 1);
			expect("late subtitle", "Boss", "Phase 2");

			fresh(context, world);
			run(world, "title @a title \"First\"");
			run(world, "title @a subtitle \"Second sub\"", "title @a title \"Second\"");
			context.waitTicks(TitleNarrator.LATE_SUBTITLE_DELAY_TICKS + 1);
			expect("next title while previous visible", "First", "Second. Second sub");

			fresh(context, world);
			run(world, "title @a actionbar \"Bar\"");
			expect("action bar off by default");
			context.runOnClient(mc -> TitleNarratorClient.config().narrateActionBar = true);
			run(world, "title @a actionbar \"Bar\"");
			run(world, "title @a actionbar \"Bar\"");
			expect("action bar enabled", "Bar");

			fresh(context, world);
			run(world, "title @a title \"ᴄʀᴀꜰᴛᴇᴅ ★\"");
			expect("small caps", "crafted");

			fresh(context, world);
			context.runOnClient(mc -> TitleNarratorClient.config().enabled = false);
			run(world, "title @a title \"Muted\"");
			expect("disabled");

			fresh(context, world);
		} finally {
			TitleNarratorClient.setTestOverrides(null, null);
		}
	}

	/** Clears the HUD, restores default settings and forgets previous speech. */
	private void fresh(ClientGameTestContext context, TestSingleplayerContext world) {
		run(world, "title @a clear");
		context.waitTicks(TitleNarrator.LATE_SUBTITLE_DELAY_TICKS + 1);
		context.runOnClient(mc -> {
			TitleNarratorClient.config().copyFrom(new TitleNarratorConfig());
			Objects.requireNonNull(TitleNarratorClient.narrator()).reset();
		});
		speaker.clear();
	}

	/** Runs the commands in one server tick and waits until the client has handled the resulting packets. */
	private static void run(TestSingleplayerContext world, String... commands) {
		world.getServer().runOnServer(server -> {
			for (String command : commands) {
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
			}
		});
		world.getConnection().waitForClientboundPackets();
	}

	private void expect(String scenario, String... expected) {
		List<String> actual = speaker.spoken();
		if (!actual.equals(List.of(expected))) {
			throw new AssertionError(scenario + ": expected " + List.of(expected) + " but spoke " + actual);
		}
	}
}
