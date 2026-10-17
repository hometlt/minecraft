package dev.starmine.entity;

import java.util.EnumSet;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;

/**
 * SC2 attributes (owner 17.10): what a weapon's bonus damage is against ({@link UnitKind#bonusDamage}) and what some
 * spells take or leave. A StarMine unit has its kind's; a player is biological, light and heroic; a creature has the
 * closest (a golem mechanical and armored, the wither and the dragon massive and heroic, an enderman psionic…). A
 * potion ({@link dev.starmine.gear.TraitPotions}) adds one for its time; armored and light exclude each other, the
 * newer wins.
 */
public enum Trait {
	LIGHT, ARMORED, BIOLOGICAL, MECHANICAL, PSIONIC, MASSIVE, STRUCTURE, HEROIC, DETECTOR;

	public String id() {
		return name().toLowerCase();
	}

	/** A kind's own (SC2). */
	public static EnumSet<Trait> of(UnitKind kind) {
		EnumSet<Trait> set = EnumSet.noneOf(Trait.class);
		if (kind.light()) set.add(LIGHT);
		if (kind.armored()) set.add(ARMORED);
		if (kind.biological() || kind.race == UnitKind.Race.ZERG) set.add(BIOLOGICAL);
		if (kind.mechanical()) set.add(MECHANICAL);
		if (kind.psionic()) set.add(PSIONIC);
		if (kind.massive()) set.add(MASSIVE);
		if (kind.structure()) set.add(STRUCTURE);
		if (kind.heroic()) set.add(HEROIC);
		if (kind.detector()) set.add(DETECTOR);
		return set;
	}

	/** Anyone's: its own, then what potions give it for now. */
	public static Set<Trait> of(LivingEntity entity) {
		EnumSet<Trait> set;
		if (entity instanceof StarUnit unit) set = of(unit.kind);
		else if (entity instanceof Player) set = EnumSet.of(BIOLOGICAL, LIGHT, HEROIC);
		else set = creature(entity);
		for (Trait trait : values()) {
			Holder<MobEffect> effect = dev.starmine.gear.TraitPotions.effect(trait);
			if (effect == null || !entity.hasEffect(effect)) continue;
			set.add(trait);
			if (trait == ARMORED) set.remove(LIGHT);
			if (trait == LIGHT) set.remove(ARMORED);
			if (trait == MECHANICAL && !(entity instanceof StarUnit)) set.remove(BIOLOGICAL);
		}
		return set;
	}

	/** Whether anyone has it now. */
	public static boolean has(LivingEntity entity, Trait trait) {
		return of(entity).contains(trait);
	}

	/** The closest SC2 attributes of a vanilla creature. */
	private static EnumSet<Trait> creature(LivingEntity entity) {
		if (entity instanceof IronGolem) return EnumSet.of(MECHANICAL, ARMORED, MASSIVE);
		if (entity instanceof SnowGolem || entity instanceof Shulker) return EnumSet.of(MECHANICAL, ARMORED);
		if (entity instanceof WitherBoss || entity instanceof EnderDragon) return EnumSet.of(ARMORED, MASSIVE, HEROIC, PSIONIC);
		if (entity instanceof Warden) return EnumSet.of(BIOLOGICAL, ARMORED, MASSIVE, HEROIC);
		if (entity instanceof Ravager || entity instanceof Hoglin) return EnumSet.of(BIOLOGICAL, ARMORED, MASSIVE);
		if (entity instanceof EnderMan || entity instanceof Evoker || entity instanceof Witch || entity instanceof Vex)
			return EnumSet.of(BIOLOGICAL, LIGHT, PSIONIC);
		if (entity instanceof Blaze || entity instanceof Ghast) return EnumSet.of(ARMORED, PSIONIC);
		EnumSet<Trait> set = EnumSet.of(BIOLOGICAL);
		set.add(entity.getArmorValue() >= 4 || entity.getMaxHealth() > 40 ? ARMORED : LIGHT);
		return set;
	}
}
