package dev.samsside.titlenarrator;

import dev.samsside.titlenarrator.config.TitleNarratorConfig;
import dev.samsside.titlenarrator.core.TitleNarrator;
import dev.samsside.titlenarrator.speech.DirectSpeaker;
import dev.samsside.titlenarrator.speech.GameNarratorSpeaker;
import dev.samsside.titlenarrator.speech.Speaker;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.LongSupplier;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

public final class TitleNarratorClient implements ClientModInitializer {
	private static @Nullable TitleNarratorConfig config;
	private static @Nullable Path configPath;
	private static @Nullable TitleNarrator narrator;
	private static volatile @Nullable Speaker speakerOverride;
	private static volatile @Nullable LongSupplier clockOverride;

	@Override
	public void onInitializeClient() {
		configPath = FabricLoader.getInstance().getConfigDir().resolve("titlenarrator.json");
		config = TitleNarratorConfig.load(configPath);

		Speaker gameSpeaker = new GameNarratorSpeaker();
		Speaker directSpeaker = new DirectSpeaker();
		TitleNarrator created = new TitleNarrator(
				TitleNarratorClient::config,
				() -> {
					Speaker override = speakerOverride;
					if (override != null) {
						return override;
					}
					return config().bypassNarratorSetting ? directSpeaker : gameSpeaker;
				},
				() -> {
					LongSupplier override = clockOverride;
					return override != null ? override.getAsLong() : System.currentTimeMillis();
				});
		narrator = created;
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> created.onClientTick());
	}

	public static TitleNarratorConfig config() {
		return Objects.requireNonNull(config, "Title Narrator is not initialised");
	}

	public static @Nullable TitleNarrator narrator() {
		return narrator;
	}

	public static void saveConfig() {
		if (config != null && configPath != null) {
			config.save(configPath);
		}
	}

	/** Gametest hook: route speech to {@code speaker} and read time from {@code clockMillis}. Pass nulls to restore. */
	public static void setTestOverrides(@Nullable Speaker speaker, @Nullable LongSupplier clockMillis) {
		speakerOverride = speaker;
		clockOverride = clockMillis;
	}
}
