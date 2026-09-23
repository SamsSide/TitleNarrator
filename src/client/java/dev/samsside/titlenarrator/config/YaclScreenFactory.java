package dev.samsside.titlenarrator.config;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.LongSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import dev.samsside.titlenarrator.TitleNarratorClient;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** The only class that touches YACL; load it only after checking YACL is installed. */
public final class YaclScreenFactory {
	private YaclScreenFactory() {
	}

	public static Screen create(@Nullable Screen parent) {
		return build().generateScreen(parent);
	}

	public static YetAnotherConfigLib build() {
		TitleNarratorConfig config = TitleNarratorClient.config();
		TitleNarratorConfig defaults = new TitleNarratorConfig();
		return YetAnotherConfigLib.createBuilder()
				.title(Component.translatable("titlenarrator.config.title"))
				.category(ConfigCategory.createBuilder()
						.name(Component.translatable("titlenarrator.config.category"))
						.option(toggle("enabled", defaults.enabled, () -> config.enabled, v -> config.enabled = v))
						.option(Option.<Integer>createBuilder()
								.name(Component.translatable("titlenarrator.config.narratorVolume"))
								.description(OptionDescription.of(Component.translatable("titlenarrator.config.narratorVolume.desc")))
								.binding(defaults.narratorVolume, () -> config.narratorVolume, v -> config.narratorVolume = v)
								.controller(option -> IntegerSliderControllerBuilder.create(option)
										.range(0, TitleNarratorConfig.MAX_VOLUME)
										.step(1)
										.formatValue(v -> Component.literal(v + "%")))
								.build())
						.option(toggle("narrateTitles", defaults.narrateTitles, () -> config.narrateTitles, v -> config.narrateTitles = v))
						.option(toggle("narrateSubtitles", defaults.narrateSubtitles, () -> config.narrateSubtitles, v -> config.narrateSubtitles = v))
						.option(toggle("lateSubtitles", defaults.lateSubtitles, () -> config.lateSubtitles, v -> config.lateSubtitles = v))
						.option(toggle("narrateActionBar", defaults.narrateActionBar, () -> config.narrateActionBar, v -> config.narrateActionBar = v))
						.option(toggle("sanitiseText", defaults.sanitiseText, () -> config.sanitiseText, v -> config.sanitiseText = v))
						.option(Option.<Long>createBuilder()
								.name(Component.translatable("titlenarrator.config.dedupeWindowMs"))
								.description(OptionDescription.of(Component.translatable("titlenarrator.config.dedupeWindowMs.desc")))
								.binding(defaults.dedupeWindowMs, () -> config.dedupeWindowMs, v -> config.dedupeWindowMs = v)
								.controller(option -> LongSliderControllerBuilder.create(option)
										.range(0L, TitleNarratorConfig.MAX_DEDUPE_WINDOW_MS)
										.step(250L))
								.build())
						.option(toggle("interrupt", defaults.interrupt, () -> config.interrupt, v -> config.interrupt = v))
						.option(toggle("bypassNarratorSetting", defaults.bypassNarratorSetting, () -> config.bypassNarratorSetting, v -> config.bypassNarratorSetting = v))
						.build())
				.save(TitleNarratorClient::saveConfig)
				.build();
	}

	private static Option<Boolean> toggle(String key, boolean defaultValue, Supplier<Boolean> getter, Consumer<Boolean> setter) {
		return Option.<Boolean>createBuilder()
				.name(Component.translatable("titlenarrator.config." + key))
				.description(OptionDescription.of(Component.translatable("titlenarrator.config." + key + ".desc")))
				.binding(defaultValue, getter, setter)
				.controller(TickBoxControllerBuilder::create)
				.build();
	}
}
