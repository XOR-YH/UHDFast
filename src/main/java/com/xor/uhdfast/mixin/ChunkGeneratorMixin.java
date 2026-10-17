package com.xor.uhdfast.mixin;

/**
 * 区块生成器 Mixin
 * 
 * Note: In NeoForge 1.21.1, the following methods have changed:
 * - getBaseHeight: Signature changed (no longer has the same parameters)
 * - fillFromDepthmap: Method removed or signature changed significantly
 * 
 * This mixin is disabled because the injection points are incompatible with
 * 1.21.1.
 * 
 * @Disabled - Methods signatures changed in 1.21.1
 */
// @Mixin(net.minecraft.world.level.chunk.ChunkGenerator.class)
public class ChunkGeneratorMixin {
    // Disabled: Method signatures changed in NeoForge 1.21.1
    // If needed, find correct injection points in the new ChunkGenerator API
}
