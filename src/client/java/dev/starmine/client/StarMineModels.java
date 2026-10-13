package dev.starmine.client;

import dev.starmine.StarMine;
import dev.starmine.entity.ZergKind;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * StarMine models by kind, read from `assets/starmine/models/sc2/<kind>.smm` on every resource reload,
 * plus the optional `<kind>_death.smm`: an SC2 death of another rig (a gore burst, ragdoll pieces)
 * that is drawn instead of the unit while it dies. A model Modkit has not imported yet is absent;
 * its mob is then not drawn and the log says why.
 */
public final class StarMineModels implements SimpleSynchronousResourceReloadListener {
	private static final Logger LOG = LoggerFactory.getLogger("StarMine");
	private static final Map<String, SkinnedModel> MODELS = new HashMap<>();

	public static SkinnedModel get(String name) {
		return MODELS.get(name);
	}

	private static boolean load(ResourceManager manager, String name) {
		ResourceLocation location = StarMine.id("models/sc2/" + name + ".smm");
		Optional<Resource> resource = manager.getResource(location);
		if (resource.isEmpty()) return false;
		try (InputStream stream = resource.get().open()) {
			MODELS.put(name, SkinnedModel.read(stream));
			return true;
		} catch (Exception error) {
			LOG.error("StarMine model {} could not be read", location, error);
			return false;
		}
	}

	@Override
	public ResourceLocation getFabricId() {
		return StarMine.id("models");
	}

	@Override
	public void onResourceManagerReload(ResourceManager manager) {
		MODELS.clear();
		int missing = 0;
		for (ZergKind kind : ZergKind.values()) {
			if (!load(manager, kind.id)) missing++;
			load(manager, kind.id + "_death");
		}
		load(manager, "creeptumor");
		if (missing > 0) LOG.warn("StarMine: {} zerg models are missing; import StarCraft II assets with Modkit", missing);
		LOG.info("StarMine: {} zerg models loaded", MODELS.size());
	}
}
