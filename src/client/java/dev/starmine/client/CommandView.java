package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.starmine.StarMine;
import dev.starmine.colony.ColonyNetwork;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.HiveTerrain;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * The terran command view (owner 14.10): the player looks down on their base like a spectator and
 * commands it as in StarCraft II — {@link CommandScreen} is the interface, this class the view.
 * Owner 17.10: the camera goes anywhere — the minimap, the keys and the edges move it however far from the player;
 * the server then loads and sends the world round the camera ({@link dev.starmine.colony.CommanderEye}).
 *
 * - Camera: a client-only marker entity the game renders from (the player's body stays where it is,
 *   so chunks around it stay loaded); it looks down at {@link #PITCH} from its own height, as in Reign of
 *   Nether (owner 14.10), facing north — straight up the minimap, as in SC2 (owner 16.10) — Q/E turn it by a quarter ({@link #rotate});
 *   WASD, arrows, the screen edges and the minimap ({@link CommandMinimap}) move it. Its height is the
 *   player's (CLOUD_TASK_2): anywhere from the bottom of the world to its top, the wheel and PageUp/PageDown
 *   change it smoothly ({@link #climb}); nothing picks it by itself. (It used to stand over the client's
 *   MOTION_BLOCKING_NO_LEAVES height, which the server does not send: the client read the bottom of the
 *   world, and the view went down into the caves.)
 * - Fog of war: only what the base's units and buildings and the player's body see is shown
 *   ({@link #visible}: within sight and not behind blocks); other entities are not drawn
 *   ({@link dev.starmine.mixin.client.HideInFogMixin}), blocks never seen are not drawn ({@link BlockFog}),
 *   and the ground is fogged: clear in sight, dimmed where seen before, dark where never seen ({@link #drawFog}).
 *   Detectors (overseer, observer, turrets, spore crawler, photon cannon) sense creatures behind walls
 *   within their sight: those are drawn with a glowing outline seen through blocks ({@link #silhouette}).
 * - Selection: unit ids; rings under selected (green for the base's own, red for others) and hovered
 *   units; a building being placed is drawn translucent where it would stand, green when the site is
 *   good ({@link HiveTerrain#prepare} check, no building in the way), red when not.
 */
public final class CommandView {
	public static final double PITCH = 60, PLAYER_SIGHT = 24;
	/** The yaw the view opens with: north, up on the minimap (owner 16.10, as in SC2); Q/E turn it by quarters. */
	static final double NORTH = 180;
	/** The yaw the view turns to (north plus quarters). */
	static double targetYaw = NORTH;
	static boolean active;
	private static Marker camera;
	private static CameraType savedCameraType;
	private static boolean savedHideGui;
	static double targetX, targetZ, groundY, yaw;
	/** The camera's height and the height it eases to. */
	static double height, targetHeight;
	static double panX, panZ, turn, climbing;
	static final Set<Integer> selection = new LinkedHashSet<>();
	/** The player's own body is selected as a hero (owner 17.10; {@link HeroPilot}); then no unit is. */
	static boolean hero;
	/** A building being placed (its ghost follows the cursor), and the ghost's current site. */
	static UnitKind placing;
	static BlockPos ghostSite;
	static boolean ghostValid;
	static Integer hovered;
	/** Owner 17.10: what is looked at without being commanded — a creature (a monster, an animal), or a resource block (a crystal, a geyser). */
	static Integer inspected;
	static BlockPos inspectedBlock;
	private static final SkinnedModelDrawer GHOST = new SkinnedModelDrawer();
	private static float[] ghostPalette = new float[0];

	private CommandView() {
	}

	public static boolean active() {
		return active;
	}

	/** Whether the player's body is held where it stands: the view is open and it is not walking as the hero (owner 18.10). */
	public static boolean bodyHeld() {
		return active && !HeroPilot.busy();
	}

	static boolean heroSelected() {
		return hero && selection.isEmpty();
	}

	/** Selects the player as the hero (the hero button, F4, a click on their body). */
	static void selectHero() {
		selection.clear();
		hero = true;
	}

	/** Whether the player's body is under a screen point. */
	static boolean heroAt(double mouseX, double mouseY, int width, int height) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return false;
		Vec3[] ray = ray(mouseX, mouseY, width, height);
		return mc.player.getBoundingBox().inflate(0.4).clip(ray[0], ray[0].add(ray[1].scale(300))).isPresent();
	}

	/** Any living creature under a screen point (a hero attacks any of them), the player's body aside. */
	static @org.jetbrains.annotations.Nullable net.minecraft.world.entity.LivingEntity creature(double mouseX, double mouseY, int width, int height) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return null;
		Vec3[] ray = ray(mouseX, mouseY, width, height);
		Vec3 end = ray[0].add(ray[1].scale(300));
		net.minecraft.world.entity.LivingEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof net.minecraft.world.entity.LivingEntity living) || entity == mc.player || !living.isAlive()
				|| entity instanceof StarUnit unit && !visible(unit)) continue;
			var clip = living.getBoundingBox().inflate(0.3).clip(ray[0], end);
			if (clip.isEmpty()) continue;
			double d = clip.get().distanceToSqr(ray[0]);
			if (d < bestDistance) {
				bestDistance = d;
				best = living;
			}
		}
		return best;
	}

	/** The command key: the view opens only while the player carries a command panel (owner 14.10). */
	static void enterWithPanel(Minecraft mc) {
		if (mc.player == null) return;
		if (!dev.starmine.colony.CommandPanel.carried(mc.player)) {
			mc.player.displayClientMessage(Component.translatable("message.starmine.need_panel"), true);
			return;
		}
		enter(mc);
	}

	/** Enters the view (the player must have a base in this dimension). */
	static void enter(Minecraft mc) {
		if (mc.player == null || mc.level == null) return;
		// owner 16.10: the view works without a base too (the map, the marks of the bases); there is just nothing to command
		if (!BaseStatusClient.status.present()) mc.player.displayClientMessage(Component.translatable("message.starmine.no_base_view"), true);
		camera = new Marker(EntityType.MARKER, mc.level);
		targetX = mc.player.getX();
		targetZ = mc.player.getZ();
		yaw = targetYaw = NORTH; // owner 16.10: the view looks straight up the map, as in SC2
		groundY = ground(mc.level, targetX, targetZ);
		height = targetHeight = Mth.clamp(Math.max(groundY, mc.player.getEyeY()) + 22, mc.level.getMinBuildHeight(), mc.level.getMaxBuildHeight());
		placeCamera(true);
		savedCameraType = mc.options.getCameraType();
		savedHideGui = mc.options.hideGui;
		mc.options.setCameraType(CameraType.FIRST_PERSON);
		mc.options.hideGui = true;
		mc.gameRenderer.setRenderHand(false);
		mc.setCameraEntity(camera);
		active = true;
		BlockFog.start(mc);
		mc.setScreen(new CommandScreen());
		tellEye(true);
	}

	static void exit(Minecraft mc) {
		if (!active) return;
		active = false;
		placing = null;
		ghostSite = null;
		BlockFog.stop(mc);
		LOOKS.clear();
		eyeX = Double.NaN;
		if (ClientPlayNetworking.canSend(dev.starmine.colony.CommanderEye.Look.TYPE))
			ClientPlayNetworking.send(new dev.starmine.colony.CommanderEye.Look(false, 0, 0, 0));
		if (mc.player != null) mc.setCameraEntity(mc.player);
		if (savedCameraType != null) mc.options.setCameraType(savedCameraType);
		mc.options.hideGui = savedHideGui;
		mc.gameRenderer.setRenderHand(true);
		camera = null;
		// the screen is already closing (exit is called from its removed()) or gone
	}

	/** Client tick: leaves the view when its screen is gone, moves the camera. */
	static void tick(Minecraft mc) {
		if (!active) return;
		if (!(mc.screen instanceof CommandScreen) || mc.player == null || mc.level == null || !mc.player.isAlive()) {
			exit(mc);
			return;
		}
		if (climbing != 0) climb(climbing * (0.8 + zoom() * 0.04));
		height += (targetHeight - height) * 0.35;
		double step = 0.6 + zoom() * 0.06;
		double sin = Math.sin(Math.toRadians(yaw)), cos = Math.cos(Math.toRadians(yaw));
		// forward is where the camera looks (minecraft: -sin, cos)
		double forward = panZ, right = panX;
		targetX += (-sin * forward - cos * right) * step;
		targetZ += (cos * forward - sin * right) * step;
		yaw += Mth.clamp(targetYaw - yaw, -6, 6); // eases to the next quarter
		// owner 17.10: anywhere on the map, wherever the player stands — the server loads and sends the world round the eye
		var border = mc.level.getWorldBorder();
		targetX = Mth.clamp(targetX, border.getMinX(), border.getMaxX());
		targetZ = Mth.clamp(targetZ, border.getMinZ(), border.getMaxZ());
		tellEye(false);
		groundY = Mth.lerp(0.25, groundY, ground(mc.level, targetX, targetZ));
		placeCamera(false);
		updateSights(mc);
		BlockFog.tick(mc);
	}

	private static double eyeX = Double.NaN, eyeZ;
	private static int eyeAge;

	/** Tells the server where the view looks ({@link dev.starmine.colony.CommanderEye}) when it has moved a few blocks, or now. */
	private static void tellEye(boolean now) {
		if (!ClientPlayNetworking.canSend(dev.starmine.colony.CommanderEye.Look.TYPE)) return;
		eyeAge++;
		if (!now && !Double.isNaN(eyeX) && Math.abs(targetX - eyeX) + Math.abs(targetZ - eyeZ) < 6 && eyeAge < 100) return;
		eyeX = targetX;
		eyeZ = targetZ;
		eyeAge = 0;
		ClientPlayNetworking.send(new dev.starmine.colony.CommanderEye.Look(true, targetX, groundY, targetZ));
	}

	/** Raises (or with a negative number lowers) the camera, between the bottom and the top of the world. */
	static void climb(double blocks) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		targetHeight = Mth.clamp(targetHeight + blocks, mc.level.getMinBuildHeight(), mc.level.getMaxBuildHeight());
	}

	/** How high the camera is over the ground it looks at (at least a few blocks: under the ground it looks down a cave). */
	static double zoom() {
		return Math.max(6, height - groundY);
	}

	/** Turns the isometric view by a quarter (-1 left, 1 right). */
	static void rotate(int quarters) {
		targetYaw += 90 * quarters;
	}

	/** Centres the view on a world point (the minimap). */
	static void lookAt(double x, double z) {
		targetX = x;
		targetZ = z;
	}

	/** Fog darkness at a column for the minimap: 0 in sight, dimmed where seen before, dark where never seen. */
	static float fogAt(ClientLevel level, int x, int z) {
		if (seen(x + 0.5, z + 0.5, 0)) return 0;
		var explored = EXPLORED.get(level.dimension());
		return explored != null && explored.contains(net.minecraft.world.level.ChunkPos.asLong(x >> 1, z >> 1)) ? FOG_EXPLORED : FOG_UNSEEN;
	}

	/** The top of the ground at a column (WORLD_SURFACE: a height the server sends to the client); for pan speed and the look point only. */
	private static double ground(ClientLevel level, double x, double z) {
		return level.getHeight(Heightmap.Types.WORLD_SURFACE, Mth.floor(x), Mth.floor(z));
	}

	private static void placeCamera(boolean jump) {
		if (camera == null) return;
		double back = zoom() / Math.tan(Math.toRadians(PITCH));
		double sin = Math.sin(Math.toRadians(yaw)), cos = Math.cos(Math.toRadians(yaw));
		double x = targetX + sin * back, z = targetZ - cos * back, y = height;
		if (jump) camera.setPos(x, y, z);
		camera.xo = camera.getX();
		camera.yo = camera.getY();
		camera.zo = camera.getZ();
		camera.yRotO = camera.getYRot();
		camera.xRotO = camera.getXRot();
		camera.setPos(x, y, z);
		camera.setYRot((float) yaw);
		camera.setXRot((float) PITCH);
	}

	// ---- picking ----

	/** The world ray under a screen point (GUI coordinates of a screen `width` x `height`). */
	static Vec3[] ray(double mouseX, double mouseY, int width, int height) {
		Minecraft mc = Minecraft.getInstance();
		Camera cam = mc.gameRenderer.getMainCamera();
		Vector3f look = cam.getLookVector(), up = cam.getUpVector(), left = cam.getLeftVector();
		double tan = Math.tan(Math.toRadians(mc.options.fov().get()) / 2);
		double aspect = width / (double) height;
		double nx = (2 * mouseX / width - 1) * tan * aspect, ny = (1 - 2 * mouseY / height) * tan;
		Vec3 dir = new Vec3(look.x() - left.x() * nx + up.x() * ny, look.y() - left.y() * nx + up.y() * ny, look.z() - left.z() * nx + up.z() * ny).normalize();
		return new Vec3[] {cam.getPosition(), dir};
	}

	/** Where a world point is on the screen (GUI coordinates), or null behind the camera. */
	static double[] project(Vec3 point, int width, int height) {
		Minecraft mc = Minecraft.getInstance();
		Camera cam = mc.gameRenderer.getMainCamera();
		Vector3f look = cam.getLookVector(), up = cam.getUpVector(), left = cam.getLeftVector();
		Vec3 rel = point.subtract(cam.getPosition());
		double z = rel.x * look.x() + rel.y * look.y() + rel.z * look.z();
		if (z <= 0.1) return null;
		double tan = Math.tan(Math.toRadians(mc.options.fov().get()) / 2), aspect = width / (double) height;
		double sx = -(rel.x * left.x() + rel.y * left.y() + rel.z * left.z()) / (z * tan * aspect);
		double sy = (rel.x * up.x() + rel.y * up.y() + rel.z * up.z()) / (z * tan);
		return new double[] {(sx + 1) / 2 * width, (1 - sy) / 2 * height};
	}

	/** The block face under a screen point, or null. */
	static BlockHitResult block(double mouseX, double mouseY, int width, int height) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || camera == null) return null;
		Vec3[] ray = ray(mouseX, mouseY, width, height);
		BlockHitResult hit = mc.level.clip(new ClipContext(ray[0], ray[0].add(ray[1].scale(300)), ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, camera));
		return hit.getType() == HitResult.Type.BLOCK ? hit : null;
	}

	/** The nearest visible StarMine unit under a screen point, or null. */
	static StarUnit unit(double mouseX, double mouseY, int width, int height) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return null;
		Vec3[] ray = ray(mouseX, mouseY, width, height);
		Vec3 end = ray[0].add(ray[1].scale(300));
		StarUnit best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof StarUnit unit) || !unit.isAlive() || !visible(unit)) continue;
			AABB box = unit.getBoundingBox().inflate(unit.kind.structure() ? 0 : 0.3);
			var clip = box.clip(ray[0], end);
			if (clip.isEmpty()) continue;
			double d = clip.get().distanceToSqr(ray[0]);
			if (d < bestDistance) {
				bestDistance = d;
				best = unit;
			}
		}
		return best;
	}

	/** Whether a block is a resource to look at: a mineral crystal, a vespene or terrazine outlet, an empty vent. */
	static boolean resource(net.minecraft.world.level.block.state.BlockState state) {
		return dev.starmine.mineral.Minerals.isCrystal(state) || dev.starmine.vespene.Vespene.isOutlet(state)
			|| state.getBlock() instanceof dev.starmine.vespene.VespeneBlock;
	}

	/** The box of the resource looked at (a crystal's whole 2 x 2 x 2), or null when it is gone. */
	static @org.jetbrains.annotations.Nullable AABB inspectedBox(ClientLevel level) {
		if (inspectedBlock == null || !resource(level.getBlockState(inspectedBlock))) return null;
		BlockPos core = dev.starmine.mineral.Minerals.core(level, inspectedBlock);
		return core != null ? new AABB(core).expandTowards(1, 1, 1) : new AABB(inspectedBlock);
	}

	/** Looks at a creature or a resource instead of commanding: the selection is emptied. */
	static void inspect(@org.jetbrains.annotations.Nullable Integer entity, @org.jetbrains.annotations.Nullable BlockPos block) {
		selection.clear();
		hero = false;
		inspected = entity;
		inspectedBlock = block;
	}

	/** Whether a unit belongs to this player's base. */
	static boolean own(StarUnit unit) {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && unit.owner().map(mc.player.getUUID()::equals).orElse(false);
	}

	/** The selected entities still around, in selection order. */
	static List<StarUnit> selected() {
		Minecraft mc = Minecraft.getInstance();
		List<StarUnit> units = new ArrayList<>();
		if (mc.level == null) return units;
		selection.removeIf(id -> !(mc.level.getEntity(id) instanceof StarUnit unit) || !unit.isAlive());
		for (int id : selection) if (mc.level.getEntity(id) instanceof StarUnit unit) units.add(unit);
		return units;
	}

	/** Sends a command for the own selected units. */
	static void send(String action, Vec3 at, int target, String arg) {
		int[] ids = selected().stream().filter(CommandView::own).mapToInt(Entity::getId).toArray();
		if (ids.length == 0) return;
		ClientPlayNetworking.send(new ColonyNetwork.UnitCommand(ids, action, at.x, at.y, at.z, target, arg == null ? "" : arg));
		Minecraft mc = Minecraft.getInstance();
		Vec3 point = target > 0 && mc.level != null && mc.level.getEntity(target) instanceof Entity aimed ? aimed.position() : at;
		if (!action.equals("train") && !action.equals("cancel") && !action.equals("build") && !(action.equals("ability") && point.lengthSqr() == 0))
			Orders.sent(ids, action, point);
		if (action.equals("ability") && arg != null && arg.equals(dev.starmine.ability.Ability.SCANNER_SWEEP.id) && point.lengthSqr() > 0
			&& selected().stream().anyMatch(unit -> own(unit) && unit.mode() == dev.starmine.ability.Modes.ORBITAL)) scanned(point);
	}

	// ---- fog of war ----

	private record Sight(double x, double y, double z, double radius, boolean detector) {
	}

	private static List<Sight> sights = List.of();

	/** A scanner sweep the player cast: its sight (from high over its place, a detector) and when it ends. */
	private record Scan(Sight sight, long until) {
	}

	private static final List<Scan> SCANS = new ArrayList<>();

	/** The player cast a scanner sweep at `at`: its place is seen (fog lifted, cloaked units shown) for the sweep's time. */
	static void scanned(Vec3 at) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		double ground = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, Mth.floor(at.x), Mth.floor(at.z));
		SCANS.add(new Scan(new Sight(at.x, Math.max(at.y, ground) + 24, at.z, Math.hypot(dev.starmine.ability.TerranAbilities.SCAN_RADIUS, 24), true),
			mc.level.getGameTime() + dev.starmine.ability.TerranAbilities.SCAN_TICKS));
	}

	/** What the base's units and buildings and the player's body see (recomputed each tick and frame). */
	private static void updateSights(Minecraft mc) {
		List<Sight> list = new ArrayList<>();
		if (mc.player != null) list.add(new Sight(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(), PLAYER_SIGHT, false));
		if (mc.level != null)
			for (Entity entity : mc.level.entitiesForRendering())
				if (entity instanceof StarUnit unit && unit.isAlive() && own(unit))
					list.add(new Sight(unit.getX(), unit.getY() + Math.max(1, unit.getBbHeight() * 0.85), unit.getZ(), unit.kind.sight(), unit.kind.detector()));
		long now = mc.level == null ? 0 : mc.level.getGameTime();
		SCANS.removeIf(scan -> scan.until() < now);
		for (Sight scan : SCANS.stream().map(Scan::sight).toList()) list.add(scan); // owner 18.10: a scanner sweep shows its place
		sights = list;
		if (mc.level != null) VisionBlockers.refresh(mc.level);
	}

	/** How an entity was last found: seen, sensed by a detector only, or neither; and when (ticks). */
	private record Look(long tick, boolean seen, boolean sensed) {
	}

	private static final java.util.Map<Integer, Look> LOOKS = new java.util.HashMap<>();
	/** Ticks a look at an entity is kept before it is looked at again. */
	private static final int LOOK_TICKS = 4;

	/** Shown in the command view: own units always; others within sight and not behind blocks, or sensed by a detector. */
	public static boolean visible(Entity entity) {
		if (!active) return true;
		if (entity instanceof StarUnit unit && own(unit)) return true;
		if (entity == Minecraft.getInstance().player) return true;
		Look look = look(entity);
		return look.seen || look.sensed;
	}

	/** Sensed by a detector behind blocks (not seen): drawn as an outline through the wall. */
	public static boolean silhouette(Entity entity) {
		if (!active || entity instanceof StarUnit unit && own(unit) || entity == Minecraft.getInstance().player) return false;
		Look look = look(entity);
		return look.sensed && !look.seen;
	}

	private static Look look(Entity entity) {
		Minecraft mc = Minecraft.getInstance();
		long now = mc.level == null ? 0 : mc.level.getGameTime();
		Look look = LOOKS.get(entity.getId());
		if (look != null && now - look.tick < LOOK_TICKS) return look;
		boolean seen = false, sensed = false;
		Vec3 centre = entity.position().add(0, entity.getBbHeight() / 2, 0);
		double margin = entity.getBbWidth() / 2;
		for (Sight sight : sights) {
			double dx = centre.x - sight.x, dy = centre.y - sight.y, dz = centre.z - sight.z, r = sight.radius + margin;
			if (dx * dx + dy * dy + dz * dz > r * r) continue;
			if (sight.detector) sensed = true;
			if (!seen && mc.level != null && clear(mc, new Vec3(sight.x, sight.y, sight.z), entity, centre)) seen = true;
			if (seen) break;
		}
		look = new Look(now, seen, sensed);
		LOOKS.put(entity.getId(), look);
		if (LOOKS.size() > 4096) LOOKS.clear();
		return look;
	}

	/** No block between an eye and an entity (its middle, or the top of a tall one). */
	private static boolean clear(Minecraft mc, Vec3 eye, Entity entity, Vec3 centre) {
		Entity viewer = camera != null ? camera : mc.player;
		for (Vec3 to : new Vec3[] {centre, entity.position().add(0, entity.getBbHeight() * 0.9, 0)}) {
			if (VisionBlockers.hides(eye, to)) continue; // in or behind a sight blocker
			BlockHitResult hit = mc.level.clip(new ClipContext(eye, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, viewer));
			if (hit.getType() == HitResult.Type.MISS) return true;
		}
		return false;
	}

	static boolean seen(double x, double z, double margin) {
		for (Sight sight : sights) {
			double dx = x - sight.x, dz = z - sight.z, r = sight.radius + margin;
			if (dx * dx + dz * dz <= r * r && !VisionBlockers.hides(sight.x, sight.z, x, z)) return true;
		}
		return false;
	}

	// ---- world drawing ----

	/** After entities: fog, rings, the ghost of a building being placed. */
	static void render(WorldRenderContext context) {
		if (!active) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || context.consumers() == null || context.matrixStack() == null) return;
		Vec3 cam = context.camera().getPosition();
		PoseStack pose = context.matrixStack();
		MultiBufferSource buffers = context.consumers();
		pose.pushPose();
		pose.translate(-cam.x, -cam.y, -cam.z);
		drawFog(mc.level, pose, buffers.getBuffer(RenderType.debugQuads()));
		VertexConsumer lines = buffers.getBuffer(RenderType.lines());
		for (StarUnit unit : selected()) ring(pose, lines, unit, own(unit) ? 0xFF40FF40 : 0xFFFF4040);
		if (heroSelected() && mc.player != null) ring(pose, lines, mc.player, 0xFF40E0FF);
		// owner 17.10: what the selected workers mine — their area (yellow) and the block each works (a line to it, its outline)
		for (StarUnit unit : selected()) {
			if (!own(unit) || !unit.kind.worker()) continue;
			AABB area = unit.shownArea();
			if (area != null) net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, lines, area, 1f, 0.85f, 0.2f, 1f);
			BlockPos target = unit.shownTarget();
			if (target != null) {
				boolean crystal = dev.starmine.mineral.Minerals.isCrystal(mc.level.getBlockState(target));
				float r = crystal ? 0.3f : 1f, g = crystal ? 0.8f : 0.8f, b = crystal ? 1f : 0.3f;
				net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, lines, new AABB(target).inflate(0.02), r, g, b, 1f);
				Vec3 from = unit.position().add(0, unit.getBbHeight() / 2, 0), to = Vec3.atCenterOf(target);
				PoseStack.Pose last = pose.last();
				Vec3 d = to.subtract(from).normalize();
				lines.addVertex(last, (float) from.x, (float) from.y, (float) from.z).setColor(r, g, b, 0.8f).setNormal(last, (float) d.x, (float) d.y, (float) d.z);
				lines.addVertex(last, (float) to.x, (float) to.y, (float) to.z).setColor(r, g, b, 0.8f).setNormal(last, (float) d.x, (float) d.y, (float) d.z);
			}
		}
		Orders.render(pose, lines, context.tickCounter().getGameTimeDeltaPartialTick(false));
		if (hovered != null && mc.level.getEntity(hovered) instanceof StarUnit unit && !selection.contains(hovered)) ring(pose, lines, unit, 0xA0FFFFFF);
		if (inspected != null && mc.level.getEntity(inspected) instanceof net.minecraft.world.entity.LivingEntity looked && looked.isAlive())
			ring(pose, lines, looked, 0xFFFFD040);
		else inspected = null;
		AABB resource = inspectedBox(mc.level);
		if (resource != null) net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, lines, resource.inflate(0.03), 1f, 0.85f, 0.25f, 1f);
		else inspectedBlock = null;
		if (placing != null && ghostSite != null) drawGhost(pose, buffers, placing, ghostSite, ghostValid ? GHOST_GOOD : GHOST_BAD);
		// what detectors sense behind walls: a frame of its body seen through the blocks (as well as the glowing
		// outline, which shader packs may not draw)
		VertexConsumer through = buffers.getBuffer(SeeThrough.LINES);
		for (Entity entity : mc.level.entitiesForRendering())
			if (entity.isAlive() && silhouette(entity))
				net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, through, entity.getBoundingBox(), 1.0f, 0.35f, 0.3f, 0.9f);
		pose.popPose();
	}

	/**
	 * Fog of war as in SC2 (owner 14.10: improved): ground in sight is clear, ground seen before is
	 * dimmed (its creatures are not shown), ground never seen is dark. The fog lies on the surface on a
	 * {@link #FOG_CELL}-block grid with a darkness per corner, so its edges are soft and it follows the
	 * terrain. What was seen is remembered per dimension for the session.
	 */
	private static final int FOG_CELL = 2, FOG_RADIUS = 104;
	private static final float FOG_EXPLORED = 0.5f, FOG_UNSEEN = 0.85f;
	private static final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, it.unimi.dsi.fastutil.longs.LongOpenHashSet> EXPLORED = new java.util.HashMap<>();
	private static int exploreFrame;

	private static void drawFog(ClientLevel level, PoseStack pose, VertexConsumer consumer) {
		int cell = FOG_CELL, radius = FOG_RADIUS, n = radius * 2 / cell + 1;
		int x0 = Mth.floor(targetX / cell) * cell - radius, z0 = Mth.floor(targetZ / cell) * cell - radius;
		it.unimi.dsi.fastutil.longs.LongOpenHashSet explored = EXPLORED.computeIfAbsent(level.dimension(), k -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet());
		// what is in sight now: corners within a sight circle (and what was seen is remembered)
		boolean[] lit = new boolean[n * n];
		boolean remember = exploreFrame++ % 10 == 0;
		for (Sight sight : sights) {
			int r = (int) Math.ceil(sight.radius / cell);
			int si = Mth.floor((sight.x - x0) / cell), sk = Mth.floor((sight.z - z0) / cell);
			for (int i = Math.max(0, si - r - 1); i <= Math.min(n - 1, si + r + 1); i++)
				for (int k = Math.max(0, sk - r - 1); k <= Math.min(n - 1, sk + r + 1); k++) {
					double dx = x0 + i * cell - sight.x, dz = z0 + k * cell - sight.z;
					if (dx * dx + dz * dz > sight.radius * sight.radius) continue;
					if (VisionBlockers.hides(sight.x, sight.z, x0 + i * cell, z0 + k * cell)) continue;
					lit[i * n + k] = true;
					if (remember) explored.add(net.minecraft.world.level.ChunkPos.asLong((x0 + i * cell) >> 1, (z0 + k * cell) >> 1));
				}
		}
		float[] dark = new float[n * n];
		float[] height = new float[n * n];
		for (int i = 0; i < n; i++)
			for (int k = 0; k < n; k++) {
				int x = x0 + i * cell, z = z0 + k * cell;
				dark[i * n + k] = lit[i * n + k] ? 0 : explored.contains(net.minecraft.world.level.ChunkPos.asLong(x >> 1, z >> 1)) ? FOG_EXPLORED : FOG_UNSEEN;
				int top = Math.max(Math.max(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), level.getHeight(Heightmap.Types.WORLD_SURFACE, x - 1, z)),
					Math.max(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z - 1), level.getHeight(Heightmap.Types.WORLD_SURFACE, x - 1, z - 1)));
				height[i * n + k] = top + 0.06f;
			}
		PoseStack.Pose last = pose.last();
		for (int i = 0; i + 1 < n; i++)
			for (int k = 0; k + 1 < n; k++) {
				int a = i * n + k, b = i * n + k + 1, c = (i + 1) * n + k + 1, d = (i + 1) * n + k;
				if (dark[a] == 0 && dark[b] == 0 && dark[c] == 0 && dark[d] == 0) continue;
				double mx = x0 + i * cell + cell / 2.0 - targetX, mz = z0 + k * cell + cell / 2.0 - targetZ;
				if (mx * mx + mz * mz > radius * radius) continue;
				float x = x0 + i * cell, z = z0 + k * cell;
				consumer.addVertex(last, x, height[a], z).setColor(0f, 0f, 0.03f, dark[a]);
				consumer.addVertex(last, x, height[b], z + cell).setColor(0f, 0f, 0.03f, dark[b]);
				consumer.addVertex(last, x + cell, height[c], z + cell).setColor(0f, 0f, 0.03f, dark[c]);
				consumer.addVertex(last, x + cell, height[d], z).setColor(0f, 0f, 0.03f, dark[d]);
			}
	}

	private static void ring(PoseStack pose, VertexConsumer lines, Entity unit, int color) {
		double r = unit.getBbWidth() / 2 + 0.3;
		double y = unit.getY() + 0.06;
		PoseStack.Pose last = pose.last();
		int segments = 24;
		for (int i = 0; i < segments; i++) {
			double a = i * Math.PI * 2 / segments, b = (i + 1) * Math.PI * 2 / segments;
			float x0 = (float) (unit.getX() + Math.cos(a) * r), z0 = (float) (unit.getZ() + Math.sin(a) * r);
			float x1 = (float) (unit.getX() + Math.cos(b) * r), z1 = (float) (unit.getZ() + Math.sin(b) * r);
			float nx = x1 - x0, nz = z1 - z0, len = Mth.sqrt(nx * nx + nz * nz);
			lines.addVertex(last, x0, (float) y, z0).setColor(color).setNormal(last, nx / len, 0, nz / len);
			lines.addVertex(last, x1, (float) y, z1).setColor(color).setNormal(last, nx / len, 0, nz / len);
		}
	}

	/** A ghost's tint: green where the building can stand, red where it cannot; fainter green for a site a worker is on its way to. */
	static final int GHOST_GOOD = 0x8060FF60, GHOST_BAD = 0x80FF5050, GHOST_PLANNED = 0x5060FF60;

	/**
	 * A building's ghost where it will stand (on its grid), translucent in `argb`: its model, or — without
	 * the SC2 models (a jar without them) — a box of its footprint and height with an outline, so the
	 * place shows either way.
	 */
	static void drawGhost(PoseStack pose, MultiBufferSource buffers, UnitKind kind, BlockPos site, int argb) {
		Vec3 at = kind.gas() ? Vec3.atBottomCenterOf(site) : kind.gridCentre(Vec3.atBottomCenterOf(site)); // where it will stand
		SkinnedModel model = StarMineModels.get(kind.id);
		SkinnedModel.Clip stand = model == null ? null : model.clips.get("stand");
		if (stand == null && model != null && !model.clips.isEmpty()) stand = model.clips.values().iterator().next();
		if (model != null && stand != null) {
			if (ghostPalette.length != model.slots * 12) ghostPalette = new float[model.slots * 12];
			model.pose(stand, 0, ghostPalette);
			GHOST.skin(model, ghostPalette);
			pose.pushPose();
			pose.translate(at.x, at.y, at.z);
			float scale = kind.modelScale();
			pose.scale(scale, scale, scale);
			GHOST.drawTranslucent(model, pose.last(), buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, argb);
			pose.popPose();
		}
		// the footprint: always outlined on the ground (the model alone hides where its edges fall); filled when there is no model
		double half = kind.width / 2;
		AABB box = new AABB(at.x - half, at.y, at.z - half, at.x + half, at.y + kind.height, at.z + half);
		float a = (argb >>> 24) / 255f, r = (argb >> 16 & 255) / 255f, g = (argb >> 8 & 255) / 255f, b = (argb & 255) / 255f;
		if (model == null || stand == null) fillBox(pose, buffers.getBuffer(RenderType.debugQuads()), box, r, g, b, a * 0.6f);
		net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()),
			model == null || stand == null ? box : box.setMaxY(at.y + 0.05), r, g, b, Math.min(1f, a + 0.4f));
	}

	private static void fillBox(PoseStack pose, VertexConsumer quads, AABB box, float r, float g, float b, float a) {
		PoseStack.Pose last = pose.last();
		float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ, x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
		float[][] faces = {
			{x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1}, {x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0},
			{x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0}, {x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1},
			{x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0}, {x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1}};
		for (float[] f : faces)
			for (int v = 0; v < 4; v++) quads.addVertex(last, f[v * 3], f[v * 3 + 1], f[v * 3 + 2]).setColor(r, g, b, a);
	}

	/**
	 * The buildings the player's workers are on their way to put down: a faint green ghost on each site
	 * until the building stands there (in the command view and out of it).
	 */
	static void renderPlans(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || context.consumers() == null || context.matrixStack() == null) return;
		Vec3 cam = context.camera().getPosition();
		PoseStack pose = context.matrixStack();
		boolean any = false;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof StarUnit worker) || !worker.kind.worker() || !worker.isAlive() || !own(worker)) continue;
			StarUnit.Plan plan = worker.plannedBuild();
			if (plan == null || plan.site().distToCenterSqr(cam) > 160 * 160) continue;
			if (!any) {
				pose.pushPose();
				pose.translate(-cam.x, -cam.y, -cam.z);
				any = true;
			}
			drawGhost(pose, context.consumers(), plan.kind(), plan.site(), GHOST_PLANNED);
		}
		if (any) pose.popPose();
	}

	/** Updates the ghost's site and whether it is good: the ground is levelable and no building is in the way. */
	static void updateGhost(double mouseX, double mouseY, int width, int height) {
		Minecraft mc = Minecraft.getInstance();
		BlockHitResult hit = placing == null ? null : block(mouseX, mouseY, width, height);
		if (hit == null || mc.level == null) {
			ghostSite = null;
			return;
		}
		BlockPos site = hit.getBlockPos().above();
		if (placing.gas()) { // on a vein outlet only
			BlockPos outlet = dev.starmine.vespene.Vespene.outletFor(mc.level, site); // a volcano's cone: its top (owner 18.10)
			// the ghost stands where the building will (owner 18.10): on the ground under the mound and cone it takes away
			if (outlet != null) site = java.util.Objects.requireNonNullElse(dev.starmine.vespene.Vespene.siteFor(mc.level, site), outlet.above());
			ghostSite = site;
			boolean taken = !mc.level.getEntitiesOfClass(StarUnit.class, new AABB(site).inflate(1, 2, 1), mob -> mob.kind.structure() && mob.isAlive()).isEmpty();
			ghostValid = outlet != null && !taken && seen(site.getX(), site.getZ(), 0);
			return;
		}
		ghostSite = site;
		int half = (int) Math.ceil(placing.width / 2);
		AABB box = new AABB(site).inflate(half, 0, half).expandTowards(0, placing.height, 0);
		boolean free = mc.level.getEntitiesOfClass(StarUnit.class, box, mob -> mob.kind.structure() && mob.isAlive()).isEmpty();
		ghostValid = free && seen(site.getX(), site.getZ(), 0)
			&& placing.race.rules().siteRefusal(mc.level, placing, site, null) == null // protoss: pylon power
			&& !((placing.main() || placing == UnitKind.HATCHERY) && dev.starmine.mineral.Minerals.nearSite(mc.level, site, placing.width / 2.0)) // 9 off resources
			&& HiveTerrain.prepare(mc.level, site, half, (int) Math.ceil(placing.height), false);
	}
}
