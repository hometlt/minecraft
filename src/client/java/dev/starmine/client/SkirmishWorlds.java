package dev.starmine.client;

import com.google.gson.JsonObject;
import dev.starmine.entity.UnitKind;
import dev.starmine.world.SkirmishMap;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/** Installs a fresh native map copy; the template and any existing save remain untouched. */
final class SkirmishWorlds {
	private SkirmishWorlds() {}

	/**
	 * Owner 22.10: a new generated world made for a skirmish (cheats on, so the match may be set up); once the player is
	 * in it the skirmish is asked for, its arena by them ({@link SkirmishScreen#tick}).
	 */
	static void fresh(Minecraft mc, UnitKind.Race own, UnitKind.Race enemy) {
		String name = "StarMine-Skirmish-" + System.currentTimeMillis();
		var settings = new net.minecraft.world.level.LevelSettings(name, net.minecraft.world.level.GameType.SURVIVAL, false,
			net.minecraft.world.Difficulty.NORMAL, true, new net.minecraft.world.level.GameRules(), net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
		var options = new net.minecraft.world.level.levelgen.WorldOptions(net.minecraft.world.level.levelgen.WorldOptions.randomSeed(), true, false);
		SkirmishScreen.pending = new int[] {own.ordinal(), enemy.ordinal()};
		SkirmishScreen.pendingWait = 0;
		mc.createWorldOpenFlows().createFreshLevel(name, settings, options, registries -> registries
			.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
			.getHolderOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL).value().createWorldDimensions(), new TitleScreen());
	}
	static void open(Minecraft mc, UnitKind.Race own, UnitKind.Race enemy) {
		try (var input = SkirmishWorlds.class.getResourceAsStream("/assets/starmine/skirmish/amphion.zip")) {
			if (input == null) throw new IllegalStateException("Missing Amphion template");
			byte[] bytes = input.readAllBytes();
			String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
			if (!sha.equals("38ff928af4b04c16552eb9089b2aa312b864354a9a889b71ce2ae4445abe2a9a")) throw new IllegalStateException("Amphion template checksum mismatch");
			String name = "StarMine-Amphion-" + System.currentTimeMillis();
			Path destination = mc.getLevelSource().getBaseDir().resolve(name).toAbsolutePath().normalize();
			Files.createDirectory(destination);
			long total = 0;
			try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
				for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
					String prefix = "amphion-starmine-water-resources-3x5/";
					if (!entry.getName().startsWith(prefix)) throw new IllegalStateException("Invalid map archive root");
					Path path = destination.resolve(entry.getName().substring(prefix.length())).normalize();
					if (!path.startsWith(destination)) throw new IllegalStateException("Invalid map archive path");
					if (entry.isDirectory()) { Files.createDirectories(path); continue; }
					Files.createDirectories(path.getParent());
					try (var output = Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE_NEW)) {
						byte[] buffer = new byte[65536];
						for (int count; (count = zip.read(buffer)) != -1;) {
							if ((total += count) > 128L * 1024 * 1024) throw new IllegalStateException("Map archive too large");
							output.write(buffer, 0, count);
						}
					}
				}
			}
			JsonObject config = new JsonObject(); config.addProperty("mapId", "amphion");
			config.addProperty("playerRace", own.name()); config.addProperty("aiRace", enemy.name());
			Files.writeString(destination.resolve(SkirmishMap.CONFIG), config.toString());
			mc.createWorldOpenFlows().openWorld(name, () -> mc.setScreen(new TitleScreen()));
		} catch (Exception error) {
			org.slf4j.LoggerFactory.getLogger("StarMine/skirmish").error("Cannot open skirmish map", error);
			mc.setScreen(new net.minecraft.client.gui.screens.AlertScreen(() -> mc.setScreen(new TitleScreen()),
				Component.translatable("skirmish.starmine.failed"), Component.literal(error.getMessage())));
		}
	}
}
