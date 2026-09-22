package dev.samsside.titlenarrator;

import com.mojang.blaze3d.platform.InputConstants;
import dev.samsside.titlenarrator.config.TitleNarratorConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Keybind that turns narration on and off, confirmed with a toast (not the action bar, which we might narrate). */
public final class ToggleKeybind {
	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("titlenarrator", "main"));
	public static final KeyMapping TOGGLE = new KeyMapping(
			"key.titlenarrator.toggle", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId();

	private ToggleKeybind() {
	}

	public static void register() {
		KeyMappingHelper.registerKeyMapping(TOGGLE);
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			while (TOGGLE.consumeClick()) {
				toggle(minecraft);
			}
		});
	}

	private static void toggle(Minecraft minecraft) {
		TitleNarratorConfig config = TitleNarratorClient.config();
		config.enabled = !config.enabled;
		TitleNarratorClient.saveConfig();
		SystemToast.addOrUpdate(minecraft.gui.toastManager(), TOAST,
				Component.translatable("titlenarrator.toast.title"),
				Component.translatable(config.enabled ? "titlenarrator.toast.on" : "titlenarrator.toast.off"));
	}
}
