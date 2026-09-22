package dev.samsside.titlenarrator.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.samsside.titlenarrator.ToggleKeybind;
import dev.samsside.titlenarrator.TitleNarratorClient;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class ToggleKeybindGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		RecordingSpeaker speaker = new RecordingSpeaker();
		TitleNarratorClient.setTestOverrides(speaker, () -> 0L);
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			context.runOnClient(mc -> {
				ToggleKeybind.TOGGLE.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_J));
				KeyMapping.resetMapping();
				TitleNarratorClient.config().enabled = true;
			});

			context.getInput().pressKey(ToggleKeybind.TOGGLE);
			context.waitTick();
			if (context.computeOnClient(mc -> TitleNarratorClient.config().enabled)) {
				throw new AssertionError("first press should turn narration off");
			}
			world.getServer().runCommand("title @a title \"Muted\"");
			world.getConnection().waitForClientboundPackets();
			if (!speaker.spoken().isEmpty()) {
				throw new AssertionError("nothing should be spoken while off, but spoke " + speaker.spoken());
			}

			context.getInput().pressKey(ToggleKeybind.TOGGLE);
			context.waitTick();
			if (!context.computeOnClient(mc -> TitleNarratorClient.config().enabled)) {
				throw new AssertionError("second press should turn narration back on");
			}
			world.getServer().runCommand("title @a title \"Loud\"");
			world.getConnection().waitForClientboundPackets();
			if (!speaker.spoken().equals(List.of("Loud"))) {
				throw new AssertionError("expected [Loud] but spoke " + speaker.spoken());
			}
		} finally {
			context.runOnClient(mc -> {
				ToggleKeybind.TOGGLE.setKey(InputConstants.UNKNOWN);
				KeyMapping.resetMapping();
			});
			TitleNarratorClient.setTestOverrides(null, null);
		}
	}
}
