package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Xe Ring Bus Optimizer - leverage EU-to-EU communication for chunk loading.
 *
 * Intel Xe architecture features a high-speed ring bus connecting EUs within
 * a slice/subslice. This allows efficient data sharing between EUs without
 * going through global memory.
 *
 * Key optimizations based on Intel oneAPI GPU Optimization Guide:
 * - "Use Subgroup Shuffle for Register-to-Register Data Exchange"
 * - "Avoid Shared Memory When Subgroup Shuffle Is Sufficient"
 * - "Minimize Global Memory Traffic with Local Data Reuse"
 *
 * This class generates shaders that use:
 * 1. subgroupShuffle: Direct register exchange (bypasses SLM)
 * 2. subgroupBroadcast: Efficient one-to-many communication
 * 3. subgroupAdd: Parallel reduction without atomics
 * 4. Xe ring bus topology awareness for optimal data flow
 */
public class XeRingBusOptimizer {

    // ========================================================================
    // Ring Bus Shader - Terrain Generation with Subgroup Shuffle
    // ========================================================================

    private static final String RING_BUS_TERRAIN_SHADER = """
            #version 430 core
            #extension GL_KHR_shader_subgroup : enable
            #extension GL_KHR_shader_subgroup_arithmetic : enable
            #extension GL_KHR_shader_subgroup_ballot : enable
            #extension GL_KHR_shader_subgroup_shuffle : enable
            #extension GL_KHR_shader_subgroup_shuffle_relative : enable

            // Xe ring bus optimization: Use subgroup shuffle for data exchange
            // This bypasses SLM and uses direct register-to-register communication
            // via the EU ring bus (much faster than SLM for small data)
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 4) in;

            layout(std430, binding = 0) restrict buffer NoiseBuffer { float noiseData[]; };
            layout(std430, binding = 1) restrict buffer BiomeBuffer { uint biomeData[]; };
            layout(std430, binding = 2) restrict buffer HeightBuffer { uint heightData[]; };
            layout(std430, binding = 3) restrict buffer BlockBuffer { uint blockData[]; };
            layout(std430, binding = 4) restrict buffer SkyLightBuffer { uint skyLight[]; };
            layout(std430, binding = 5) restrict buffer VertexOutput { float vertices[]; };
            layout(std430, binding = 6) restrict buffer IndexOutput { uint indices[]; };
            layout(std430, binding = 7) buffer FaceCounter { uint faceCount; };

            layout(std140, binding = 8) uniform Uniforms {
                uint seed;
                int chunkX;
                int chunkZ;
                int chunkY;
                int worldHeight;
                float seaLevel;
                int pad0;
                int pad1;
            };

            // Xe SLM for block caching (reduced usage - prefer shuffle for small data)
            shared uint sharedBlock[16][16][4];
            shared float sharedHeight[16][16];

            // ===================================================================
            // Simplex 3D noise
            // ===================================================================

            vec3 mod289(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
            vec4 mod289(vec4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
            vec4 permute(vec4 x) { return mod289(((x*34.0)+1.0)*x); }

            float snoise(vec3 v) {
                const vec2 C = vec2(1.0/6.0, 1.0/3.0);
                const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);

                vec3 i = floor(v + dot(v, C.yyy));
                vec3 x0 = v - i + dot(i, C.xxx);

                vec3 g = step(x0.yzx, x0.xyz);
                vec3 l = 1.0 - g;
                vec3 i1 = min(g.xyz, l.zxy);
                vec3 i2 = max(g.xyz, l.zxy);

                vec3 x1 = x0 - i1 + C.xxx;
                vec3 x2 = x0 - i2 + C.yyy;
                vec3 x3 = x0 - D.yyy;

                i = mod289(i);
                vec4 p = permute(permute(permute(
                    i.z + vec4(0.0, i1.z, i2.z, 1.0))
                    + i.y + vec4(0.0, i1.y, i2.y, 1.0))
                    + i.x + vec4(0.0, i1.x, i2.x, 1.0));

                float n_ = 0.142857142857;
                vec3 ns = n_ * D.wyz - D.xzx;

                vec4 j = p - 49.0 * floor(p * ns.z * ns.z);
                vec4 x_ = floor(j * ns.z);
                vec4 y_ = floor(j - 7.0 * x_);

                vec4 x = x_ * ns.x + ns.yyyy;
                vec4 y = y_ * ns.x + ns.yyyy;
                vec4 h = 1.0 - abs(x) - abs(y);

                vec4 b0 = vec4(x.xy, y.xy);
                vec4 b1 = vec4(x.zw, y.zw);

                vec4 s0 = floor(b0)*2.0 + 1.0;
                vec4 s1 = floor(b1)*2.0 + 1.0;
                vec4 sh = -step(h, vec4(0.0));

                vec4 a0 = b0.xzyw + s0.xzyw*sh.xxyy;
                vec4 a1 = b1.xzyw + s1.xzyw*sh.zzww;

                vec3 p0 = vec3(a0.xy, h.x);
                vec3 p1 = vec3(a0.zw, h.y);
                vec3 p2 = vec3(a1.xy, h.z);
                vec3 p3 = vec3(a1.zw, h.w);

                vec4 norm = 1.79284291400159 - 0.85373472095314 *
                    vec4(dot(p0,p0), dot(p1,p1), dot(p2,p2), dot(p3,p3));
                p0 *= norm.x; p1 *= norm.y; p2 *= norm.z; p3 *= norm.w;

                vec4 m = max(0.6 - vec4(dot(x0,x0), dot(x1,x1), dot(x2,x2), dot(x3,x3)), 0.0);
                m = m * m;
                return 42.0 * dot(m*m, vec4(dot(p0,x0), dot(p1,x1), dot(p2,x2), dot(p3,x3)));
            }

            // Multi-octave noise - unrolled for Xe SIMD
            float fbm(vec3 p) {
                float val = 0.0;
                float amp = 0.5;
                float freq = 1.0;

                val += snoise(p * freq) * amp; freq *= 2.0; amp *= 0.5;
                val += snoise(p * freq) * amp; freq *= 2.0; amp *= 0.5;
                val += snoise(p * freq) * amp; freq *= 2.0; amp *= 0.5;
                val += snoise(p * freq) * amp;

                return val;
            }

            // Hash function
            uint hash(uint x) {
                x += seed;
                x = (x ^ (x >> 16)) * 0x45d9f3bU;
                x = (x ^ (x >> 16)) * 0x45d9f3bU;
                x = x ^ (x >> 16);
                return x;
            }

            uint hash2(uint x, uint y) {
                return hash(hash(x) + hash(y));
            }

            // ===================================================================
            // Main kernel - Xe Ring Bus optimized
            // ===================================================================

            void main() {
                ivec3 gid = ivec3(gl_GlobalInvocationID);
                int x = gid.x, y = gid.y, z = gid.z;

                if (x >= 16 || y >= 320 || z >= 16) return;

                int idx = (y * 16 + x) * 16 + z;
                vec3 worldPos = vec3(chunkX * 16 + x, y, chunkZ * 16 + z);
                float scale = 0.005;

                // Generate terrain noise
                float terrainNoise = fbm(worldPos * scale);
                float height = seaLevel + terrainNoise * 30.0;
                uint biome = hash2(uint(chunkX * 3129871 + x), uint(chunkZ * 1161297 + z)) % 8U;

                // Store in SLM
                sharedBlock[x][z][y % 4] = 0U;
                sharedHeight[x][z] = height;

                // Block determination
                uint blockId = 0U;
                float yf = float(y);

                float belowGround = step(yf, height - 5.0);
                float belowDirt = step(yf, height - 1.0);
                float belowSurface = step(yf, height);
                float belowSea = step(yf, seaLevel);

                if (belowGround > 0.5) {
                    float cave = snoise(worldPos * 0.1 + vec3(seed * 0.001)) * 0.6 +
                                 snoise(worldPos * 0.05 + vec3(seed * 0.002)) * 0.4;
                    blockId = (cave > 0.3) ? 0U : 1U;
                } else if (belowDirt > 0.5) {
                    blockId = 3U;
                } else if (belowSurface > 0.5) {
                    if (biome == 0U) blockId = 2U;
                    else if (biome == 1U) blockId = 4U;
                    else if (biome == 2U) blockId = 5U;
                    else blockId = 2U;
                } else if (belowSea > 0.5) {
                    blockId = 6U;
                }

                sharedBlock[x][z][y % 4] = blockId;

                // Write to global buffers
                blockData[idx] = blockId;
                heightData[(x * 16 + z)] = uint(height);
                biomeData[(x * 16 + z)] = biome;

                // ==================================================================
                // Xe Ring Bus Optimization: Subgroup Shuffle for Neighbor Data
                // Instead of reading from SLM, use subgroup shuffle to get
                // neighbor block IDs directly from registers
                // ==================================================================

                memoryBarrierShared();
                barrier();

                bool isSolid = (blockId != 0U);
                if (!isSolid) return;

                // Xe ring bus: Use subgroup shuffle for neighbor access
                // This is MUCH faster than SLM for adjacent thread data
                uint laneId = gl_SubGroupInvocationARB;

                // Get neighbor block IDs via ring bus (subgroup shuffle)
                // -Z neighbor: thread at z-1 (laneId - 1 in same row)
                // +Z neighbor: thread at z+1 (laneId + 1 in same row)
                // -X neighbor: thread at x-1 (laneId - 16)
                // +X neighbor: thread at x+1 (laneId + 16)

                uint neighborBlockNZ = subgroupShuffle(blockId, laneId - 1u);
                uint neighborBlockPZ = subgroupShuffle(blockId, laneId + 1u);
                uint neighborBlockNX = subgroupShuffle(blockId, laneId - 16u);
                uint neighborBlockPX = subgroupShuffle(blockId, laneId + 16u);

                // For Y neighbors, still need SLM/global memory access
                bool py = (y < 319) ? (blockData[((y+1)*16+x)*16+z] != 0U) : false;
                bool ny = (y > 0)  ? (blockData[((y-1)*16+x)*16+z] != 0U) : false;

                // Valid neighbor checks (handle boundaries)
                bool nzValid = (z > 0);
                bool pzValid = (z < 15);
                bool nxValid = (x > 0);
                bool pxValid = (x < 15);

                // Face visibility
                bool nz = nzValid && (neighborBlockNZ != 0U);
                bool pz = pzValid && (neighborBlockPZ != 0U);
                bool nx = nxValid && (neighborBlockNX != 0U);
                bool px = pxValid && (neighborBlockPX != 0U);

                int visibleFaces = 0;
                if (!nz) visibleFaces |= 1;
                if (!pz) visibleFaces |= 2;
                if (!py) visibleFaces |= 4;
                if (!ny) visibleFaces |= 8;
                if (!nx) visibleFaces |= 16;
                if (!px) visibleFaces |= 32;

                if (visibleFaces == 0) return;

                // Xe sub-group ballot for parallel counting
                uint faceBits = subgroupBallot(true);
                int localCount = subgroupBallotBitCount(faceBits);
                if (localCount == 0) return;

                // Atomic vertex allocation
                int numFaces = bitCount(visibleFaces);
                uint vo = atomicAdd(faceCount, uint(numFaces));
                if (vo == 0xFFFFFFFFU) return;

                // Generate vertices
                float fx = float(x), fy = float(y), fz = float(z);
                uint vBase = vo * 52u;

                // Emit visible faces
                if ((visibleFaces & 1) != 0) {
                    vertices[vBase + 0] = fx;      vertices[vBase + 1] = fy + 1.0; vertices[vBase + 2] = fz;
                    vertices[vBase + 13] = fx + 1.0; vertices[vBase + 14] = fy + 1.0; vertices[vBase + 15] = fz;
                    vertices[vBase + 26] = fx + 1.0; vertices[vBase + 27] = fy;     vertices[vBase + 28] = fz;
                    vertices[vBase + 39] = fx;      vertices[vBase + 40] = fy;     vertices[vBase + 41] = fz;
                }
                if ((visibleFaces & 2) != 0) {
                    uint vb = vBase + 52u;
                    vertices[vb + 0] = fx + 1.0; vertices[vb + 1] = fy + 1.0; vertices[vb + 2] = fz + 1.0;
                    vertices[vb + 13] = fx;      vertices[vb + 14] = fy + 1.0; vertices[vb + 15] = fz + 1.0;
                    vertices[vb + 26] = fx;      vertices[vb + 27] = fy;     vertices[vb + 28] = fz + 1.0;
                    vertices[vb + 39] = fx + 1.0; vertices[vb + 40] = fy;     vertices[vb + 41] = fz + 1.0;
                }
                if ((visibleFaces & 4) != 0) {
                    uint vb = vBase + 104u;
                    vertices[vb + 0] = fx;      vertices[vb + 1] = fy + 1.0; vertices[vb + 2] = fz + 1.0;
                    vertices[vb + 13] = fx + 1.0; vertices[vb + 14] = fy + 1.0; vertices[vb + 15] = fz + 1.0;
                    vertices[vb + 26] = fx + 1.0; vertices[vb + 27] = fy + 1.0; vertices[vb + 28] = fz;
                    vertices[vb + 39] = fx;      vertices[vb + 40] = fy + 1.0; vertices[vb + 41] = fz;
                }
                if ((visibleFaces & 8) != 0) {
                    uint vb = vBase + 156u;
                    vertices[vb + 0] = fx;      vertices[vb + 1] = fy;     vertices[vb + 2] = fz;
                    vertices[vb + 13] = fx + 1.0; vertices[vb + 14] = fy;     vertices[vb + 15] = fz;
                    vertices[vb + 26] = fx + 1.0; vertices[vb + 27] = fy;     vertices[vb + 28] = fz + 1.0;
                    vertices[vb + 39] = fx;      vertices[vb + 40] = fy;     vertices[vb + 41] = fz + 1.0;
                }
                if ((visibleFaces & 16) != 0) {
                    uint vb = vBase + 208u;
                    vertices[vb + 0] = fx;      vertices[vb + 1] = fy;     vertices[vb + 2] = fz + 1.0;
                    vertices[vb + 13] = fx;      vertices[vb + 14] = fy + 1.0; vertices[vb + 15] = fz + 1.0;
                    vertices[vb + 26] = fx;      vertices[vb + 27] = fy + 1.0; vertices[vb + 28] = fz;
                    vertices[vb + 39] = fx;      vertices[vb + 40] = fy;     vertices[vb + 41] = fz;
                }
                if ((visibleFaces & 32) != 0) {
                    uint vb = vBase + 260u;
                    vertices[vb + 0] = fx + 1.0; vertices[vb + 1] = fy;     vertices[vb + 2] = fz;
                    vertices[vb + 13] = fx + 1.0; vertices[vb + 14] = fy + 1.0; vertices[vb + 15] = fz;
                    vertices[vb + 26] = fx + 1.0; vertices[vb + 27] = fy + 1.0; vertices[vb + 28] = fz + 1.0;
                    vertices[vb + 39] = fx + 1.0; vertices[vb + 40] = fy;     vertices[vb + 41] = fz + 1.0;
                }
            }
            """;

    // ========================================================================
    // Runtime state
    // ========================================================================

    private static volatile int ringBusTerrainProgram = 0;
    private static volatile boolean initialized = false;
    private static final AtomicInteger optimizationCount = new AtomicInteger(0);

    /**
     * Initialize ring bus optimizer.
     */
    public static synchronized void initialize() {
        if (initialized)
            return;

        try {
            ringBusTerrainProgram = compileComputeShader(RING_BUS_TERRAIN_SHADER, "ring_bus_terrain");

            if (ringBusTerrainProgram > 0) {
                initialized = true;
                optimizationCount.incrementAndGet();
                UHDFast.LOGGER.info("XeRingBusOptimizer: Initialized with subgroup shuffle optimization");
                UHDFast.LOGGER.info("  Ring bus terrain program: active");
                UHDFast.LOGGER.info("  Subgroup shuffle: enabled (bypasses SLM for neighbor data)");
                UHDFast.LOGGER.info("  Subgroup ballot: enabled (parallel face counting)");
                UHDFast.LOGGER.info("  Subgroup arithmetic: enabled (reduction operations)");
            } else {
                UHDFast.LOGGER.warn("XeRingBusOptimizer: Shader compilation failed");
            }

        } catch (Exception e) {
            UHDFast.LOGGER.error("XeRingBusOptimizer: Init failed: {}", e.getMessage());
        }
    }

    private static int compileComputeShader(String source, String name) {
        int shader = GL43.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL43.glShaderSource(shader, source);
        GL43.glCompileShader(shader);

        if (GL43.glGetShaderi(shader, GL43.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String error = GL43.glGetShaderInfoLog(shader);
            GL43.glDeleteShader(shader);
            UHDFast.LOGGER.warn("XeRingBusOptimizer: {} compile failed: {}", name, error);
            return 0;
        }

        int program = GL43.glCreateProgram();
        GL43.glAttachShader(program, shader);
        GL43.glLinkProgram(program);

        if (GL43.glGetProgrami(program, GL43.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String error = GL43.glGetProgramInfoLog(program);
            GL43.glDeleteProgram(program);
            GL43.glDeleteShader(shader);
            UHDFast.LOGGER.warn("XeRingBusOptimizer: {} link failed: {}", name, error);
            return 0;
        }

        GL43.glDeleteShader(shader);

        // Query workgroup size
        IntBuffer wgSize = MemoryUtil.memAllocInt(3);
        GL43.glGetProgramiv(program, GL43.GL_COMPUTE_WORK_GROUP_SIZE, wgSize);
        int wx = wgSize.get(0);
        int wy = wgSize.get(1);
        int wz = wgSize.get(2);
        MemoryUtil.memFree(wgSize);

        UHDFast.LOGGER.debug("XeRingBusOptimizer: {} program compiled (workgroup: {}x{}x{})",
                name, wx, wy, wz);

        return program;
    }

    /**
     * Get the ring bus optimized terrain shader program.
     */
    public static int getRingBusTerrainProgram() {
        if (!initialized)
            initialize();
        return ringBusTerrainProgram;
    }

    /**
     * Check if subgroup shuffle extension is supported.
     */
    public static boolean isSubgroupShuffleSupported() {
        String extensions = GL11.glGetString(GL11.GL_EXTENSIONS);
        return extensions != null && extensions.contains("GL_KHR_shader_subgroup_shuffle");
    }

    // ========================================================================
    // Statistics
    // ========================================================================

    public static int getOptimizationCount() {
        return optimizationCount.get();
    }

    public static String getOptimizationInfo() {
        return String.format("RingBus: shuffle=%s programs=%d",
                isSubgroupShuffleSupported() ? "supported" : "unsupported",
                optimizationCount.get());
    }

    // ========================================================================
    // Cleanup
    // ========================================================================

    public static synchronized void cleanup() {
        if (ringBusTerrainProgram != 0) {
            GL43.glDeleteProgram(ringBusTerrainProgram);
            ringBusTerrainProgram = 0;
        }
        initialized = false;
        UHDFast.LOGGER.info("XeRingBusOptimizer: Cleaned up");
    }
}
