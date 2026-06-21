package hunternif.mc.atlas.core;

import java.util.Arrays;

import hunternif.mc.atlas.ext.ExtTileIdMap;
import hunternif.mc.atlas.util.ByteUtil;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.common.BiomeDictionary.Type;

/**
 * Detects the 256 vanilla biomes, water pools and lava pools.
 * Water and beach biomes are given priority because shore line is the defining
 * feature of the map, and so that rivers are more connected.
 * @author Hunternif
 */
public class BiomeDetectorBase implements IBiomeDetector {
	private boolean doScanPonds = true;

	/** Biome used for occasional pools of water. */
	private static final int waterPoolBiomeID = BiomeGenBase.river.biomeID;
	/** Increment the counter for water biomes by this much during iteration.
	 * This is done so that water pools are more visible. */
	private static final int priorityWaterPool = 3, prioritylavaPool = 6;

	/** Set to true for biome IDs that return true for BiomeDictionary.isBiomeOfType(WATER) */
	private static final boolean[] waterBiomes = new boolean[256];
	/** Set to true for biome IDs that return true for BiomeDictionary.isBiomeOfType(BEACH) */
	private static final boolean[] beachBiomes = new boolean[256];

	/** Block IDs of still water / still lava, resolved once at class load. The original
	 * code compared Block objects (chunk.getBlock(...) == Blocks.water); comparing the raw
	 * integer block IDs is equivalent but lets the pond scan skip the block-registry lookup
	 * that Chunk.getBlock performs for every single column. */
	private static final int waterBlockID = Block.getIdFromBlock(Blocks.water);
	private static final int lavaBlockID = Block.getIdFromBlock(Blocks.lava);

	/** Per-thread scratch buffer for counting biome occurrences, reused between calls so
	 * getBiomeID does not allocate a fresh int[256] on every chunk scan. getBiomeID can run
	 * on both the integrated-server thread and the client thread in single-player, so this
	 * is a ThreadLocal rather than a plain shared field. */
	private static final ThreadLocal<int[]> occurrencesScratch = new ThreadLocal<int[]>() {
		@Override protected int[] initialValue() {
			return new int[BiomeGenBase.getBiomeGenArray().length];
		}
	};

	/** Scan all registered biomes to mark biomes of certain types that will be
	 * given higher priority when identifying mean biome ID for a chunk.
	 * (Currently WATER and BEACH) */
	public static void scanBiomeTypes() {
		for (BiomeGenBase biome : BiomeDictionary.getBiomesForType(Type.WATER)) {
			waterBiomes[biome.biomeID] = true;
		}
		for (BiomeGenBase biome : BiomeDictionary.getBiomesForType(Type.BEACH)) {
			beachBiomes[biome.biomeID] = true;
		}
	}

	public void setScanPonds(boolean value) {
		this.doScanPonds = value;
	}

	protected int priorityForBiome(BiomeGenBase biome) {
		if (waterBiomes[biome.biomeID]) {
			return 4;
		} else if (beachBiomes[biome.biomeID]) {
			return 3;
		} else {
			return 1;
		}
	}

	/** If no valid biome ID is found, returns {@link IBiomeDetector#NOT_FOUND}. */
	@Override
	public int getBiomeID(Chunk chunk) {
		BiomeGenBase[] biomes = BiomeGenBase.getBiomeGenArray();
		// Convert the chunk's biome byte[] to an int[] via ByteUtil.unsignedByteToIntArray.
		// This call site is deliberately preserved: EndlessIDs @Redirects it (and the
		// chunk.getBiomeArray() call feeding it) in BiomeDetectorBaseMixin to inject its
		// extended (>255) biome IDs as a short[]. Reading the byte[] directly would drop
		// that redirect and both crash EndlessIDs' injection check and lose extended IDs.
		int[] chunkBiomes = ByteUtil.unsignedByteToIntArray(chunk.getBiomeArray());
		// Reuse a per-thread occurrence counter instead of allocating one per chunk.
		int[] biomeOccurrences = occurrencesScratch.get();
		if (biomeOccurrences.length < biomes.length) {
			biomeOccurrences = new int[biomes.length];
			occurrencesScratch.set(biomeOccurrences);
		}
		Arrays.fill(biomeOccurrences, 0, biomes.length, 0);

		// The following important pseudo-biomes don't have IDs:
		int lavaOccurences = 0;

		// Block storage is only needed when scanning for ponds; grab it once per chunk
		// rather than going through Chunk.getBlock (and the registry) for every column.
		ExtendedBlockStorage[] storage = doScanPonds ? chunk.getBlockStorageArray() : null;

		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				int biomeID = chunkBiomes[x << 4 | z];
				if (doScanPonds) {
					int y = chunk.getHeightValue(x, z);
					if (y > 0) {
						// For some reason lava doesn't count in height value
						// TODO: check if 1.8 fixes this!
						// Check if there's surface of water at (x, z), but not swamp.
						// Water sits just below the height-map value.
						if (blockIdAt(storage, x, y - 1, z) == waterBlockID &&
								biomeID != BiomeGenBase.swampland.biomeID &&
								biomeID != BiomeGenBase.swampland.biomeID + 128) {
							biomeOccurrences[waterPoolBiomeID] += priorityWaterPool;
						} else if (blockIdAt(storage, x, y, z) == lavaBlockID) {
							lavaOccurences += prioritylavaPool;
						}
					}
				}
				if (biomeID >= 0 && biomeID < biomes.length && biomes[biomeID] != null) {
					biomeOccurrences[biomeID] += priorityForBiome(biomes[biomeID]);
				}
			}
		}
		int meanBiomeId = NOT_FOUND;
		int meanBiomeOccurences = 0;
		for (int i = 0; i < biomes.length; i++) {
			if (biomeOccurrences[i] > meanBiomeOccurences) {
				meanBiomeId = i;
				meanBiomeOccurences = biomeOccurrences[i];
			}
		}

		// The following important pseudo-biomes don't have IDs:
		if (meanBiomeOccurences < lavaOccurences) {
			return ExtTileIdMap.instance().getPseudoBiomeID(ExtTileIdMap.TILE_LAVA);
		}

		return meanBiomeId;
	}

	/** Raw block ID at chunk-local coordinates (x, z in 0..15, y in world space), read
	 * straight from the chunk's block storage. This avoids the block-registry lookup that
	 * Chunk.getBlock does per call. Returns 0 (air) for empty or out-of-range sections,
	 * which matches Chunk.getBlock's behaviour for the water/lava comparisons done above. */
	private static int blockIdAt(ExtendedBlockStorage[] storage, int x, int y, int z) {
		if (y < 0) return 0;
		int section = y >> 4;
		if (section >= storage.length) return 0;
		ExtendedBlockStorage ebs = storage[section];
		if (ebs == null) return 0;
		int idx = ((y & 15) << 8) | (z << 4) | x;
		int id = ebs.getBlockLSBArray()[idx] & 0xFF;
		NibbleArray msb = ebs.getBlockMSBArray();
		if (msb != null) {
			id |= msb.get(x, y & 15, z) << 8;
		}
		return id;
	}
}
