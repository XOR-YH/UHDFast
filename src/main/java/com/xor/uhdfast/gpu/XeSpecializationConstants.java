package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.*;

import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Xe Specialization Constants - leverage compile-time constant folding for GPU
 * shaders.
 * 
 * Intel Xe GPUs benefit significantly from specialization constants because:
 * 1. Constants are folded into shader at SPIR-V compilation time
 * 2. Driver can optimize branching and unroll loops based on known values
 * 3. Eliminates uniform buffer fetches for unchanging parameters
 * 4. Allows better register allocation and instruction scheduling
 * 
 * Based on Intel oneAPI GPU Optimization Guide:
 * "Use Specialization Constants for Parameters That Are Constant Per Pipeline"
 * 
 * In OpenGL, we use GL_ARB_gl_spirv extension with SPIR-V shaders.
 * For GLSL source, we simulate this via #define substitution before
 * compilation.
 */
public class XeSpecializationConstants {

    // ========================================================================
    // Xe Hardware Constants (detected at runtime, constant per GPU session)
    // ========================================================================

    /** Number of Execution Units (EUs) - detected from Intel Xe hardware */
    private static volatile int euCount = 16;

    /** Maximum threads per EU (Xe-LP: 7, Xe-HPG: 8) */
    private static volatile int maxThreadsPerEU = 7;

    /** SIMD width (8 for Xe-LP, 16 for Xe-HPG) */
    private static volatile int simdWidth = 8;

    /** Shared Local Memory per tile (64KB for Xe) */
    private static final int SLM_SIZE_BYTES = 64 * 1024;

    // ========================================================================
    // Terrain Generation Constants (constant per chunk, specialized at compile
    // time)
    // ========================================================================

    /** Chunk dimensions */
    public static final int CHUNK_WIDTH = 16;
    public static final int CHUNK_HEIGHT = 320;
    public static final int CHUNK_DEPTH = 16;

    /** Noise octaves for terrain generation */
    public static final int NOISE_OCTAVES = 4;

    /** Noise scale factor */
    public static final float NOISE_SCALE = 0.005f;

    /** Sea level */
    public static final float DEFAULT_SEA_LEVEL = 62.0f;

    /** Number of biome types */
    public static final int BIOME_COUNT = 8;

    // ========================================================================
    // Workgroup Optimization Constants
    // ========================================================================

    /**
     * Optimal workgroup X dimension - matches Xe SIMD width.
     * Xe-LP: 8 (SIMD-8), Xe-HPG: 16 (SIMD-16)
     */
    public static volatile int optimalWorkgroupX = 16;

    /**
     * Optimal workgroup Y dimension - chosen to maximize EU occupancy.
     */
    public static volatile int optimalWorkgroupY = 16;

    /**
     * Optimal workgroup Z dimension - balances SLM usage and occupancy.
     */
    public static volatile int optimalWorkgroupZ = 4;

    /** Total threads per workgroup */
    public static volatile int threadsPerWorkgroup = 1024; // 16*16*4

    // ========================================================================
    // Shader Specialization
    // ========================================================================

    /**
     * Generate terrain shader source with Xe-specialized constants.
     * Substitutes #define values based on detected hardware.
     */
    public static String generateSpecializedTerrainShader() {
        StringBuilder sb = new StringBuilder();

        // Specialization preamble - Xe hardware constants
        sb.append("#version 430 core\n");
        sb.append("#extension GL_KHR_shader_subgroup : enable\n");
        sb.append("#extension GL_KHR_shader_subgroup_arithmetic : enable\n");
        sb.append("#extension GL_KHR_shader_subgroup_ballot : enable\n\n");

        // Xe specialization constants
        sb.append("// Xe specialization constants (runtime-detected)\n");
        sb.append(String.format("#define EU_COUNT %d\n", euCount));
        sb.append(String.format("#define SIMD_WIDTH %d\n", simdWidth));
        sb.append(String.format("#define MAX_THREADS_PER_EU %d\n", maxThreadsPerEU));
        sb.append(String.format("#define SLM_SIZE_BYTES %d\n", SLM_SIZE_BYTES));
        sb.append(String.format("#define WORKGROUP_X %d\n", optimalWorkgroupX));
        sb.append(String.format("#define WORKGROUP_Y %d\n", optimalWorkgroupY));
        sb.append(String.format("#define WORKGROUP_Z %d\n", optimalWorkgroupZ));
        sb.append(String.format("#define NOISE_OCTAVES %d\n", NOISE_OCTAVES));
        sb.append(String.format("#define CHUNK_WIDTH %d\n", CHUNK_WIDTH));
        sb.append(String.format("#define CHUNK_HEIGHT %d\n", CHUNK_HEIGHT));
        sb.append(String.format("#define CHUNK_DEPTH %d\n", CHUNK_DEPTH));
        sb.append(String.format("#define BIOME_COUNT %d\n", BIOME_COUNT));
        sb.append(String.format("#define NOISE_SCALE %.6f\n", NOISE_SCALE));
        sb.append("\n");

        // Workgroup layout - uses specialized constants
        sb.append("layout(local_size_x = WORKGROUP_X, local_size_y = WORKGROUP_Y, local_size_z = WORKGROUP_Z) in;\n\n");

        // SSBO bindings
        sb.append("layout(std430, binding = 0) buffer NoiseBuffer { float noiseData[]; };\n");
        sb.append("layout(std430, binding = 1) buffer BiomeBuffer { uint biomeData[]; };\n");
        sb.append("layout(std430, binding = 2) buffer HeightBuffer { uint heightData[]; };\n");
        sb.append("layout(std430, binding = 3) buffer BlockBuffer { uint blockData[]; };\n");
        sb.append("layout(std430, binding = 4) buffer SkyLightBuffer { uint skyLight[]; };\n");
        sb.append("layout(std430, binding = 5) buffer VertexOutput { float vertices[]; };\n");
        sb.append("layout(std430, binding = 6) buffer IndexOutput { uint indices[]; };\n");
        sb.append("layout(std430, binding = 7) buffer FaceCounter { uint faceCount; };\n\n");

        // Uniforms
        sb.append("layout(std140, binding = 8) uniform Uniforms {\n");
        sb.append("    uint seed;\n");
        sb.append("    int chunkX;\n");
        sb.append("    int chunkZ;\n");
        sb.append("    int chunkY;\n");
        sb.append("    int worldHeight;\n");
        sb.append("    float seaLevel;\n");
        sb.append("    int pad0;\n");
        sb.append("    int pad1;\n");
        sb.append("};\n\n");

        // Shared Local Memory - sized for Xe SLM budget
        sb.append("// Shared Local Memory - Xe SLM (64KB per tile)\n");
        sb.append("shared float sharedNoise[WORKGROUP_X][WORKGROUP_Z];\n");
        sb.append("shared uint sharedBlock[WORKGROUP_X][WORKGROUP_Y][WORKGROUP_Z];\n\n");

        // Simplex noise
        sb.append(SIMPLEX_NOISE_IMPL);

        // Multi-octave noise - unrolled loop using NOISE_OCTAVES constant
        sb.append("// Multi-octave noise - Xe unrolled for SIMD optimization\n");
        sb.append("float fbmNoise(vec3 p) {\n");
        sb.append("    float value = 0.0;\n");
        sb.append("    float amplitude = 0.5;\n");
        sb.append("    float frequency = 1.0;\n\n");
        sb.append("    // Unrolled for ").append(NOISE_OCTAVES).append(" octaves (Xe SIMD optimization)\n");
        for (int i = 0; i < NOISE_OCTAVES; i++) {
            double factor = Math.pow(2.0, i);
            double amp = 0.5 / factor;
            sb.append(String.format("    value += snoise(p * frequency * %.1f) * %.4f;\n", factor, amp));
            if (i < NOISE_OCTAVES - 1) {
                sb.append("    frequency *= 2.0;\n");
            }
        }
        sb.append("\n    return value;\n}\n\n");

        // Hash function
        sb.append("uint hash(uint x) {\n");
        sb.append("    x += seed;\n");
        sb.append("    x = (x ^ (x >> 16)) * 0x45d9f3b;\n");
        sb.append("    x = (x ^ (x >> 16)) * 0x45d9f3b;\n");
        sb.append("    x = x ^ (x >> 16);\n");
        sb.append("    return x;\n}\n\n");

        // Biome determination
        sb.append("uint getBiome(int x, int z) {\n");
        sb.append("    uint h = hash(hash(uint(chunkX * 3129871 + x)) + hash(uint(chunkZ * 1161297 + z)));\n");
        sb.append("    return h % BIOME_COUNT;\n}\n\n");

        // Cave detection
        sb.append("bool isCave(vec3 pos, float density) {\n");
        sb.append("    float caveNoise1 = snoise(pos * 0.1 + vec3(seed * 0.001));\n");
        sb.append("    float caveNoise2 = snoise(pos * 0.05 + vec3(seed * 0.002));\n");
        sb.append("    return (caveNoise1 * 0.6 + caveNoise2 * 0.4) > density;\n}\n\n");

        // Main function
        sb.append("void main() {\n");
        sb.append("    ivec3 gid = ivec3(gl_GlobalInvocationID);\n");
        sb.append("    if (gid.x >= CHUNK_WIDTH || gid.y >= CHUNK_HEIGHT || gid.z >= CHUNK_DEPTH) return;\n\n");

        sb.append("    int x = gid.x, y = gid.y, z = gid.z;\n");
        sb.append("    int idx = (y * CHUNK_WIDTH + x) * CHUNK_DEPTH + z;\n\n");

        sb.append("    vec3 worldPos = vec3(chunkX * CHUNK_WIDTH + x, y, chunkZ * CHUNK_DEPTH + z);\n");
        sb.append("    float terrainNoise = fbmNoise(worldPos * NOISE_SCALE);\n\n");

        sb.append("    float height = seaLevel + terrainNoise * 30.0;\n");
        sb.append("    uint biome = getBiome(x, z);\n\n");

        sb.append("    sharedNoise[x][y % WORKGROUP_Z] = terrainNoise;\n\n");

        sb.append("    uint blockId = 0;\n");
        sb.append("    if (y < height - 5) {\n");
        sb.append("        blockId = 1;\n");
        sb.append("        if (isCave(worldPos, 0.3)) blockId = 0;\n");
        sb.append("    } else if (y < height - 1) {\n");
        sb.append("        blockId = 3;\n");
        sb.append("    } else if (y < height) {\n");
        sb.append("        switch (int(biome)) {\n");
        sb.append("            case 0: blockId = 2; break;\n");
        sb.append("            case 1: blockId = 4; break;\n");
        sb.append("            case 2: blockId = 5; break;\n");
        sb.append("            case 3: blockId = 6; break;\n");
        sb.append("            default: blockId = 2;\n");
        sb.append("        }\n");
        sb.append("    } else if (y < seaLevel) {\n");
        sb.append("        blockId = 6;\n");
        sb.append("    }\n\n");

        sb.append("    sharedBlock[x][z][y % WORKGROUP_Z] = blockId;\n\n");

        sb.append("    memoryBarrierShared();\n");
        sb.append("    barrier();\n\n");

        sb.append("    blockData[idx] = blockId;\n");
        sb.append("    heightData[(x * CHUNK_DEPTH + z)] = uint(height);\n");
        sb.append("    biomeData[(x * CHUNK_DEPTH + z)] = biome;\n\n");

        // Face visibility with subgroup optimization
        sb.append("    bool isSolid = blockId != 0;\n");
        sb.append("    bool neighborSolid[6];\n\n");

        sb.append("    neighborSolid[0] = (z > 0) ? (sharedBlock[x][z-1][y % WORKGROUP_Z] != 0) : false;\n");
        sb.append(
                "    neighborSolid[1] = (z < CHUNK_DEPTH-1) ? (sharedBlock[x][z+1][y % WORKGROUP_Z] != 0) : false;\n");
        sb.append(
                "    neighborSolid[2] = (y < CHUNK_HEIGHT-1) ? (blockData[((y+1)*CHUNK_WIDTH+x)*CHUNK_DEPTH+z] != 0) : false;\n");
        sb.append("    neighborSolid[3] = (y > 0) ? (blockData[((y-1)*CHUNK_WIDTH+x)*CHUNK_DEPTH+z] != 0) : false;\n");
        sb.append("    neighborSolid[4] = (x > 0) ? (sharedBlock[x-1][z][y % WORKGROUP_Z] != 0) : false;\n");
        sb.append(
                "    neighborSolid[5] = (x < CHUNK_WIDTH-1) ? (sharedBlock[x+1][z][y % WORKGROUP_Z] != 0) : false;\n\n");

        sb.append("    if (!isSolid) return;\n\n");

        sb.append("    int visibleFaces = 0;\n");
        sb.append("    if (!neighborSolid[0]) visibleFaces |= 1;\n");
        sb.append("    if (!neighborSolid[1]) visibleFaces |= 2;\n");
        sb.append("    if (!neighborSolid[2]) visibleFaces |= 4;\n");
        sb.append("    if (!neighborSolid[3]) visibleFaces |= 8;\n");
        sb.append("    if (!neighborSolid[4]) visibleFaces |= 16;\n");
        sb.append("    if (!neighborSolid[5]) visibleFaces |= 32;\n\n");

        sb.append("    if (visibleFaces == 0) return;\n\n");

        sb.append("    uint faceBits = subgroupBallot(isSolid && visibleFaces != 0);\n");
        sb.append("    int localFaceCount = subgroupBallotBitCount(faceBits);\n\n");

        sb.append("    if (localFaceCount == 0) return;\n\n");

        sb.append("    uint vo = atomicAdd(faceCount, uint(visibleFaces != 0 ? bitCount(visibleFaces) : 0));\n");
        sb.append("    if (visibleFaces == 0) return;\n\n");

        sb.append("    uint vBase = vo * 52u;\n");
        sb.append("    float fx = float(x), fy = float(y), fz = float(z);\n\n");

        // Vertex emission (same as original)
        sb.append(VERTEX_EMISSION_CODE);

        sb.append("}\n");

        return sb.toString();
    }

    /**
     * Generate mesh-only shader source with Xe specialization constants.
     */
    public static String generateSpecializedMeshShader() {
        StringBuilder sb = new StringBuilder();

        // Preamble
        sb.append("#version 430 core\n");
        sb.append("#extension GL_KHR_shader_subgroup : enable\n");
        sb.append("#extension GL_KHR_shader_subgroup_arithmetic : enable\n\n");

        // Xe constants
        sb.append(String.format("#define WORKGROUP_X %d\n", optimalWorkgroupX));
        sb.append(String.format("#define WORKGROUP_Y %d\n", optimalWorkgroupY));
        sb.append(String.format("#define WORKGROUP_Z %d\n", optimalWorkgroupZ));
        sb.append(String.format("#define CHUNK_WIDTH %d\n", CHUNK_WIDTH));
        sb.append(String.format("#define CHUNK_HEIGHT %d\n", CHUNK_HEIGHT));
        sb.append(String.format("#define CHUNK_DEPTH %d\n", CHUNK_DEPTH));
        sb.append("\n");

        sb.append("layout(local_size_x = WORKGROUP_X, local_size_y = WORKGROUP_Y, local_size_z = WORKGROUP_Z) in;\n\n");

        // SSBO bindings
        sb.append("layout(std430, binding = 0) buffer BlockBuffer { uint blockData[]; };\n");
        sb.append("layout(std430, binding = 1) buffer VertexOutput { float vertices[]; };\n");
        sb.append("layout(std430, binding = 2) buffer IndexOutput { uint indices[]; };\n");
        sb.append("layout(std430, binding = 3) buffer FaceCounter { uint faceCount; };\n\n");

        // Uniforms
        sb.append("layout(std140, binding = 4) uniform Uniforms {\n");
        sb.append("    int chunkWidth;\n");
        sb.append("    int chunkHeight;\n");
        sb.append("    int chunkDepth;\n");
        sb.append("    int pad0;\n");
        sb.append("};\n\n");

        sb.append("shared uint sharedBlock[WORKGROUP_X][WORKGROUP_Y][WORKGROUP_Z];\n\n");

        // Block access function
        sb.append("bool isBlockSolid(int x, int y, int z) {\n");
        sb.append("    if (x < 0 || x >= CHUNK_WIDTH || y < 0 || y >= CHUNK_HEIGHT || z < 0 || z >= CHUNK_DEPTH) {\n");
        sb.append("        return false;\n");
        sb.append("    }\n");
        sb.append("    int index = (y * CHUNK_WIDTH + x) * CHUNK_DEPTH + z;\n");
        sb.append("    return blockData[index] != 0u;\n");
        sb.append("}\n\n");

        // Main function
        sb.append("void main() {\n");
        sb.append("    ivec3 gid = ivec3(gl_GlobalInvocationID);\n");
        sb.append("    if (gid.x >= CHUNK_WIDTH || gid.y >= CHUNK_HEIGHT || gid.z >= CHUNK_DEPTH) return;\n\n");

        sb.append("    int x = gid.x, y = gid.y, z = gid.z;\n");
        sb.append("    uint blockId = blockData[(y * CHUNK_WIDTH + x) * CHUNK_DEPTH + z];\n");
        sb.append("    sharedBlock[x][z][y % WORKGROUP_Z] = blockId;\n\n");

        sb.append("    memoryBarrierShared();\n");
        sb.append("    barrier();\n\n");

        sb.append("    if (blockId == 0u) return;\n\n");

        // Face visibility
        sb.append("    bool visible[6];\n");
        sb.append("    visible[0] = !isBlockSolid(x, y, z - 1);\n");
        sb.append("    visible[1] = !isBlockSolid(x, y, z + 1);\n");
        sb.append("    visible[2] = !isBlockSolid(x, y + 1, z);\n");
        sb.append("    visible[3] = !isBlockSolid(x, y - 1, z);\n");
        sb.append("    visible[4] = !isBlockSolid(x - 1, y, z);\n");
        sb.append("    visible[5] = !isBlockSolid(x + 1, y, z);\n\n");

        sb.append("    int visibleFaces = 0;\n");
        sb.append("    if (visible[0]) visibleFaces |= 1;\n");
        sb.append("    if (visible[1]) visibleFaces |= 2;\n");
        sb.append("    if (visible[2]) visibleFaces |= 4;\n");
        sb.append("    if (visible[3]) visibleFaces |= 8;\n");
        sb.append("    if (visible[4]) visibleFaces |= 16;\n");
        sb.append("    if (visible[5]) visibleFaces |= 32;\n\n");

        sb.append("    if (visibleFaces == 0) return;\n\n");

        sb.append("    uint vo = atomicAdd(faceCount, uint(bitCount(visibleFaces)));\n");
        sb.append("    uint vBase = vo * 52u;\n");
        sb.append("    float fx = float(x), fy = float(y), fz = float(z);\n\n");

        sb.append(VERTEX_EMISSION_CODE);

        sb.append("}\n");

        return sb.toString();
    }

    // ========================================================================
    // Simplex Noise Implementation (shared between shaders)
    // ========================================================================
    private static final String SIMPLEX_NOISE_IMPL = """
            vec3 mod289(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
            vec4 mod289(vec4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
            vec4 permute(vec4 x) { return mod289(((x*34.0)+1.0)*x); }

            float snoise(vec3 v) {
                const vec2 C = vec2(1.0/6.0, 1.0/3.0);
                const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);

                vec3 i  = floor(v + dot(v, C.yyy));
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

                vec4 s0 = floor(b0) * 2.0 + 1.0;
                vec4 s1 = floor(b1) * 2.0 + 1.0;
                vec4 sh = -step(h, vec4(0.0));

                vec4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
                vec4 a1 = b1.xzyw + s1.xzyw * sh.zzww;

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

            """;

    // ========================================================================
    // Vertex Emission Code (shared between shaders)
    // ========================================================================
    private static final String VERTEX_EMISSION_CODE = """
            if ((visibleFaces & 1) != 0) {
                vertices[vBase + 0] = fx;     vertices[vBase + 1] = fy + 1.0; vertices[vBase + 2] = fz;
                vertices[vBase + 13] = fx + 1.0; vertices[vBase + 14] = fy + 1.0; vertices[vBase + 15] = fz;
                vertices[vBase + 26] = fx + 1.0; vertices[vBase + 27] = fy;     vertices[vBase + 28] = fz;
                vertices[vBase + 39] = fx;     vertices[vBase + 40] = fy;     vertices[vBase + 41] = fz;
                vBase += 52u;
            }
            if ((visibleFaces & 2) != 0) {
                vertices[vBase + 0] = fx + 1.0; vertices[vBase + 1] = fy + 1.0; vertices[vBase + 2] = fz + 1.0;
                vertices[vBase + 13] = fx;     vertices[vBase + 14] = fy + 1.0; vertices[vBase + 15] = fz + 1.0;
                vertices[vBase + 26] = fx;     vertices[vBase + 27] = fy;     vertices[vBase + 28] = fz + 1.0;
                vertices[vBase + 39] = fx + 1.0; vertices[vBase + 40] = fy;     vertices[vBase + 41] = fz + 1.0;
                vBase += 52u;
            }
            if ((visibleFaces & 4) != 0) {
                vertices[vBase + 0] = fx;     vertices[vBase + 1] = fy + 1.0; vertices[vBase + 2] = fz + 1.0;
                vertices[vBase + 13] = fx + 1.0; vertices[vBase + 14] = fy + 1.0; vertices[vBase + 15] = fz + 1.0;
                vertices[vBase + 26] = fx + 1.0; vertices[vBase + 27] = fy + 1.0; vertices[vBase + 28] = fz;
                vertices[vBase + 39] = fx;     vertices[vBase + 40] = fy + 1.0; vertices[vBase + 41] = fz;
                vBase += 52u;
            }
            if ((visibleFaces & 8) != 0) {
                vertices[vBase + 0] = fx;     vertices[vBase + 1] = fy;     vertices[vBase + 2] = fz;
                vertices[vBase + 13] = fx + 1.0; vertices[vBase + 14] = fy;     vertices[vBase + 15] = fz;
                vertices[vBase + 26] = fx + 1.0; vertices[vBase + 27] = fy;     vertices[vBase + 28] = fz + 1.0;
                vertices[vBase + 39] = fx;     vertices[vBase + 40] = fy;     vertices[vBase + 41] = fz + 1.0;
                vBase += 52u;
            }
            if ((visibleFaces & 16) != 0) {
                vertices[vBase + 0] = fx;     vertices[vBase + 1] = fy;     vertices[vBase + 2] = fz + 1.0;
                vertices[vBase + 13] = fx;     vertices[vBase + 14] = fy + 1.0; vertices[vBase + 15] = fz + 1.0;
                vertices[vBase + 26] = fx;     vertices[vBase + 27] = fy + 1.0; vertices[vBase + 28] = fz;
                vertices[vBase + 39] = fx;     vertices[vBase + 40] = fy;     vertices[vBase + 41] = fz;
                vBase += 52u;
            }
            if ((visibleFaces & 32) != 0) {
                vertices[vBase + 0] = fx + 1.0; vertices[vBase + 1] = fy;     vertices[vBase + 2] = fz;
                vertices[vBase + 13] = fx + 1.0; vertices[vBase + 14] = fy + 1.0; vertices[vBase + 15] = fz;
                vertices[vBase + 26] = fx + 1.0; vertices[vBase + 27] = fy + 1.0; vertices[vBase + 28] = fz + 1.0;
                vertices[vBase + 39] = fx + 1.0; vertices[vBase + 40] = fy;     vertices[vBase + 41] = fz + 1.0;
            }

            // Index generation
            if ((visibleFaces & 1) != 0) {
                uint iBase = vo * 6u;
                indices[iBase + 0] = vo * 4u;
                indices[iBase + 1] = vo * 4u + 1u;
                indices[iBase + 2] = vo * 4u + 2u;
                indices[iBase + 3] = vo * 4u;
                indices[iBase + 4] = vo * 4u + 2u;
                indices[iBase + 5] = vo * 4u + 3u;
            }
            """;

    // ========================================================================
    // Initialization and Xe Hardware Detection
    // ========================================================================

    private static volatile boolean initialized = false;
    private static final AtomicInteger compilationCount = new AtomicInteger(0);

    /**
     * Initialize specialization constants based on Xe hardware detection.
     * Called once at startup.
     */
    public static synchronized void initialize() {
        if (initialized)
            return;

        IntelXeDetector.detect();

        euCount = IntelXeDetector.getEUCount();
        IntelXeDetector.XeArchitectureType arch = IntelXeDetector.getArchitectureType();

        // Set SIMD width based on architecture
        switch (arch) {
            case GEN12_LP:
            case GEN12_HP:
                simdWidth = 8;
                maxThreadsPerEU = 7;
                break;
            case XE_HPG:
            case XE_HPC:
            case XE2_LPG:
                simdWidth = 16;
                maxThreadsPerEU = 8;
                break;
            default:
                simdWidth = 8;
                maxThreadsPerEU = 7;
        }

        // Calculate optimal workgroup dimensions
        calculateOptimalWorkgroup();

        initialized = true;
        compilationCount.incrementAndGet();

        UHDFast.LOGGER.info("XeSpecializationConstants: Initialized for {} ({} EUs, SIMD-{}, {} threads/EU)",
                arch, euCount, simdWidth, maxThreadsPerEU);
        UHDFast.LOGGER.info("  Optimal workgroup: {}x{}x{} ({} threads)",
                optimalWorkgroupX, optimalWorkgroupY, optimalWorkgroupZ, threadsPerWorkgroup);
    }

    /**
     * Calculate optimal workgroup dimensions for Xe hardware.
     * Goal: Maximize EU occupancy while staying within SLM budget.
     */
    private static void calculateOptimalWorkgroup() {
        // Xe workgroup sizing rules:
        // 1. local_size_x should be multiple of SIMD width
        // 2. Total threads should allow multiple workgroups per EU
        // 3. SLM usage should not exceed 64KB per tile

        optimalWorkgroupX = Math.max(simdWidth, 8); // At least SIMD width
        optimalWorkgroupY = 16; // Good for 2D terrain processing
        optimalWorkgroupZ = 4; // Fits in SLM budget (16*16*4*4bytes = 4KB per array)

        threadsPerWorkgroup = optimalWorkgroupX * optimalWorkgroupY * optimalWorkgroupZ;

        // Verify SLM budget (we use 2 arrays: sharedNoise + sharedBlock)
        // sharedNoise: X*Z * 4 bytes (float)
        // sharedBlock: X*Y*Z * 4 bytes (uint)
        int slmUsage = (optimalWorkgroupX * optimalWorkgroupZ * 4) +
                (optimalWorkgroupX * optimalWorkgroupY * optimalWorkgroupZ * 4);

        if (slmUsage > SLM_SIZE_BYTES) {
            // Reduce Z dimension to fit in SLM
            optimalWorkgroupZ = Math.max(1, (SLM_SIZE_BYTES / 2) / (optimalWorkgroupX * optimalWorkgroupY * 4));
            threadsPerWorkgroup = optimalWorkgroupX * optimalWorkgroupY * optimalWorkgroupZ;
        }

        UHDFast.LOGGER.debug("Xe workgroup optimization: {}x{}x{} (SLM: {}KB / 64KB)",
                optimalWorkgroupX, optimalWorkgroupY, optimalWorkgroupZ, slmUsage / 1024);
    }

    // ========================================================================
    // Compiled Shader Programs
    // ========================================================================

    private static volatile int specializedTerrainProgram = 0;
    private static volatile int specializedMeshProgram = 0;

    /**
     * Get compiled specialized terrain shader program.
     * Compiles on first call.
     */
    public static synchronized int getTerrainProgram() {
        if (!initialized)
            initialize();
        if (specializedTerrainProgram != 0)
            return specializedTerrainProgram;

        String shaderSource = generateSpecializedTerrainShader();
        specializedTerrainProgram = compileComputeShader(shaderSource, "XeTerrainSpecialized");
        return specializedTerrainProgram;
    }

    /**
     * Get compiled specialized mesh shader program.
     * Compiles on first call.
     */
    public static synchronized int getMeshProgram() {
        if (!initialized)
            initialize();
        if (specializedMeshProgram != 0)
            return specializedMeshProgram;

        String shaderSource = generateSpecializedMeshShader();
        specializedMeshProgram = compileComputeShader(shaderSource, "XeMeshSpecialized");
        return specializedMeshProgram;
    }

    private static int compileComputeShader(String source, String name) {
        int shader = GL43.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL43.glShaderSource(shader, source);
        GL43.glCompileShader(shader);

        if (GL43.glGetShaderi(shader, GL43.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String error = GL43.glGetShaderInfoLog(shader);
            GL43.glDeleteShader(shader);
            UHDFast.LOGGER.warn("XeSpecializationConstants: {} shader compile failed: {}", name, error);
            return 0;
        }

        int program = GL43.glCreateProgram();
        GL43.glAttachShader(program, shader);
        GL43.glLinkProgram(program);

        if (GL43.glGetProgrami(program, GL43.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String error = GL43.glGetProgramInfoLog(program);
            GL43.glDeleteProgram(program);
            GL43.glDeleteShader(shader);
            UHDFast.LOGGER.warn("XeSpecializationConstants: {} program link failed: {}", name, error);
            return 0;
        }

        GL43.glDeleteShader(shader);

        // Query workgroup size
        IntBuffer wgSize = org.lwjgl.system.MemoryUtil.memAllocInt(3);
        GL43.glGetProgramiv(program, GL43.GL_COMPUTE_WORK_GROUP_SIZE, wgSize);
        int wx = wgSize.get(0);
        int wy = wgSize.get(1);
        int wz = wgSize.get(2);
        org.lwjgl.system.MemoryUtil.memFree(wgSize);

        UHDFast.LOGGER.info("XeSpecializationConstants: {} program compiled (workgroup: {}x{}x{})",
                name, wx, wy, wz);

        compilationCount.incrementAndGet();
        return program;
    }

    // ========================================================================
    // Dispatch Calculation Helpers
    // ========================================================================

    /**
     * Calculate dispatch dimensions for terrain generation.
     * 优化：使用缓存数组避免每帧分配
     */
    private static int[] cachedTerrainDispatch = null;
    private static int[] cachedMeshDispatch = null;

    public static int[] calcTerrainDispatch() {
        if (cachedTerrainDispatch == null) {
            cachedTerrainDispatch = new int[] {
                    (CHUNK_WIDTH + optimalWorkgroupX - 1) / optimalWorkgroupX,
                    (CHUNK_HEIGHT + optimalWorkgroupY - 1) / optimalWorkgroupY,
                    (CHUNK_DEPTH + optimalWorkgroupZ - 1) / optimalWorkgroupZ
            };
        }
        return cachedTerrainDispatch;
    }

    /**
     * Calculate dispatch dimensions for mesh building.
     * 优化：使用缓存数组避免每帧分配
     */
    public static int[] calcMeshDispatch() {
        if (cachedMeshDispatch == null) {
            cachedMeshDispatch = new int[] {
                    (CHUNK_WIDTH + optimalWorkgroupX - 1) / optimalWorkgroupX,
                    (CHUNK_HEIGHT + optimalWorkgroupY - 1) / optimalWorkgroupY,
                    (CHUNK_DEPTH + optimalWorkgroupZ - 1) / optimalWorkgroupZ
            };
        }
        return cachedMeshDispatch;
    }

    // ========================================================================
    // Statistics
    // ========================================================================

    public static int getCompilationCount() {
        return compilationCount.get();
    }

    public static int getEUCount() {
        return euCount;
    }

    public static int getSIMDWidth() {
        return simdWidth;
    }

    public static int getThreadsPerWorkgroup() {
        return threadsPerWorkgroup;
    }

    public static String getSpecializationInfo() {
        return String.format("EU=%d SIMD=%d WG=%dx%dx%d threads=%d",
                euCount, simdWidth, optimalWorkgroupX, optimalWorkgroupY, optimalWorkgroupZ,
                threadsPerWorkgroup);
    }

    // ========================================================================
    // Cleanup
    // ========================================================================

    public static synchronized void cleanup() {
        if (specializedTerrainProgram != 0) {
            GL43.glDeleteProgram(specializedTerrainProgram);
            specializedTerrainProgram = 0;
        }
        if (specializedMeshProgram != 0) {
            GL43.glDeleteProgram(specializedMeshProgram);
            specializedMeshProgram = 0;
        }
        initialized = false;
        UHDFast.LOGGER.info("XeSpecializationConstants: Cleaned up");
    }
}
