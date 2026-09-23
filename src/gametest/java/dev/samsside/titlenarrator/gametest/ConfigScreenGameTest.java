package dev.samsside.titlenarrator.gametest;

import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.samsside.titlenarrator.TitleNarratorClient;
import dev.samsside.titlenarrator.config.ModMenuIntegration;
import dev.samsside.titlenarrator.config.YaclScreenFactory;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;

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

		assertVolumeSliderShowsSavedValue(context);
	}

	private static void assertVolumeSliderShowsSavedValue(ClientGameTestContext context) {
		int saved = TitleNarratorClient.config().narratorVolume;
		try {
			TitleNarratorClient.config().narratorVolume = 37;
			Object pending = context.computeOnClient(mc -> findOption(YaclScreenFactory.build(),
					"titlenarrator.config.narratorVolume").pendingValue());
			if (!Integer.valueOf(37).equals(pending)) {
				throw new AssertionError("narrator volume slider should show the saved value 37, got " + pending);
			}
		} finally {
			TitleNarratorClient.config().narratorVolume = saved;
		}
	}

	private static Option<?> findOption(YetAnotherConfigLib yacl, String key) {
		return yacl.categories().stream()
				.flatMap(category -> category.groups().stream())
				.flatMap(group -> group.options().stream())
				.filter(option -> option.name().getContents() instanceof TranslatableContents contents
						&& contents.getKey().equals(key))
				.findFirst()
				.orElseThrow(() -> new AssertionError("config screen has no option " + key));
	}
}
