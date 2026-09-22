package dev.samsside.titlenarrator.mixin;

import dev.samsside.titlenarrator.TitleNarratorClient;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks overlay system messages, which vanilla narrates itself right after showing them on the action bar. */
@Mixin(ChatListener.class)
public abstract class ChatListenerMixin {
	@Inject(method = "handleOverlay", at = @At("HEAD"))
	private void titlenarrator$beforeOverlay(Component message, CallbackInfo ci) {
		TitleNarratorClient.setHandlingVanillaOverlay(true);
	}

	@Inject(method = "handleOverlay", at = @At("TAIL"))
	private void titlenarrator$afterOverlay(Component message, CallbackInfo ci) {
		TitleNarratorClient.setHandlingVanillaOverlay(false);
	}
}
