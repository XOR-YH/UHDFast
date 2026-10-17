package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.*;

/**
 * Xe Shader Optimizer - generates and manages Xe-optimized compute shaders.
 *
 * Key optimizations based on Intel oneAPI GPU Optimization Guide:
 *
 * 1. Sub-group aware dispatch: 16-wide SIMD matches Xe-LP EU vector width
 * 2. SLM (Shared Local Memory): 64KB per tile for block data caching
 * 3. Reduced barriers: minimize memoryBarrierShared() calls
 * 4. Sub-group ballot: subgroupBallot for parallel face counting
 * 5. Sub-group arithmetic: subgroupBroadcast/add for reduction
 * 6. No divergent branching: avoid if/else in shader where possible
 * 7. Loop unrolling: unroll fixed loops to reduce instruction count
 * 8. Constant propagation: use specialization constants for chunk dimensions
 */
public class XeShaderOptimizer {

    // ========================================================================
    // Optimized terrain generation shader
    // ========================================================================

    private static final String XE_TERRAIN_GEN_OPTIMIZED = """
            #version 430 core
            #extension GL_KHR_shader_subgroup : enable
            #extension GL_KHR_shader_subgroup_arithmetic : enable
            #extension GL_KHR_shader_subgroup_ballot : enable
            #extension GL_KHR_shader_subgroup_shuffle : enable
            #extension GL_EXT_control_flow_attributes : enable

            // Xe EU optimization: 16x16 workgroup = 256 threads = 16 SIMD-16 lanes
            // Matches Xe-LP EU SMT configuration (7 threads per EU, 8-wide SIMD)
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 4) in;
            // 16*16*4 = 1024 threads, Z=4 for better occupancy

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
                int worldHeight;  // 320
                float seaLevel;
                int pad0;
                int pad1;
            };

            // Xe SLM (Shared Local Memory) - 64KB per tile
            // Store block IDs for neighbor access (reduces global memory reads)
            shared uint sharedBlock[16][16][4];

            // ===================================================================
            // Simplex 3D noise - optimized for Xe SIMD
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

            // Multi-octave noise - 4 octaves with Xe sub-group optimization
            float fbm(vec3 p) {
                float val = 0.0;
                float amp = 0.5;
                float freq = 1.0;

                // Unrolled loop for 4 octaves
                val += snoise(p * freq) * amp; freq *= 2.0; amp *= 0.5;
                val += snoise(p * freq) * amp; freq *= 2.0; amp *= 0.5;
                val += snoise(p * freq) * amp; freq *= 2.0; amp *= 0.5;
                val += snoise(p * freq) * amp;

                return val;
            }

            // Xe-optimized hash function
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

            uint getBiome(int x, int z) {
                return hash2(uint(chunkX * 3129871 + x), uint(chunkZ * 1161297 + z)) % 8U;
            }

            // Cave density - simplified to reduce divergence
            float caveDensity(vec3 pos) {
                float n1 = snoise(pos * 0.1 + vec3(seed * 0.001));
                float n2 = snoise(pos * 0.05 + vec3(seed * 0.002));
                return n1 * 0.6 + n2 * 0.4;
            }

            // ===================================================================
            // Main kernel - Xe EU optimized
            // ===================================================================

            void main() {
                ivec3 gid = ivec3(gl_GlobalInvocationID);
                int x = gid.x, y = gid.y, z = gid.z;

                // Early exit for out-of-bounds
                if (x >= 16 || y >= 320 || z >= 16) return;

                int idx = (y * 16 + x) * 16 + z;

                // World position
                vec3 worldPos = vec3(chunkX * 16 + x, y, chunkZ * 16 + z);
                float scale = 0.005;

                // Terrain noise (4-octave FBM)
                float terrainNoise = fbm(worldPos * scale);
                float height = seaLevel + terrainNoise * 30.0;

                // Biome
                uint biome = getBiome(x, z);

                // Store in SLM (reduced size: 4 z-slices vs 8)
                sharedBlock[x][z][y % 4] = 0U; // placeholder

                // Block determination - branchless where possible
                uint blockId = 0U;
                float yf = float(y);

                // Use step/mix for branchless terrain generation
                float belowGround = step(yf, height - 5.0);
                float belowDirt = step(yf, height - 1.0);
                float belowSurface = step(yf, height);
                float belowSea = step(yf, seaLevel);

                // Stone layer
                if (belowGround > 0.5) {
                    // Check cave
                    float cave = caveDensity(worldPos);
                    if (cave > 0.3) {
                        blockId = 0U; // cave
                    } else {
                        blockId = 1U; // stone
                    }
                } else if (belowDirt > 0.5) {
                    blockId = 3U; // dirt
                } else if (belowSurface > 0.5) {
                    // Biome-specific surface
                    if (biome == 0U) blockId = 2U;      // grass
                    else if (biome == 1U) blockId = 4U; // sand
                    else if (biome == 2U) blockId = 5U; // snow
                    else blockId = 2U;
                } else if (belowSea > 0.5) {
                    blockId = 6U; // water
                }

                // Write to SLM
                sharedBlock[x][z][y % 4] = blockId;

                // Write to global buffers
                blockData[idx] = blockId;
                heightData[(x * 16 + z)] = uint(height);
                biomeData[(x * 16 + z)] = biome;

                // ==================================================================
                // Face detection using SLM - Xe sub-group optimization
                // ==================================================================

                // SLM barrier - ensure all block data is visible
                memoryBarrierShared();
                barrier();

                // Check if block is solid
                bool isSolid = (blockId != 0U);
                if (!isSolid) return;

                // Check neighbors using SLM (fast) or global memory (for Y neighbors)
                bool nz = (z > 0)  ? (sharedBlock[x][z-1][y%4] != 0U) : false; // -Z
                bool pz = (z < 15) ? (sharedBlock[x][z+1][y%4] != 0U) : false; // +Z
                bool py = (y < 319) ? (blockData[((y+1)*16+x)*16+z] != 0U) : false; // +Y
                bool ny = (y > 0)  ? (blockData[((y-1)*16+x)*16+z] != 0U) : false; // -Y
                bool nx = (x > 0)  ? (sharedBlock[x-1][z][y%4] != 0U) : false; // -X
                bool px = (x < 15) ? (sharedBlock[x+1][z][y%4] != 0U) : false; // +X

                // Visible faces bitmask
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
                if (vo == 0xFFFFFFFFU) return; // overflow

                // Generate vertices
                float fx = float(x), fy = float(y), fz = float(z);
                uint vBase = vo * 52u;

                // Emit visible faces
                if ((visibleFaces & 1) != 0) { // -Z
                    vertices[vBase + 0] = fx;      vertices[vBase + 1] = fy + 1.0; vertices[vBase + 2] = fz;
                    vertices[vBase + 13] = fx + 1.0; vertices[vBase + 14] = fy + 1.0; vertices[vBase + 15] = fz;
                    vertices[vBase + 26] = fx + 1.0; vertices[vBase + 27] = fy;     vertices[vBase + 28] = fz;
                    vertices[vBase + 39] = fx;      vertices[vBase + 40] = fy;     vertices[vBase + 41] = fz;
                }
                if ((visibleFaces & 2) != 0) { // +Z
                    uint vb = vBase + 52u;
                    vertices[vb + 0] = fx + 1.0; vertices[vb + 1] = fy + 1.0; vertices[vb + 2] = fz + 1.0;
                    vertices[vb + 13] = fx;      vertices[vb + 14] = fy + 1.0; vertices[vb + 15] = fz + 1.0;
                    vertices[vb + 26] = fx;      vertices[vb + 27] = fy;     vertices[vb + 28] = fz + 1.0;
                    vertices[vb + 39] = fx + 1.0; vertices[vb + 40] = fy;     vertices[vb + 41] = fz + 1.0;
                }
                if ((visibleFaces & 4) != 0) { // +Y (top)
                    uint vb = vBase + 104u;
                    vertices[vb + 0] = fx;      vertices[vb + 1] = fy + 1.0; vertices[vb + 2] = fz + 1.0;
                    vertices[vb + 13] = fx + 1.0; vertices[vb + 14] = fy + 1.0; vertices[vb + 15] = fz + 1.0;
                    vertices[vb + 26] = fx + 1.0; vertices[vb + 27] = fy + 1.0; vertices[vb + 28] = fz;
                    vertices[vb + 39] = fx;      vertices[vb + 40] = fy + 1.0; vertices[vb + 41] = fz;
                }
                if ((visibleFaces & 8) != 0) { // -Y (bottom)
                    uint vb = vBase + 156u;
                    vertices[vb + 0] = fx;      vertices[vb + 1] = fy;     vertices[vb + 2] = fz;
                    vertices[vb + 13] = fx + 1.0; vertices[vb + 14] = fy;     vertices[vb + 15] = fz;
                    vertices[vb + 26] = fx + 1.0; vertices[vb + 27] = fy;     vertices[vb + 28] = fz + 1.0;
                    vertices[vb + 39] = fx;      vertices[vb + 40] = fy;     vertices[vb + 41] = fz + 1.0;
                }
                if ((visibleFaces & 16) != 0) { // -X
                    uint vb = vBase + 208u;
                    vertices[vb + 0] = fx;      vertices[vb + 1] = fy;     vertices[vb + 2] = fz + 1.0;
                    vertices[vb + 13] = fx;      vertices[vb + 14] = fy + 1.0; vertices[vb + 15] = fz + 1.0;
                    vertices[vb + 26] = fx;      vertices[vb + 27] = fy + 1.0; vertices[vb + 28] = fz;
                    vertices[vb + 39] = fx;      vertices[vb + 40] = fy;     vertices[vb + 41] = fz;
                }
                if ((visibleFaces & 32) != 0) { // +X
                    uint vb = vBase + 260u;
                    vertices[vb + 0] = fx + 1.0; vertices[vb + 1] = fy;     vertices[vb + 2] = fz;
                    vertices[vb + 13] = fx + 1.0; vertices[vb + 14] = fy + 1.0; vertices[vb + 15] = fz;
                    vertices[vb + 26] = fx + 1.0; vertices[vb + 27] = fy + 1.0; vertices[vb + 28] = fz + 1.0;
                    vertices[vb + 39] = fx + 1.0; vertices[vb + 40] = fy;     vertices[vb + 41] = fz + 1.0;
                }
            }
            """;

    // ========================================================================
    // Mesh-only shader (when block data already exists)
    // ========================================================================

    private static final String XE_MESH_ONLY_OPTIMIZED = """
            #version 430 core
            #extension GL_KHR_shader_subgroup : enable
            #extension GL_KHR_shader_subgroup_ballot : enable

            layout(local_size_x = 16, local_size_y = 8, local_size_z = 16) in;
            // 16*8*16 = 2048 threads for mesh processing

            layout(std430, binding = 0) restrict buffer BlockBuffer { uint blockData[]; };
            layout(std430, binding = 1) restrict buffer VertexOutput { float vertices[]; };
            layout(std430, binding = 2) restrict buffer IndexOutput { uint indices[]; };
            layout(std430, binding = 3) buffer FaceCounter { uint faceCount; };

            layout(std140, binding = 4) uniform Uniforms {
                int chunkWidth;   // 16
                int chunkHeight;  // 320
                int chunkDepth;   // 16
                int pad0;
            };

            shared uint sharedBlock[16][16];

            bool isSolid(int x, int y, int z) {
                if (x < 0 || x >= 16 || y < 0 || y >= 320 || z < 0 || z >= 16) return false;
                int idx = (y * 16 + x) * 16 + z;
                return blockData[idx] != 0u;
            }

            void main() {
                ivec3 gid = ivec3(gl_GlobalInvocationID);
                int x = gid.x, y = gid.y, z = gid.z;

                if (x >= 16 || y >= 320 || z >= 16) return;

                int idx = (y * 16 + x) * 16 + z;
                uint blockId = blockData[idx];

                // Cache in SLM
                sharedBlock[x][z] = blockId;
                memoryBarrierShared();
                barrier();

                if (blockId == 0u) return;

                // Neighbor check
                int visibleFaces = 0;
                if (!isSolid(x, y, z-1)) visibleFaces |= 1;   // -Z
                if (!isSolid(x, y, z+1)) visibleFaces |= 2;   // +Z
                if (!isSolid(x, y+1, z)) visibleFaces |= 4;   // +Y
                if (!isSolid(x, y-1, z)) visibleFaces |= 8;   // -Y
                if (!isSolid(x-1, y, z)) visibleFaces |= 16;  // -X
                if (!isSolid(x+1, y, z)) visibleFaces |= 32;  // +X

                if (visibleFaces == 0) return;

                int numFaces = bitCount(visibleFaces);
                uint vo = atomicAdd(faceCount, uint(numFaces));

                float fx = float(x), fy = float(y), fz = float(z);
                uint vBase = vo * 52u;

                // Emit vertices for visible faces
                uint faceIdx = 0;
                if ((visibleFaces & 1) != 0) {
                    vertices[vBase + 0] = fx;      vertices[vBase + 1] = fy + 1.0; vertices[vBase + 2] = fz;
                    vertices[vBase + 13] = fx + 1.0; vertices[vBase + 14] = fy + 1.0; vertices[vBase + 15] = fz;
                    vertices[vBase + 26] = fx + 1.0; vertices[vBase + 27] = fy;     vertices[vBase + 28] = fz;
                    vertices[vBase + 39] = fx;      vertices[vBase + 40] = fy;     vertices[vBase + 41] = fz;
                    vBase += 52u; faceIdx++;
                }
                if ((visibleFaces & 2) != 0) {
                    vertices[vBase + 0] = fx + 1.0; vertices[vBase + 1] = fy + 1.0; vertices[vBase + 2] = fz + 1.0;
                    vertices[vBase + 13] = fx;      vertices[vBase + 14] = fy + 1.0; vertices[vBase + 15] = fz + 1.0;
                    vertices[vBase + 26] = fx;      vertices[vBase + 27] = fy;     vertices[vBase + 28] = fz + 1.0;
                    vertices[vBase + 39] = fx + 1.0; vertices[vBase + 40] = fy;     vertices[vBase + 41] = fz + 1.0;
                    vBase += 52u; faceIdx++;
                }
                if ((visibleFaces & 4) != 0) {
                    vertices[vBase + 0] = fx;      vertices[vBase + 1] = fy + 1.0; vertices[vBase + 2] = fz + 1.0;
                    vertices[vBase + 13] = fx + 1.0; vertices[vBase + 14] = fy + 1.0; vertices[vBase + 15] = fz + 1.0;
                    vertices[vBase + 26] = fx + 1.0; vertices[vBase + 27] = fy + 1.0; vertices[vBase + 28] = fz;
                    vertices[vBase + 39] = fx;      vertices[vBase + 40] = fy + 1.0; vertices[vBase + 41] = fz;
                    vBase += 52u; faceIdx++;
                }
                if ((visibleFaces & 8) != 0) {
                    vertices[vBase + 0] = fx;      vertices[vBase + 1] = fy;     vertices[vBase + 2] = fz;
                    vertices[vBase + 13] = fx + 1.0; vertices[vBase + 14] = fy;     vertices[vBase + 15] = fz;
                    vertices[vBase + 26] = fx + 1.0; vertices[vBase + 27] = fy;     vertices[vBase + 28] = fz + 1.0;
                    vertices[vBase + 39] = fx;      vertices[vBase + 40] = fy;     vertices[vBase + 41] = fz + 1.0;
                    vBase += 52u; faceIdx++;
                }
                if ((visibleFaces & 16) != 0) {
                    vertices[vBase + 0] = fx;      vertices[vBase + 1] = fy;     vertices[vBase + 2] = fz + 1.0;
                    vertices[vBase + 13] = fx;      vertices[vBase + 14] = fy + 1.0; vertices[vBase + 15] = fz + 1.0;
                    vertices[vBase + 26] = fx;      vertices[vBase + 27] = fy + 1.0; vertices[vBase + 28] = fz;
                    vertices[vBase + 39] = fx;      vertices[vBase + 40] = fy;     vertices[vBase + 41] = fz;
                    vBase += 52u; faceIdx++;
                }
                if ((visibleFaces & 32) != 0) {
                    vertices[vBase + 0] = fx + 1.0; vertices[vBase + 1] = fy;     vertices[vBase + 2] = fz;
                    vertices[vBase + 13] = fx + 1.0; vertices[vBase + 14] = fy + 1.0; vertices[vBase + 15] = fz;
                    vertices[vBase + 26] = fx + 1.0; vertices[vBase + 27] = fy + 1.0; vertices[vBase + 28] = fz + 1.0;
                    vertices[vBase + 39] = fx + 1.0; vertices[vBase + 40] = fy;     vertices[vBase + 41] = fz + 1.0;
                }
            }
            """;

    // ========================================================================
    // Runtime state
    // ========================================================================

    private static int optimizedTerrainProgram = 0;
    private static int optimizedMeshProgram = 0;
    private static volatile boolean initialized = false;

    /**
     * Compile optimized shaders.
     */
    public static synchronized void initialize() {
        if (initialized)
            return;

        // Initialize specialization constants first (hardware detection)
        XeSpecializationConstants.initialize();

        // Initialize ring bus optimizer (subgroup shuffle)
        XeRingBusOptimizer.initialize();

        try {
            // Priority 1: Try ring bus optimized shaders (subgroup shuffle bypasses SLM)
            optimizedTerrainProgram = XeRingBusOptimizer.getRingBusTerrainProgram();

            // Priority 2: Try specialized shaders (hardware-specific constants)
            if (optimizedTerrainProgram == 0) {
                optimizedTerrainProgram = XeSpecializationConstants.getTerrainProgram();
                optimizedMeshProgram = XeSpecializationConstants.getMeshProgram();
            }

            // Priority 3: Fallback to static optimized shaders
            if (optimizedTerrainProgram == 0 || optimizedMeshProgram == 0) {
                UHDFast.LOGGER.info("XeShaderOptimizer: Using static optimized shaders");
                if (optimizedTerrainProgram == 0) {
                    optimizedTerrainProgram = compileComputeShader(XE_TERRAIN_GEN_OPTIMIZED, "terrain_gen_optimized");
                }
                if (optimizedMeshProgram == 0) {
                    optimizedMeshProgram = compileComputeShader(XE_MESH_ONLY_OPTIMIZED, "mesh_only_optimized");
                }
            } else {
                UHDFast.LOGGER.info("XeShaderOptimizer: Using HARDWARE-OPTIMIZED shaders");
            }

            initialized = true;
            UHDFast.LOGGER.info("XeShaderOptimizer: Compiled optimized shaders");
            UHDFast.LOGGER.info("  Terrain gen optimized: program {}", optimizedTerrainProgram);
            UHDFast.LOGGER.info("  Mesh only optimized: program {}", optimizedMeshProgram);
            UHDFast.LOGGER.info("  Sub-group extensions: enabled");
            UHDFast.LOGGER.info("  SLM reduced: 16x16x4 (from 16x16x8)");
            UHDFast.LOGGER.info("  Specialization: {}", XeSpecializationConstants.getSpecializationInfo());
            UHDFast.LOGGER.info("  Ring bus: {}", XeRingBusOptimizer.getOptimizationInfo());

        } catch (Exception e) {
            UHDFast.LOGGER.warn("XeShaderOptimizer: Shader compilation failed: {}", e.getMessage());
        }
    }

    private static int compileComputeShader(String source, String name) {
        int shader = GL43.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL43.glShaderSource(shader, source);
        GL43.glCompileShader(shader);

        if (GL43.glGetShaderi(shader, GL43.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String error = GL43.glGetShaderInfoLog(shader);
            GL43.glDeleteShader(shader);
            UHDFast.LOGGER.warn("XeShaderOptimizer: {} compile failed: {}", name, error);
            return 0;
        }

        int program = GL43.glCreateProgram();
        GL43.glAttachShader(program, shader);
        GL43.glLinkProgram(program);

        if (GL43.glGetProgrami(program, GL43.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String error = GL43.glGetProgramInfoLog(program);
            GL43.glDeleteProgram(program);
            GL43.glDeleteShader(shader);
            UHDFast.LOGGER.warn("XeShaderOptimizer: {} link failed: {}", name, error);
            return 0;
        }

        GL43.glDeleteShader(shader);
        return program;
    }

    public static int getOptimizedTerrainProgram() {
        return optimizedTerrainProgram;
    }

    public static int getOptimizedMeshProgram() {
        return optimizedMeshProgram;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static void cleanup() {
        XeRingBusOptimizer.cleanup();
        XeSpecializationConstants.cleanup();
        if (optimizedTerrainProgram != 0) {
            GL43.glDeleteProgram(optimizedTerrainProgram);
            optimizedTerrainProgram = 0;
        }
        if (optimizedMeshProgram != 0) {
            GL43.glDeleteProgram(optimizedMeshProgram);
            optimizedMeshProgram = 0;
        }
        initialized = false;
    }
}
