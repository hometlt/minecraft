package dev.starmine.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.starmine.StarMine;
import dev.starmine.gear.Townsfolk;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.WanderingTraderRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.WanderingTrader;

/**
 * The armed villagers (owner 16.10) drawn as wandering traders in their own clothes: the game's own
 * wandering trader skin recoloured when first needed (nothing of it is shipped) — the marshal in olive
 * armour with yellow stripes, the ghost in a near-black suit with blue lights, the sheriff in a brown coat with gold. Their gun shows in their
 * crossed arms.
 */
public class TownsfolkRenderer extends WanderingTraderRenderer {
	private static final ResourceLocation BASE = ResourceLocation.withDefaultNamespace("textures/entity/wandering_trader.png");
	private static final Map<Townsfolk.Role, ResourceLocation> SKINS = new EnumMap<>(Townsfolk.Role.class);

	public TownsfolkRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public ResourceLocation getTextureLocation(WanderingTrader trader) {
		if (!(trader instanceof Townsfolk folk)) return super.getTextureLocation(trader);
		return SKINS.computeIfAbsent(folk.role, TownsfolkRenderer::paint);
	}

	/** The wandering trader's skin in the role's colours, as a texture of its own. */
	private static ResourceLocation paint(Townsfolk.Role role) {
		ResourceLocation id = StarMine.id("dynamic/" + role.name().toLowerCase());
		Minecraft mc = Minecraft.getInstance();
		try (var in = mc.getResourceManager().open(BASE)) {
			NativeImage image = NativeImage.read(in);
			for (int y = 0; y < image.getHeight(); y++)
				for (int x = 0; x < image.getWidth(); x++) {
					int abgr = image.getPixelRGBA(x, y);
					int a = abgr >>> 24, b = abgr >> 16 & 255, g = abgr >> 8 & 255, r = abgr & 255;
					if (a == 0) continue;
					float[] hsv = java.awt.Color.RGBtoHSB(r, g, b, null);
					int rgb;
					if (hsv[1] > 0.25f && hsv[0] > 0.5f && hsv[0] < 0.75f) // the blue robe
						rgb = switch (role) {
							case MARSHAL -> java.awt.Color.HSBtoRGB(0.22f, 0.25f, hsv[2] * 0.9f);
							case SHERIFF -> java.awt.Color.HSBtoRGB(0.07f, 0.55f, hsv[2] * 0.7f);
							case GHOST -> java.awt.Color.HSBtoRGB(0.6f, 0.1f, hsv[2] * 0.35f);
							case PRESERVER -> java.awt.Color.HSBtoRGB(0.12f, 0.6f, hsv[2]); // without their SC2 models: gold, and brown
							case DEHAKA -> java.awt.Color.HSBtoRGB(0.05f, 0.5f, hsv[2] * 0.6f);
						};
					else if (hsv[1] > 0.9f && hsv[0] < 0.05f) // the red trims (skin and belt are duller)
						rgb = role == Townsfolk.Role.GHOST ? java.awt.Color.HSBtoRGB(0.55f, 0.7f, 1f)
							: java.awt.Color.HSBtoRGB(0.13f, 0.8f, Math.min(1, hsv[2] * 1.2f)); // gold
					else continue;
					int nr = rgb >> 16 & 255, ng = rgb >> 8 & 255, nb = rgb & 255;
					image.setPixelRGBA(x, y, a << 24 | nb << 16 | ng << 8 | nr);
				}
			mc.getTextureManager().register(id, new DynamicTexture(image));
			return id;
		} catch (Exception e) {
			return BASE; // the plain trader then
		}
	}
}
