package dev.starmine.client;

import dev.starmine.block.BlockWear;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/** Owner 19.10: how worn the blocks near are, as the server says ({@link BlockWear.Sync}); mining starts from it. */
public final class ClientWear {
	private static final Map<Long, Float> WEAR = new HashMap<>();
	private static ClientLevel of;

	private ClientWear() {
	}

	static void register() {
		ClientPlayNetworking.registerGlobalReceiver(BlockWear.Sync.TYPE, (sync, context) -> {
			ClientLevel level = Minecraft.getInstance().level;
			if (level != of) {
				WEAR.clear();
				of = level;
			}
			for (int i = 0; i < sync.positions().size(); i++) {
				if (sync.progress().get(i) <= 0) WEAR.remove(sync.positions().get(i));
				else WEAR.put(sync.positions().get(i), sync.progress().get(i));
			}
		});
	}

	public static float get(BlockPos pos) {
		if (Minecraft.getInstance().level != of) return 0;
		return WEAR.getOrDefault(pos.asLong(), 0f);
	}
}
