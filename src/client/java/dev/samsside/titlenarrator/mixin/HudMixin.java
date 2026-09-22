package dev.samsside.titlenarrator.mixin;

import dev.samsside.titlenarrator.TitleNarratorClient;
import dev.samsside.titlenarrator.core.TitleNarrator;
import dev.samsside.titlenarrator.speech.GameNarratorSpeaker;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class HudMixin {
	@Shadow
	private @Nullable Component subtitle;

	@Shadow
	private int titleTime;

	@Inject(method = "setTitle", at = @At("TAIL"))
	private void titlenarrator$onSetTitle(Component title, CallbackInfo ci) {
		TitleNarrator narrator = TitleNarratorClient.narrator();
		if (narrator != null) {
			narrator.onTitle(title.getString(), subtitle == null ? null : subtitle.getString());
		}
	}

	@Inject(method = "setSubtitle", at = @At("HEAD"))
	private void titlenarrator$onSetSubtitle(Component newSubtitle, CallbackInfo ci) {
		TitleNarrator narrator = TitleNarratorClient.narrator();
		if (narrator != null) {
			narrator.onSubtitle(newSubtitle.getString(), titleTime > 0);
		}
	}

	@Inject(method = "setOverlayMessage", at = @At("TAIL"))
	private void titlenarrator$onSetOverlayMessage(Component message, boolean animateColor, CallbackInfo ci) {
		TitleNarrator narrator = TitleNarratorClient.narrator();
		if (narrator != null) {
			boolean vanillaWillSpeak = TitleNarratorClient.isHandlingVanillaOverlay()
					&& GameNarratorSpeaker.vanillaSpeaksSystemMessages();
			narrator.onActionBar(message.getString(), vanillaWillSpeak);
		}
	}
}
