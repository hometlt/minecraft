package dev.starmine.gear;

import dev.starmine.StarMine;
import dev.starmine.entity.Spots;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Mercenaries (owner 19.10, SC2 Wings of Liberty's mercenary compound): a sheriff by his house sells contracts of the
 * terran mercenary companies for mineral shards (and vespene). A contract used calls its company down by drop pods round
 * the player: theirs, in their base if they have one (taking supply as SC2's mercenaries do). Mercenaries are veterans:
 * a third more health and a fifth more damage ({@link #damageBonus}), their company's name on them.
 */
public final class Mercenaries {
	/** The tag of a mercenary. */
	public static final String TAG = "starmine_mercenary";
	/** Veterans: health and damage over the kind's. */
	public static final double HEALTH = 1.3, DAMAGE = 0.2;
	private static final ResourceLocation VETERAN = StarMine.id("mercenary_health");

	/** A company: who, how many, and its price in mineral shards (and a bucket of vespene). */
	public enum Company {
		WAR_PIGS(UnitKind.MARINE, 4, 24, false),
		DEVIL_DOGS(UnitKind.HELLBAT, 3, 30, true),
		HAMMER_SECURITIES(UnitKind.MARAUDER, 3, 30, true),
		SPARTAN_COMPANY(UnitKind.CYCLONE, 2, 36, true),
		SIEGE_BREAKERS(UnitKind.SIEGE_TANK, 2, 44, true),
		HELS_ANGELS(UnitKind.VIKING, 3, 40, true),
		DUSK_WINGS(UnitKind.BANSHEE, 2, 44, true),
		JACKSONS_REVENGE(UnitKind.BATTLECRUISER, 1, 80, true);

		public final UnitKind kind;
		public final int count, minerals;
		public final boolean vespene;

		Company(UnitKind kind, int count, int minerals, boolean vespene) {
			this.kind = kind;
			this.count = count;
			this.minerals = minerals;
			this.vespene = vespene;
		}

		public String id() {
			return name().toLowerCase();
		}
	}

	public static final Map<Company, Item> CONTRACTS = new EnumMap<>(Company.class);

	static {
		for (Company company : Company.values())
			CONTRACTS.put(company, Registry.register(BuiltInRegistries.ITEM, StarMine.id("contract_" + company.id()),
				new Contract(company, new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON))));
	}

	private Mercenaries() {
	}

	public static void load() {
		net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(net.minecraft.world.item.CreativeModeTabs.COMBAT)
			.register(entries -> CONTRACTS.values().forEach(entries::accept));
	}

	/** The sheriff's contracts on offer: what, for how many shards, whether a bucket of vespene too. */
	public static List<Townsfolk.Ware> wares() {
		return java.util.Arrays.stream(Company.values())
			.map(company -> new Townsfolk.Ware(() -> new ItemStack(CONTRACTS.get(company)), company.minerals, company.vespene)).toList();
	}

	/** Its company called down round `player`: theirs (in their base here if they have one). */
	public static List<StarUnit> call(ServerLevel level, Player player, Company company) {
		Hive base = HiveSavedData.get(level.getServer()).baseOrHome(player.getUUID(), level.dimension().location().toString());
		List<StarUnit> called = new java.util.ArrayList<>();
		for (int i = 0; i < company.count; i++) {
			StarUnit unit = StarMine.type(company.kind).create(level);
			if (unit == null) continue;
			double angle = i * Math.PI * 2 / company.count + level.random.nextDouble() * 0.5;
			Vec3 at = Spots.outside(level, company.kind, player.position(), angle, player.getBbWidth() / 2 + company.kind.width / 2 + 2);
			unit.moveTo(at.x, at.y + (company.kind.flying ? 3 : 0), at.z, (float) Math.toDegrees(angle), 0);
			unit.setOwner(player.getUUID());
			if (base != null) unit.joinHive(base);
			veteran(unit, company);
			unit.setPersistenceRequired();
			level.addFreshEntity(unit);
			level.sendParticles(ParticleTypes.FLAME, at.x, at.y + 1, at.z, 20, 0.3, 1.2, 0.3, 0.05); // the drop pod's trail
			level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1, 0, 0, 0, 0);
			called.add(unit);
		}
		if (base != null) HiveSavedData.get(level.getServer()).setDirty();
		level.playSound(null, player.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.8f, 1.3f);
		return called;
	}

	/** A veteran of its company: more health, its company's name. */
	static void veteran(StarUnit unit, Company company) {
		unit.addTag(TAG);
		var health = unit.getAttribute(Attributes.MAX_HEALTH);
		if (health != null && health.getModifier(VETERAN) == null)
			health.addPermanentModifier(new AttributeModifier(VETERAN, HEALTH - 1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		unit.setHealth(unit.getMaxHealth());
		unit.setCustomName(Component.translatable("mercenary.starmine." + company.id()));
	}

	/** A mercenary's extra damage on a hit of `base` (a fifth). */
	public static double damageBonus(StarUnit attacker, double base) {
		return attacker.getTags().contains(TAG) ? base * DAMAGE : 0;
	}

	/** A contract: used, its company comes. */
	static final class Contract extends Item {
		final Company company;

		Contract(Company company, Properties properties) {
			super(properties);
			this.company = company;
		}

		@Override
		public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
			ItemStack stack = player.getItemInHand(hand);
			if (!(level instanceof ServerLevel server)) return InteractionResultHolder.success(stack);
			if (call(server, player, company).isEmpty()) return InteractionResultHolder.fail(stack);
			if (!player.getAbilities().instabuild) stack.shrink(1);
			return InteractionResultHolder.consume(stack);
		}

		@Override
		public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
			lines.add(Component.translatable("item.starmine.contract.tip", company.count,
				Component.translatable("entity.starmine." + company.kind.id)).withStyle(ChatFormatting.GRAY));
		}
	}
}
