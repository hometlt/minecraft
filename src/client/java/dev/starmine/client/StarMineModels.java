package dev.starmine.client;

import dev.starmine.StarMine;
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
 * StarMine models by name, read from `assets/starmine/models/sc2/<name>.smm` on every resource reload.
 * A model Modkit has not imported yet is absent; its mob is then not drawn and the log says why.
 */
public final class StarMineModels implements SimpleSynchronousResourceReloadListener {
	private static final Logger LOG = LoggerFactory.getLogger("StarMine");
	private static final String[] NAMES = {"zergling"};
	private static final Map<String, SkinnedModel> MODELS = new HashMap<>();

	public static SkinnedModel get(String name) {
		return MODELS.get(name);
	}

	@Override
	public ResourceLocation getFabricId() {
		return StarMine.id("models");
	}

	@Override
	public void onResourceManagerReload(ResourceManager manager) {
		MODELS.clear();
		for (String name : NAMES) {
			ResourceLocation location = StarMine.id("models/sc2/" + name + ".smm");
			Optional<Resource> resource = manager.getResource(location);
			if (resource.isEmpty()) {
				LOG.warn("StarMine model {} is missing: import StarCraft II assets with Modkit", location);
				continue;
			}
			try (InputStream stream = resource.get().open()) {
				MODELS.put(name, SkinnedModel.read(stream));
			} catch (Exception error) {
				LOG.error("StarMine model {} could not be read", location, error);
			}
		}
	}
}
