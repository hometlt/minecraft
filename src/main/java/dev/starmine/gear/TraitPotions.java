package dev.starmine.gear;

import dev.starmine.StarMine;
import dev.starmine.entity.Trait;
import java.util.EnumMap;
import java.util.Map;
import net.fabricmc.fabric.api.registry.FabricBrewingRecipeRegistryBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.Potions;
import org.jetbrains.annotations.Nullable;

/**
 * Potions of SC2 attributes (owner 17.10): drunk, thrown (splash) or lingering, each gives its attribute
 * ({@link Trait#of(net.minecraft.world.entity.LivingEntity)}) for its time — to a player, a creature or a unit, never to a
 * building. Brewed from an awkward potion; redstone makes a long one (8 min instead of 3), gunpowder a splash one.
 *
 * - light (a feather): +15 % speed; armored (an iron ingot): +4 armor; biological (rotten flesh); mechanical (a copper
 *   ingot): +2 armor, not biological; psionic (an amethyst shard); massive (obsidian): no knockback, +10 health, it walks
 *   through force fields and shatters them;
 * - detector (an ender eye): sees cloaked and burrowed units within 11 SC2 units, as a detector does;
 * - radar (a glow ink sac): sees creatures and units through walls within 48 blocks (client: drawn glowing).
 */
public final class TraitPotions {
	/** The radar: not an attribute, a way of seeing. */
	public static final Holder<MobEffect> RADAR = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, StarMine.id("radar"),
		new Effect(0x60E0A0));
	private static final Map<Trait, Holder<MobEffect>> EFFECTS = new EnumMap<>(Trait.class);
	private static final Map<Holder<MobEffect>, Holder<Potion>[]> POTIONS = new java.util.LinkedHashMap<>();
	/** Ticks of a potion and of a long one (as vanilla's). */
	public static final int TICKS = 3 * 60 * 20, LONG_TICKS = 8 * 60 * 20;
	/** Blocks the radar reaches. */
	public static final double RADAR_RANGE = 48;

	private TraitPotions() {
	}

	private static final class Effect extends MobEffect {
		Effect(int colour) {
			super(MobEffectCategory.BENEFICIAL, colour);
		}
	}

	/** The effect giving an attribute (null: none can be given — structure, heroic). */
	public static @Nullable Holder<MobEffect> effect(Trait trait) {
		return EFFECTS.get(trait);
	}

	private static Holder<MobEffect> register(Trait trait, int colour) {
		Effect effect = new Effect(colour);
		switch (trait) {
			case LIGHT -> effect.addAttributeModifier(Attributes.MOVEMENT_SPEED, StarMine.id("trait_light"), 0.15, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
			case ARMORED -> effect.addAttributeModifier(Attributes.ARMOR, StarMine.id("trait_armored"), 4, AttributeModifier.Operation.ADD_VALUE);
			case MECHANICAL -> effect.addAttributeModifier(Attributes.ARMOR, StarMine.id("trait_mechanical"), 2, AttributeModifier.Operation.ADD_VALUE);
			case MASSIVE -> {
				effect.addAttributeModifier(Attributes.KNOCKBACK_RESISTANCE, StarMine.id("trait_massive"), 1, AttributeModifier.Operation.ADD_VALUE);
				effect.addAttributeModifier(Attributes.MAX_HEALTH, StarMine.id("trait_massive_health"), 10, AttributeModifier.Operation.ADD_VALUE);
			}
			default -> {
			}
		}
		Holder<MobEffect> holder = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, StarMine.id(trait.id()), effect);
		EFFECTS.put(trait, holder);
		return holder;
	}

	@SuppressWarnings("unchecked")
	private static void potions(String name, Holder<MobEffect> effect) {
		Holder<Potion> normal = Registry.registerForHolder(BuiltInRegistries.POTION, StarMine.id(name),
			new Potion(name, new MobEffectInstance(effect, TICKS)));
		Holder<Potion> longer = Registry.registerForHolder(BuiltInRegistries.POTION, StarMine.id("long_" + name),
			new Potion(name, new MobEffectInstance(effect, LONG_TICKS)));
		POTIONS.put(effect, new Holder[] {normal, longer});
	}

	public static void load() {
		Map<Trait, Item> ingredients = new EnumMap<>(Trait.class);
		register(Trait.LIGHT, 0xE8E8C0);
		ingredients.put(Trait.LIGHT, Items.FEATHER);
		register(Trait.ARMORED, 0x8090A0);
		ingredients.put(Trait.ARMORED, Items.IRON_INGOT);
		register(Trait.BIOLOGICAL, 0x80C040);
		ingredients.put(Trait.BIOLOGICAL, Items.ROTTEN_FLESH);
		register(Trait.MECHANICAL, 0xC08040);
		ingredients.put(Trait.MECHANICAL, Items.COPPER_INGOT);
		register(Trait.PSIONIC, 0x8060E0);
		ingredients.put(Trait.PSIONIC, Items.AMETHYST_SHARD);
		register(Trait.MASSIVE, 0x403050);
		ingredients.put(Trait.MASSIVE, Items.OBSIDIAN);
		register(Trait.DETECTOR, 0xE04040);
		ingredients.put(Trait.DETECTOR, Items.ENDER_EYE);
		for (Trait trait : ingredients.keySet()) potions(trait.id(), EFFECTS.get(trait));
		potions("radar", RADAR);
		FabricBrewingRecipeRegistryBuilder.BUILD.register(builder -> {
			for (Map.Entry<Trait, Item> entry : ingredients.entrySet()) mixes(builder, POTIONS.get(EFFECTS.get(entry.getKey())), entry.getValue());
			mixes(builder, POTIONS.get(RADAR), Items.GLOW_INK_SAC);
		});
	}

	private static void mixes(net.minecraft.world.item.alchemy.PotionBrewing.Builder builder, Holder<Potion>[] potions, Item ingredient) {
		builder.addMix(Potions.AWKWARD, ingredient, potions[0]);
		builder.addMix(potions[0], Items.REDSTONE, potions[1]);
	}

	/** Every potion made here (tests, the guide). */
	public static java.util.Collection<Holder<Potion>[]> all() {
		return POTIONS.values();
	}
}
