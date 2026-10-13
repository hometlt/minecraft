package dev.starmine.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * A StarMine model (`.smm`, written by Modkit's StarMine exporter from a skinned UVM).
 *
 * Layout: "SMM1", u32 header length, header JSON, then little-endian body: vertices (stride 52:
 * position, normal, uv, 4 joint bytes, 4 weights), u32 triangle indices, and per clip `frames x slots`
 * 3x4 row-major skinning matrices that map bind-pose positions to Minecraft model space (blocks,
 * +Y up, feet at 0, facing +Z). The last palette slot is the identity.
 */
public final class SkinnedModel {
	public final int vertexCount;
	public final int slots;
	public final float fps;
	public final float[] positions, normals, uvs, weights;
	public final byte[] joints;
	public final int[] indices;
	public final Map<String, Clip> clips = new HashMap<>();

	/** One baked clip: `frames` palettes of `slots` 3x4 matrices. */
	public static final class Clip {
		public final String name;
		public final int frames;
		public final boolean loop;
		public final float[] matrices;

		Clip(String name, int frames, boolean loop, float[] matrices) {
			this.name = name;
			this.frames = frames;
			this.loop = loop;
			this.matrices = matrices;
		}

		public float seconds(float fps) {
			return (frames - 1) / fps;
		}
	}

	private SkinnedModel(ByteBuffer data) {
		byte[] magic = new byte[4];
		data.get(magic);
		if (!"SMM1".equals(new String(magic, StandardCharsets.US_ASCII))) throw new IllegalArgumentException("not a StarMine model");
		int headerLength = data.getInt();
		byte[] headerBytes = new byte[headerLength];
		data.get(headerBytes);
		JsonObject header = JsonParser.parseString(new String(headerBytes, StandardCharsets.UTF_8).trim()).getAsJsonObject();
		int bodyStart = 8 + headerLength;
		vertexCount = header.get("vertexCount").getAsInt();
		int indexCount = header.get("indexCount").getAsInt();
		slots = header.get("slots").getAsInt();
		fps = header.get("fps").getAsFloat();
		positions = new float[vertexCount * 3];
		normals = new float[vertexCount * 3];
		uvs = new float[vertexCount * 2];
		joints = new byte[vertexCount * 4];
		weights = new float[vertexCount * 4];
		for (int v = 0; v < vertexCount; v++) {
			int o = bodyStart + v * 52;
			for (int i = 0; i < 3; i++) positions[v * 3 + i] = data.getFloat(o + i * 4);
			for (int i = 0; i < 3; i++) normals[v * 3 + i] = data.getFloat(o + 12 + i * 4);
			uvs[v * 2] = data.getFloat(o + 24);
			uvs[v * 2 + 1] = data.getFloat(o + 28);
			for (int i = 0; i < 4; i++) joints[v * 4 + i] = data.get(o + 32 + i);
			for (int i = 0; i < 4; i++) weights[v * 4 + i] = data.getFloat(o + 36 + i * 4);
		}
		indices = new int[indexCount];
		int indexStart = bodyStart + vertexCount * 52;
		for (int i = 0; i < indexCount; i++) {
			indices[i] = data.getInt(indexStart + i * 4);
			if (indices[i] < 0 || indices[i] >= vertexCount) throw new IllegalArgumentException("index out of range");
		}
		JsonArray clipList = header.getAsJsonArray("clips");
		for (JsonElement element : clipList) {
			JsonObject clip = element.getAsJsonObject();
			int frames = clip.get("frames").getAsInt();
			int offset = bodyStart + clip.get("offset").getAsInt();
			float[] matrices = new float[frames * slots * 12];
			for (int i = 0; i < matrices.length; i++) matrices[i] = data.getFloat(offset + i * 4);
			String name = clip.get("name").getAsString();
			clips.put(name, new Clip(name, frames, clip.get("loop").getAsBoolean(), matrices));
		}
	}

	public static SkinnedModel read(InputStream stream) throws IOException {
		ByteBuffer data = ByteBuffer.wrap(stream.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
		return new SkinnedModel(data);
	}

	/**
	 * Writes into {@code palette} (slots x 12) the pose of {@code clip} at {@code seconds}, linearly
	 * interpolated between baked frames; looping clips wrap, others hold their last frame.
	 */
	public void pose(Clip clip, float seconds, float[] palette) {
		float frame = seconds * fps;
		int last = clip.frames - 1;
		if (clip.loop && last > 0) frame = frame % last;
		if (frame < 0) frame = 0;
		if (frame > last) frame = last;
		int a = (int) Math.floor(frame);
		int b = Math.min(a + 1, last);
		float t = frame - a;
		int size = slots * 12;
		for (int i = 0; i < size; i++) {
			float x = clip.matrices[a * size + i];
			palette[i] = x + (clip.matrices[b * size + i] - x) * t;
		}
	}

	/** {@code into = into + (other - into) * t}: cross-fade of two palettes. */
	public static void blend(float[] into, float[] other, float t) {
		for (int i = 0; i < into.length; i++) into[i] += (other[i] - into[i]) * t;
	}
}
