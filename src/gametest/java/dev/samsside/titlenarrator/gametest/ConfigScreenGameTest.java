package dev.samsside.titlenarrator.gametest;

import dev.samsside.titlenarrator.config.ModMenuIntegration;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.Screen;

public final class ConfigScreenGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		Screen screen = context.computeOnClient(mc -> new ModMenuIntegration().getModConfigScreenFactory().create(null));
		if (screen == null) {
			throw new AssertionError("config screen should exist when YACL is installed");
		}
		context.setScreen(() -> screen);
		context.waitTick();
		context.takeScreenshot("titlenarrator-config-screen");
		context.setScreen(() -> null);
	}
}
