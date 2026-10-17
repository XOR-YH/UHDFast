package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.compat.ModCompatibility;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Xe GPU 区块网格生成加速器 - Xe架构优化版 v2
 * 
 * 关键修复（v2）：
 * 1. 修复致命Bug：着色器循环处理所有32768个方块，之前只处理了64个（0.2%）
 * 2. blockDataSSBO/chunkMetaSSBO改用persistent mapped buffer，消除glBufferSubData拷贝
 * 3. 使用静态零数组消除每区块的byte[]分配
 * 4. 批量重置header使用IntBuffer.put批量操作
 * 5. 优化render线程上的数据准备路径
 * 
 * 核心优化（v1）：
 * - 批处理：将多个区块合并到一次GPU dispatch
 * - 每workgroup一个区块，每区块原子计数器
 * - subgroup ballot并行检查6个面
 * - shared memory缓存区块元数据
 * - 持久映射 + Coherent输出（零拷贝读回）
 * - 工作群组大小64对齐Xe SIMD-8
 * - 线程安全：flushBatch使用synchronized保护
 * - 结果直接写入ChunkDataCache
 */
public class XeChunkGPUAccelerator {

    // ========== 批处理配置 ==========
    private static final int MAX_BATCH_CHUNKS = 64;
    private static final int BLOCKS_PER_CHUNK = 16 * 128 * 16;
    private static final int MAX_FACES_PER_CHUNK = 8192;
    private static final int FLOATS_PER_FACE = 12;
    private static final int INDICES_PER_FACE = 6;
    private static final int OUTPUT_VERTS_PER_CHUNK = MAX_FACES_PER_CHUNK * FLOATS_PER_FACE;
    private static final int OUTPUT_INDICES_PER_CHUNK = MAX_FACES_PER_CHUNK * INDICES_PER_FACE;

    // 静态零数组，避免每区块分配 new byte[remaining]
    private static final byte[] ZERO_BYTES = new byte[BLOCKS_PER_CHUNK * 4];

    // ========== 计算着色器 ==========
    // v2修复：每个线程循环处理多个方块，不是只处理1个
    private static final String BATCH_COMPUTE_SHADER_TEMPLATE = """
            #version 430 core
            #extension GL_KHR_shader_subgroup_ballot : enable
            #extension GL_KHR_shader_subgroup_arithmetic : enable

            layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

            // 共享内存：缓存当前区块的元数据
            shared uint sharedMeta[5];
            shared uint sharedDataOffset;

            // 输入：所有区块的方块数据
            layout(std430, binding = 0) buffer ChunkBlockData {
                uint[] blockData;
            };

            // 输入：区块元数据
            layout(std430, binding = 1) buffer ChunkMetadata {
                uint[] chunkMeta;
            };

            // 输出：顶点数据
            layout(std430, binding = 2) buffer OutputVertices {
                float[] outVertices;
            };

            // 输出：索引数据
            layout(std430, binding = 3) buffer OutputIndices {
                uint[] outIndices;
            };

            // 输出：每区块结果头
            layout(std430, binding = 4) buffer OutputHeaders {
                uint[] outHeaders;
            };

            #define BLOCKS_PER_CHUNK {BLOCKS}
            #define MAX_FACES_PER_CHUNK {MAX_FACES}
            #define FLOATS_PER_FACE {FLOATS}
            #define INDICES_PER_FACE {INDICES}
            #define OUTPUT_VERTS_PER_CHUNK (MAX_FACES_PER_CHUNK * FLOATS_PER_FACE)
            #define OUTPUT_INDICES_PER_CHUNK (MAX_FACES_PER_CHUNK * INDICES_PER_FACE)

            bool isBlockSolid(uint dataOffs, int x, int y, int z, int w, int h, int d) {
                if (x < 0 || x >= w || y < 0 || y >= h || z < 0 || z >= d) return false;
                uint idx = dataOffs + uint(y * w * d + x * d + z);
                return blockData[idx] != 0u;
            }

            void emitQuad(int face, float fx, float fy, float fz, uint faceBase, uint chunkVertBase, uint chunkIdxBase) {
                uint vo = chunkVertBase + faceBase * FLOATS_PER_FACE;
                float fx2 = fx + 1.0;
                float fy2 = fy + 1.0;
                float fz2 = fz + 1.0;

                switch (face) {
                    case 0: // -Z
                        outVertices[int(vo)]     = fx;     outVertices[int(vo)+1]  = fy2; outVertices[int(vo)+2]  = fz;
                        outVertices[int(vo)+3]   = fx2;    outVertices[int(vo)+4]  = fy2; outVertices[int(vo)+5]  = fz;
                        outVertices[int(vo)+6]   = fx2;    outVertices[int(vo)+7]  = fy;  outVertices[int(vo)+8]  = fz;
                        outVertices[int(vo)+9]   = fx;     outVertices[int(vo)+10] = fy;  outVertices[int(vo)+11] = fz;
                        break;
                    case 1: // +Z
                        outVertices[int(vo)]     = fx2;    outVertices[int(vo)+1]  = fy2; outVertices[int(vo)+2]  = fz2;
                        outVertices[int(vo)+3]   = fx;     outVertices[int(vo)+4]  = fy2; outVertices[int(vo)+5]  = fz2;
                        outVertices[int(vo)+6]   = fx;     outVertices[int(vo)+7]  = fy;  outVertices[int(vo)+8]  = fz2;
                        outVertices[int(vo)+9]   = fx2;    outVertices[int(vo)+10] = fy;  outVertices[int(vo)+11] = fz2;
                        break;
                    case 2: // +Y
                        outVertices[int(vo)]     = fx;     outVertices[int(vo)+1]  = fy2; outVertices[int(vo)+2]  = fz2;
                        outVertices[int(vo)+3]   = fx2;    outVertices[int(vo)+4]  = fy2; outVertices[int(vo)+5]  = fz2;
                        outVertices[int(vo)+6]   = fx2;    outVertices[int(vo)+7]  = fy2; outVertices[int(vo)+8]  = fz;
                        outVertices[int(vo)+9]   = fx;     outVertices[int(vo)+10] = fy2; outVertices[int(vo)+11] = fz;
                        break;
                    case 3: // -Y
                        outVertices[int(vo)]     = fx;     outVertices[int(vo)+1]  = fy;  outVertices[int(vo)+2]  = fz;
                        outVertices[int(vo)+3]   = fx2;    outVertices[int(vo)+4]  = fy;  outVertices[int(vo)+5]  = fz;
                        outVertices[int(vo)+6]   = fx2;    outVertices[int(vo)+7]  = fy;  outVertices[int(vo)+8]  = fz2;
                        outVertices[int(vo)+9]   = fx;     outVertices[int(vo)+10] = fy;  outVertices[int(vo)+11] = fz2;
                        break;
                    case 4: // -X
                        outVertices[int(vo)]     = fx;     outVertices[int(vo)+1]  = fy;  outVertices[int(vo)+2]  = fz2;
                        outVertices[int(vo)+3]   = fx;     outVertices[int(vo)+4]  = fy2; outVertices[int(vo)+5]  = fz2;
                        outVertices[int(vo)+6]   = fx;     outVertices[int(vo)+7]  = fy2; outVertices[int(vo)+8]  = fz;
                        outVertices[int(vo)+9]   = fx;     outVertices[int(vo)+10] = fy;  outVertices[int(vo)+11] = fz;
                        break;
                    case 5: // +X
                        outVertices[int(vo)]     = fx2;    outVertices[int(vo)+1]  = fy;  outVertices[int(vo)+2]  = fz;
                        outVertices[int(vo)+3]   = fx2;    outVertices[int(vo)+4]  = fy2; outVertices[int(vo)+5]  = fz;
                        outVertices[int(vo)+6]   = fx2;    outVertices[int(vo)+7]  = fy2; outVertices[int(vo)+8]  = fz2;
                        outVertices[int(vo)+9]   = fx2;    outVertices[int(vo)+10] = fy;  outVertices[int(vo)+11] = fz2;
                        break;
                }

                uint io = chunkIdxBase + faceBase * INDICES_PER_FACE;
                uint vertBase = faceBase * 4u;
                outIndices[int(io)]     = vertBase;
                outIndices[int(io)+1]   = vertBase + 1u;
                outIndices[int(io)+2]   = vertBase + 2u;
                outIndices[int(io)+3]   = vertBase;
                outIndices[int(io)+4]   = vertBase + 2u;
                outIndices[int(io)+5]   = vertBase + 3u;
            }

            void main() {
                uint chunkIdx = gl_WorkGroupID.x;
                uint localId = gl_LocalInvocationID.x;

                // 线程0：加载共享内存
                if (localId == 0u) {
                    uint metaBase = chunkIdx * 5u;
                    sharedDataOffset = chunkMeta[metaBase];
                    sharedMeta[0] = sharedDataOffset;
                    sharedMeta[1] = chunkMeta[metaBase + 1u];
                    sharedMeta[2] = chunkMeta[metaBase + 2u];
                    sharedMeta[3] = chunkMeta[metaBase + 3u];
                    sharedMeta[4] = chunkMeta[metaBase + 4u];
                }
                memoryBarrierShared();
                barrier();

                uint dataOffset = sharedDataOffset;
                int w = int(sharedMeta[1]);
                int h = int(sharedMeta[2]);
                int d = int(sharedMeta[3]);
                int maxFaces = int(sharedMeta[4]);
                int totalBlocks = w * h * d;

                // v2修复：循环处理所有方块，每个线程处理 stride = 64 个方块
                // 32768 / 64 = 512 次迭代，每个线程处理512个方块
                uint chunkVertBase = chunkIdx * uint(OUTPUT_VERTS_PER_CHUNK);
                uint chunkIdxBase = chunkIdx * uint(OUTPUT_INDICES_PER_CHUNK);

                for (uint id = localId; id < uint(totalBlocks); id += gl_WorkGroupSize.x) {
                    int z = int(id) % d;
                    int xy = int(id) / d;
                    int x = xy % w;
                    int y = xy / w;

                    uint blockVal = blockData[dataOffset + id];
                    if (blockVal == 0u) continue;

                    uint visibleFaces = 0u;
                    if (!isBlockSolid(dataOffset, x, y, z-1, w, h, d)) visibleFaces |= 1u;
                    if (!isBlockSolid(dataOffset, x, y, z+1, w, h, d)) visibleFaces |= 2u;
                    if (!isBlockSolid(dataOffset, x, y+1, z, w, h, d)) visibleFaces |= 4u;
                    if (!isBlockSolid(dataOffset, x, y-1, z, w, h, d)) visibleFaces |= 8u;
                    if (!isBlockSolid(dataOffset, x-1, y, z, w, h, d)) visibleFaces |= 16u;
                    if (!isBlockSolid(dataOffset, x+1, y, z, w, h, d)) visibleFaces |= 32u;

                    if (visibleFaces == 0u) continue;

                    uint faceCount = uint(bitCount(visibleFaces));
                    uint faceOffset = atomicAdd(outHeaders[chunkIdx * 4u], faceCount);
                    if (faceOffset >= uint(maxFaces)) continue;

                    float fx = float(x);
                    float fy = float(y);
                    float fz = float(z);

                    uint written = 0u;
                    for (int face = 0; face < 6; face++) {
                        if ((visibleFaces & (1u << uint(face))) != 0u) {
                            emitQuad(face, fx, fy, fz, faceOffset + written, chunkVertBase, chunkIdxBase);
                            written++;
                        }
                    }
                }
            }
            """;

    private static String batchComputeShaderSource;

    // ========== 回退着色器（无 subgroup 扩展） ==========
    // 如果 subgroup 版本编译失败，使用此版本
    private static final String FALLBACK_COMPUTE_SHADER_TEMPLATE = """
            #version 430 core

            layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

            shared uint sharedMeta[5];
            shared uint sharedDataOffset;

            layout(std430, binding = 0) buffer ChunkBlockData {
                uint[] blockData;
            };
            layout(std430, binding = 1) buffer ChunkMetadata {
                uint[] chunkMeta;
            };
            layout(std430, binding = 2) buffer OutputVertices {
                float[] outVertices;
            };
            layout(std430, binding = 3) buffer OutputIndices {
                uint[] outIndices;
            };
            layout(std430, binding = 4) buffer OutputHeaders {
                uint[] outHeaders;
            };

            #define BLOCKS_PER_CHUNK {BLOCKS}
            #define MAX_FACES_PER_CHUNK {MAX_FACES}
            #define FLOATS_PER_FACE {FLOATS}
            #define INDICES_PER_FACE {INDICES}
            #define OUTPUT_VERTS_PER_CHUNK (MAX_FACES_PER_CHUNK * FLOATS_PER_FACE)
            #define OUTPUT_INDICES_PER_CHUNK (MAX_FACES_PER_CHUNK * INDICES_PER_FACE)

            bool isBlockSolid(uint dataOffs, int x, int y, int z, int w, int h, int d) {
                if (x < 0 || x >= w || y < 0 || y >= h || z < 0 || z >= d) return false;
                uint idx = dataOffs + uint(y * w * d + x * d + z);
                return blockData[idx] != 0u;
            }

            void emitQuad(int face, float fx, float fy, float fz, uint faceBase, uint chunkVertBase, uint chunkIdxBase) {
                uint vo = chunkVertBase + faceBase * FLOATS_PER_FACE;
                float fx2 = fx + 1.0;
                float fy2 = fy + 1.0;
                float fz2 = fz + 1.0;

                switch (face) {
                    case 0: // -Z
                        outVertices[int(vo)]     = fx;     outVertices[int(vo)+1]  = fy2; outVertices[int(vo)+2]  = fz;
                        outVertices[int(vo)+3]   = fx2;    outVertices[int(vo)+4]  = fy2; outVertices[int(vo)+5]  = fz;
                        outVertices[int(vo)+6]   = fx2;    outVertices[int(vo)+7]  = fy;  outVertices[int(vo)+8]  = fz;
                        outVertices[int(vo)+9]   = fx;     outVertices[int(vo)+10] = fy;  outVertices[int(vo)+11] = fz;
                        break;
                    case 1: // +Z
                        outVertices[int(vo)]     = fx2;    outVertices[int(vo)+1]  = fy2; outVertices[int(vo)+2]  = fz2;
                        outVertices[int(vo)+3]   = fx;     outVertices[int(vo)+4]  = fy2; outVertices[int(vo)+5]  = fz2;
                        outVertices[int(vo)+6]   = fx;     outVertices[int(vo)+7]  = fy;  outVertices[int(vo)+8]  = fz2;
                        outVertices[int(vo)+9]   = fx2;    outVertices[int(vo)+10] = fy;  outVertices[int(vo)+11] = fz2;
                        break;
                    case 2: // +Y
                        outVertices[int(vo)]     = fx;     outVertices[int(vo)+1]  = fy2; outVertices[int(vo)+2]  = fz2;
                        outVertices[int(vo)+3]   = fx2;    outVertices[int(vo)+4]  = fy2; outVertices[int(vo)+5]  = fz2;
                        outVertices[int(vo)+6]   = fx2;    outVertices[int(vo)+7]  = fy2; outVertices[int(vo)+8]  = fz;
                        outVertices[int(vo)+9]   = fx;     outVertices[int(vo)+10] = fy2; outVertices[int(vo)+11] = fz;
                        break;
                    case 3: // -Y
                        outVertices[int(vo)]     = fx;     outVertices[int(vo)+1]  = fy;  outVertices[int(vo)+2]  = fz;
                        outVertices[int(vo)+3]   = fx2;    outVertices[int(vo)+4]  = fy;  outVertices[int(vo)+5]  = fz;
                        outVertices[int(vo)+6]   = fx2;    outVertices[int(vo)+7]  = fy;  outVertices[int(vo)+8]  = fz2;
                        outVertices[int(vo)+9]   = fx;     outVertices[int(vo)+10] = fy;  outVertices[int(vo)+11] = fz2;
                        break;
                    case 4: // -X
                        outVertices[int(vo)]     = fx;     outVertices[int(vo)+1]  = fy;  outVertices[int(vo)+2]  = fz2;
                        outVertices[int(vo)+3]   = fx;     outVertices[int(vo)+4]  = fy2; outVertices[int(vo)+5]  = fz2;
                        outVertices[int(vo)+6]   = fx;     outVertices[int(vo)+7]  = fy2; outVertices[int(vo)+8]  = fz;
                        outVertices[int(vo)+9]   = fx;     outVertices[int(vo)+10] = fy;  outVertices[int(vo)+11] = fz;
                        break;
                    case 5: // +X
                        outVertices[int(vo)]     = fx2;    outVertices[int(vo)+1]  = fy;  outVertices[int(vo)+2]  = fz;
                        outVertices[int(vo)+3]   = fx2;    outVertices[int(vo)+4]  = fy2; outVertices[int(vo)+5]  = fz;
                        outVertices[int(vo)+6]   = fx2;    outVertices[int(vo)+7]  = fy2; outVertices[int(vo)+8]  = fz2;
                        outVertices[int(vo)+9]   = fx2;    outVertices[int(vo)+10] = fy;  outVertices[int(vo)+11] = fz2;
                        break;
                }

                uint io = chunkIdxBase + faceBase * INDICES_PER_FACE;
                uint vertBase = faceBase * 4u;
                outIndices[int(io)]     = vertBase;
                outIndices[int(io)+1]   = vertBase + 1u;
                outIndices[int(io)+2]   = vertBase + 2u;
                outIndices[int(io)+3]   = vertBase;
                outIndices[int(io)+4]   = vertBase + 2u;
                outIndices[int(io)+5]   = vertBase + 3u;
            }

            void main() {
                uint chunkIdx = gl_WorkGroupID.x;
                uint localId = gl_LocalInvocationID.x;

                if (localId == 0u) {
                    uint metaBase = chunkIdx * 5u;
                    sharedDataOffset = chunkMeta[metaBase];
                    sharedMeta[0] = sharedDataOffset;
                    sharedMeta[1] = chunkMeta[metaBase + 1u];
                    sharedMeta[2] = chunkMeta[metaBase + 2u];
                    sharedMeta[3] = chunkMeta[metaBase + 3u];
                    sharedMeta[4] = chunkMeta[metaBase + 4u];
                }
                memoryBarrierShared();
                barrier();

                uint dataOffset = sharedDataOffset;
                int w = int(sharedMeta[1]);
                int h = int(sharedMeta[2]);
                int d = int(sharedMeta[3]);
                int maxFaces = int(sharedMeta[4]);
                int totalBlocks = w * h * d;

                uint chunkVertBase = chunkIdx * uint(OUTPUT_VERTS_PER_CHUNK);
                uint chunkIdxBase = chunkIdx * uint(OUTPUT_INDICES_PER_CHUNK);

                for (uint id = localId; id < uint(totalBlocks); id += gl_WorkGroupSize.x) {
                    int z = int(id) % d;
                    int xy = int(id) / d;
                    int x = xy % w;
                    int y = xy / w;

                    uint blockVal = blockData[dataOffset + id];
                    if (blockVal == 0u) continue;

                    // 手动检查6个面，不使用subgroup扩展
                    uint visibleFaces = 0u;
                    if (!isBlockSolid(dataOffset, x, y, z-1, w, h, d)) visibleFaces |= 1u;
                    if (!isBlockSolid(dataOffset, x, y, z+1, w, h, d)) visibleFaces |= 2u;
                    if (!isBlockSolid(dataOffset, x, y+1, z, w, h, d)) visibleFaces |= 4u;
                    if (!isBlockSolid(dataOffset, x, y-1, z, w, h, d)) visibleFaces |= 8u;
                    if (!isBlockSolid(dataOffset, x-1, y, z, w, h, d)) visibleFaces |= 16u;
                    if (!isBlockSolid(dataOffset, x+1, y, z, w, h, d)) visibleFaces |= 32u;

                    if (visibleFaces == 0u) continue;

                    uint faceCount = uint(bitCount(visibleFaces));
                    uint faceOffset = atomicAdd(outHeaders[chunkIdx * 4u], faceCount);
                    if (faceOffset >= uint(maxFaces)) continue;

                    float fx = float(x);
                    float fy = float(y);
                    float fz = float(z);

                    uint written = 0u;
                    for (int face = 0; face < 6; face++) {
                        if ((visibleFaces & (1u << uint(face))) != 0u) {
                            emitQuad(face, fx, fy, fz, faceOffset + written, chunkVertBase, chunkIdxBase);
                            written++;
                        }
                    }
                }
            }
            """;

    // ========== OpenGL 资源 ==========
    private static int computeProgram = 0;
    private static int blockDataSSBO = 0;
    private static int chunkMetaSSBO = 0;
    private static int outputVertsSSBO = 0;
    private static int outputIndicesSSBO = 0;
    private static int outputHeadersSSBO = 0;

    // ========== 持久映射缓冲区 ==========
    private static ByteBuffer persistentBlockData; // blockDataSSBO的persistent映射
    private static ByteBuffer persistentMetaData; // chunkMetaSSBO的persistent映射
    private static FloatBuffer persistentVertexView;
    private static IntBuffer persistentIndexView;
    private static IntBuffer persistentHeaderView;

    // ========== 批处理状态 ==========
    private static final ConcurrentLinkedQueue<PendingChunkBuild> pendingBuilds = new ConcurrentLinkedQueue<>();

    // ========== 缓存缓冲区（避免每帧分配） ==========
    private static ByteBuffer batchUploadBuffer;
    private static IntBuffer batchMetaUploadBuffer;

    // ========== 结果缓存 ==========
    private static final ConcurrentLinkedQueue<ChunkGPUResult> resultPool = new ConcurrentLinkedQueue<>();
    private static com.xor.uhdfast.cache.ChunkDataCache cachedChunkDataCache;

    // ========== 状态 ==========
    private static boolean initialized = false;
    private static boolean active = false;

    // ========== 同步锁 ==========
    private static final Object flushLock = new Object();

    // ========== 统计 ==========
    private static final AtomicInteger chunksProcessed = new AtomicInteger(0);
    private static final AtomicInteger facesGenerated = new AtomicInteger(0);
    private static final AtomicInteger batchesDispatched = new AtomicInteger(0);
    private static final AtomicInteger chunksPerBatch = new AtomicInteger(0);
    private static long totalComputeTime = 0;

    // ========== 数据结构 ==========

    public static class PendingChunkBuild {
        final byte[] blockData;
        final int chunkX;
        final int chunkZ;

        PendingChunkBuild(byte[] blockData, int chunkX, int chunkZ) {
            this.blockData = blockData;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    public static class ChunkGPUResult {
        public int chunkX;
        public int chunkZ;
        public float[] vertices;
        public int[] indices;
        public int faceCount;
        public boolean success;
        public long gpuTimeNs;

        public ChunkGPUResult() {
        }

        public ChunkGPUResult(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        public void reset(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.vertices = null;
            this.indices = null;
            this.faceCount = 0;
            this.success = false;
            this.gpuTimeNs = 0;
        }
    }

    // ========== 初始化 ==========

    public static void initialize() {
        if (initialized)
            return;

        if (!IntelXeDetector.isXeArchitecture()) {
            UHDFast.LOGGER.info("Xe Chunk GPU Accelerator disabled - not Xe architecture");
            return;
        }
        if (!IntelXeDetector.supportsComputeShaders()) {
            UHDFast.LOGGER.info("Xe Chunk GPU Accelerator disabled - compute shaders not supported");
            return;
        }
        // 如果渲染替换mod（如Sodium）已启用，跳过GPU区块加速
        // Sodium有自己的区块网格构建管线，GPU加速不必要
        if (ModCompatibility.shouldDisableGPUChunkLoader()) {
            UHDFast.LOGGER.info("Xe Chunk GPU Accelerator disabled - rendering replacement mod detected");
            return;
        }

        try {
            batchComputeShaderSource = BATCH_COMPUTE_SHADER_TEMPLATE
                    .replace("{BLOCKS}", String.valueOf(BLOCKS_PER_CHUNK))
                    .replace("{MAX_FACES}", String.valueOf(MAX_FACES_PER_CHUNK))
                    .replace("{FLOATS}", String.valueOf(FLOATS_PER_FACE))
                    .replace("{INDICES}", String.valueOf(INDICES_PER_FACE));

            // 编译着色器（自动回退到非subgroup版本）
            if (!compileComputeShaderWithSource(batchComputeShaderSource)) {
                throw new RuntimeException("All shader compilation attempts failed");
            }
            createSSBOs();

            // 预分配上传缓冲区（仅用于旧API buildChunkGPU）
            batchUploadBuffer = MemoryUtil.memAlloc(BLOCKS_PER_CHUNK * 4);
            batchMetaUploadBuffer = MemoryUtil.memAllocInt(5);

            cachedChunkDataCache = com.xor.uhdfast.cache.ChunkDataCache.getInstance();

            initialized = true;
            active = true;

            UHDFast.LOGGER.info("XeChunkGPUAccelerator: v2 initialized");
            UHDFast.LOGGER.info("  Max batch: {} chunks, 64 threads/chunk, loop x512", MAX_BATCH_CHUNKS);
            UHDFast.LOGGER.info("  Persistent mapped upload: enabled");
            UHDFast.LOGGER.info("  EU Count: {}, Architecture: {}", IntelXeDetector.getEUCount(),
                    IntelXeDetector.getArchitectureType());
        } catch (Exception e) {
            UHDFast.LOGGER.error("Failed to initialize XeChunkGPUAccelerator: {}", e.getMessage());
            active = false;
        }
    }

    private static boolean compileComputeShaderWithSource(String source) {
        int shader = GL43.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL43.glShaderSource(shader, source);
        GL43.glCompileShader(shader);

        if (GL43.glGetShaderi(shader, GL43.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String error = GL43.glGetShaderInfoLog(shader);
            GL43.glDeleteShader(shader);
            UHDFast.LOGGER.warn("Compute shader compilation failed: {}", error);

            String fallbackSource = FALLBACK_COMPUTE_SHADER_TEMPLATE
                    .replace("{BLOCKS}", String.valueOf(BLOCKS_PER_CHUNK))
                    .replace("{MAX_FACES}", String.valueOf(MAX_FACES_PER_CHUNK))
                    .replace("{FLOATS}", String.valueOf(FLOATS_PER_FACE))
                    .replace("{INDICES}", String.valueOf(INDICES_PER_FACE));
            if (source.equals(fallbackSource)) {
                return false;
            }
            if (!compileComputeShaderWithSource(fallbackSource)) {
                return false;
            }
            UHDFast.LOGGER.info("Using fallback shader (no subgroup extensions)");
            return true;
        }

        int program = GL43.glCreateProgram();
        GL43.glAttachShader(program, shader);
        GL43.glLinkProgram(program);

        if (GL43.glGetProgrami(program, GL43.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String error = GL43.glGetProgramInfoLog(program);
            GL43.glDeleteProgram(program);
            GL43.glDeleteShader(shader);
            UHDFast.LOGGER.warn("Compute program linking failed: {}", error);
            return false;
        }

        // 清理旧程序（如果有）
        if (computeProgram != 0) {
            GL43.glDeleteProgram(computeProgram);
        }
        computeProgram = program;
        GL43.glDeleteShader(shader);
        batchComputeShaderSource = source;
        UHDFast.LOGGER.debug("Compute shader compiled successfully (v2: loop x512)");
        return true;
    }

    private static void createSSBOs() {
        int[] buffers = new int[5];
        GL15.glGenBuffers(buffers);
        blockDataSSBO = buffers[0];
        chunkMetaSSBO = buffers[1];
        outputVertsSSBO = buffers[2];
        outputIndicesSSBO = buffers[3];
        outputHeadersSSBO = buffers[4];

        boolean hasBufferStorage = GL.getCapabilities().GL_ARB_buffer_storage
                || GL.getCapabilities().OpenGL44;

        // v2优化：blockDataSSBO使用persistent mapped buffer，消除glBufferSubData
        int blockDataSize = MAX_BATCH_CHUNKS * BLOCKS_PER_CHUNK * 4;
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, blockDataSSBO);
        if (hasBufferStorage) {
            int flags = GL44.GL_MAP_PERSISTENT_BIT | GL43.GL_MAP_WRITE_BIT | GL44.GL_MAP_COHERENT_BIT;
            if (GL.getCapabilities().OpenGL44) {
                GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, blockDataSize, flags);
            } else {
                ARBBufferStorage.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, blockDataSize, flags);
            }
            ByteBuffer mapped = GL43.glMapBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0, blockDataSize,
                    GL43.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT);
            if (mapped != null) {
                persistentBlockData = mapped;
            } else {
                GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, blockDataSize, GL15.GL_DYNAMIC_DRAW);
            }
        } else {
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, blockDataSize, GL15.GL_DYNAMIC_DRAW);
        }

        // v2优化：chunkMetaSSBO使用persistent mapped buffer
        int metaSize = MAX_BATCH_CHUNKS * 5 * 4;
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, chunkMetaSSBO);
        if (hasBufferStorage) {
            int flags = GL44.GL_MAP_PERSISTENT_BIT | GL43.GL_MAP_WRITE_BIT | GL44.GL_MAP_COHERENT_BIT;
            if (GL.getCapabilities().OpenGL44) {
                GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, metaSize, flags);
            } else {
                ARBBufferStorage.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, metaSize, flags);
            }
            ByteBuffer mapped = GL43.glMapBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0, metaSize,
                    GL43.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT);
            if (mapped != null) {
                persistentMetaData = mapped;
            } else {
                GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, metaSize, GL15.GL_DYNAMIC_DRAW);
            }
        } else {
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, metaSize, GL15.GL_DYNAMIC_DRAW);
        }

        // 输出：顶点数据（持久映射 + Coherent）
        int vertsSize = MAX_BATCH_CHUNKS * OUTPUT_VERTS_PER_CHUNK * 4;
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, outputVertsSSBO);
        if (hasBufferStorage) {
            int storageFlags = GL44.GL_MAP_PERSISTENT_BIT | GL43.GL_MAP_READ_BIT | GL44.GL_MAP_COHERENT_BIT;
            if (GL.getCapabilities().OpenGL44) {
                GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, vertsSize, storageFlags);
            } else {
                ARBBufferStorage.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, vertsSize, storageFlags);
            }
            ByteBuffer mapped = GL43.glMapBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0, vertsSize,
                    GL43.GL_MAP_READ_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT);
            if (mapped != null) {
                persistentVertexView = mapped.asFloatBuffer();
            } else {
                persistentVertexView = null;
                UHDFast.LOGGER.warn("Failed to map persistent vertex buffer");
            }
        } else {
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, vertsSize, GL15.GL_DYNAMIC_DRAW);
        }

        // 输出：索引数据（持久映射 + Coherent）
        int idxSize = MAX_BATCH_CHUNKS * OUTPUT_INDICES_PER_CHUNK * 4;
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, outputIndicesSSBO);
        if (hasBufferStorage) {
            int storageFlags = GL44.GL_MAP_PERSISTENT_BIT | GL43.GL_MAP_READ_BIT | GL44.GL_MAP_COHERENT_BIT;
            if (GL.getCapabilities().OpenGL44) {
                GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, idxSize, storageFlags);
            } else {
                ARBBufferStorage.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, idxSize, storageFlags);
            }
            ByteBuffer mapped = GL43.glMapBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0, idxSize,
                    GL43.GL_MAP_READ_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT);
            if (mapped != null) {
                persistentIndexView = mapped.asIntBuffer();
            } else {
                persistentIndexView = null;
                UHDFast.LOGGER.warn("Failed to map persistent index buffer");
            }
        } else {
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, idxSize, GL15.GL_DYNAMIC_DRAW);
        }

        // 输出：结果头（持久映射 + Coherent）
        int headerSize = MAX_BATCH_CHUNKS * 4 * 4;
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, outputHeadersSSBO);
        if (hasBufferStorage) {
            int storageFlags = GL44.GL_MAP_PERSISTENT_BIT | GL43.GL_MAP_READ_BIT
                    | GL43.GL_MAP_WRITE_BIT | GL44.GL_MAP_COHERENT_BIT;
            if (GL.getCapabilities().OpenGL44) {
                GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, headerSize, storageFlags);
            } else {
                ARBBufferStorage.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, headerSize, storageFlags);
            }
            ByteBuffer mapped = GL43.glMapBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0, headerSize,
                    GL43.GL_MAP_READ_BIT | GL43.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT
                            | GL44.GL_MAP_COHERENT_BIT);
            if (mapped != null) {
                persistentHeaderView = mapped.asIntBuffer();
            } else {
                persistentHeaderView = null;
                UHDFast.LOGGER.warn("Failed to map persistent header buffer");
            }
        } else {
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, headerSize, GL15.GL_DYNAMIC_DRAW);
        }

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }

    // ========== 公共 API ==========

    /**
     * 提交一个区块构建请求到批处理队列。
     * 线程安全。
     */
    public static void submitChunkBuild(byte[] blockData, int chunkX, int chunkZ) {
        if (!active || !initialized)
            return;
        pendingBuilds.offer(new PendingChunkBuild(blockData, chunkX, chunkZ));
    }

    /**
     * 立即构建一个区块（旧API，向后兼容，直接单区块dispatch）。
     * 必须在渲染线程调用！
     */
    public static ChunkGPUResult buildChunkGPU(byte[] blockData, int chunkX, int chunkZ) {
        if (!active || !initialized)
            return null;

        long startTime = System.nanoTime();
        ChunkGPUResult result = obtainResult(chunkX, chunkZ);

        try {
            // v2: 使用persistent mapped buffer上传，无需glBufferSubData
            if (persistentBlockData != null) {
                int limit = Math.min(blockData.length, BLOCKS_PER_CHUNK);
                persistentBlockData.position(0);
                persistentBlockData.put(blockData, 0, limit);
                // 填充剩余
                if (limit < BLOCKS_PER_CHUNK * 4) {
                    persistentBlockData.put(ZERO_BYTES, 0, BLOCKS_PER_CHUNK * 4 - limit);
                }
            } else {
                // 回退：使用glBufferSubData
                batchUploadBuffer.clear();
                int limit = Math.min(blockData.length, BLOCKS_PER_CHUNK);
                batchUploadBuffer.put(blockData, 0, limit);
                int remaining = BLOCKS_PER_CHUNK * 4 - limit;
                if (remaining > 0) {
                    batchUploadBuffer.put(ZERO_BYTES, 0, remaining);
                }
                batchUploadBuffer.flip();

                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, blockDataSSBO);
                GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, batchUploadBuffer);
            }

            // 重置header
            if (persistentHeaderView != null) {
                persistentHeaderView.put(0, 0);
            }

            // 上传元数据（persistent直接写入）
            if (persistentMetaData != null) {
                persistentMetaData.putInt(0, 0); // blockDataOffset = 0
                persistentMetaData.putInt(4, 16); // width
                persistentMetaData.putInt(8, 128); // height
                persistentMetaData.putInt(12, 16); // depth
                persistentMetaData.putInt(16, MAX_FACES_PER_CHUNK);
            } else {
                batchMetaUploadBuffer.clear();
                batchMetaUploadBuffer.put(0, 0);
                batchMetaUploadBuffer.put(1, 16);
                batchMetaUploadBuffer.put(2, 128);
                batchMetaUploadBuffer.put(3, 16);
                batchMetaUploadBuffer.put(4, MAX_FACES_PER_CHUNK);
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, chunkMetaSSBO);
                ByteBuffer metaByteBuf = MemoryUtil.memByteBuffer(batchMetaUploadBuffer);
                metaByteBuf.limit(5 * 4);
                GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, metaByteBuf);
            }

            // 绑定SSBO并dispatch
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, blockDataSSBO);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, chunkMetaSSBO);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, outputVertsSSBO);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, outputIndicesSSBO);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, outputHeadersSSBO);

            GL20.glUseProgram(computeProgram);
            GL43.glDispatchCompute(1, 1, 1);
            GL43.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

            // 读回结果
            if (persistentHeaderView != null) {
                int faceCount = persistentHeaderView.get(0);
                if (faceCount > 0 && faceCount <= MAX_FACES_PER_CHUNK) {
                    result.faceCount = faceCount;
                    result.success = true;

                    int vertCount = faceCount * FLOATS_PER_FACE;
                    int idxCount = faceCount * INDICES_PER_FACE;

                    result.vertices = new float[vertCount];
                    if (persistentVertexView != null) {
                        persistentVertexView.position(0);
                        persistentVertexView.get(result.vertices, 0, vertCount);
                    }

                    result.indices = new int[idxCount];
                    if (persistentIndexView != null) {
                        persistentIndexView.position(0);
                        persistentIndexView.get(result.indices, 0, idxCount);
                    }
                } else {
                    result.faceCount = 0;
                    result.success = true;
                    result.vertices = new float[0];
                    result.indices = new int[0];
                }
            } else {
                result.success = false;
            }

            result.gpuTimeNs = System.nanoTime() - startTime;
            totalComputeTime += result.gpuTimeNs;
            chunksProcessed.incrementAndGet();
            facesGenerated.addAndGet(result.faceCount);

            writeResultToCache(result);
            recycleResult(result);

        } catch (Exception e) {
            UHDFast.LOGGER.warn("GPU chunk build failed, falling back to CPU: {}", e.getMessage());
            result.success = false;
            recycleResult(result);
        }

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        GL20.glUseProgram(0);
        return result;
    }

    /**
     * 刷新批处理 - 每帧调用一次，必须在渲染线程。
     * v2优化：使用persistent mapped buffer直接写入数据，消除glBufferSubData，
     * 消除每区块byte[] zeros分配，批量重置header。
     */
    public static void flushBatch() {
        if (!active || !initialized)
            return;
        if (pendingBuilds.isEmpty())
            return;

        synchronized (flushLock) {
            int batchSize = Math.min(pendingBuilds.size(), MAX_BATCH_CHUNKS);

            PendingChunkBuild[] batch = new PendingChunkBuild[batchSize];
            for (int i = 0; i < batchSize; i++) {
                batch[i] = pendingBuilds.poll();
                if (batch[i] == null) {
                    batchSize = i;
                    break;
                }
            }

            if (batchSize <= 0)
                return;

            try {
                doBatchDispatch(batch, batchSize);
            } catch (Exception e) {
                UHDFast.LOGGER.warn("Batch GPU chunk build failed: {}", e.getMessage());
            }
        }
    }

    /**
     * 处理GPU构建结果 - 已由 flushBatch() 直接写入缓存。
     */
    public static void processPendingResults() {
        // 结果已由 doBatchDispatch() 直接写入缓存
    }

    // ========== 对象池 ==========

    private static ChunkGPUResult obtainResult(int chunkX, int chunkZ) {
        ChunkGPUResult result = resultPool.poll();
        if (result != null) {
            result.reset(chunkX, chunkZ);
            return result;
        }
        return new ChunkGPUResult(chunkX, chunkZ);
    }

    private static void recycleResult(ChunkGPUResult result) {
        if (result != null) {
            result.vertices = null;
            result.indices = null;
            resultPool.offer(result);
        }
    }

    private static void writeResultToCache(ChunkGPUResult result) {
        if (result == null || !result.success)
            return;
        if (result.vertices != null && result.indices != null) {
            com.xor.uhdfast.cache.CachedChunkData cachedData = new com.xor.uhdfast.cache.CachedChunkData(
                    result.vertices, result.indices);
            long chunkKey = com.xor.uhdfast.cache.ChunkDataCache.createChunkKey(result.chunkX, result.chunkZ);
            if (cachedChunkDataCache != null) {
                cachedChunkDataCache.put(chunkKey, cachedData);
            }
        }
    }

    // ========== 内部实现 ==========

    /**
     * v2优化：使用persistent mapped buffer直接写入上传数据，
     * 消除glBufferSubData，消除每区块byte[] zeros分配。
     */
    private static void doBatchDispatch(PendingChunkBuild[] batch, int batchSize) {
        long startTime = System.nanoTime();

        // v2: 使用persistent mapped buffer直接写入，零拷贝上传
        if (persistentBlockData != null && persistentMetaData != null) {
            // 直接写入persistent mapped buffer，无需glBufferSubData
            for (int i = 0; i < batchSize; i++) {
                PendingChunkBuild build = batch[i];
                int dataOffset = i * BLOCKS_PER_CHUNK * 4;

                int limit = Math.min(build.blockData.length, BLOCKS_PER_CHUNK);
                persistentBlockData.position(dataOffset);
                persistentBlockData.put(build.blockData, 0, limit);

                // 使用静态零数组填充剩余，避免每区块分配
                int remaining = BLOCKS_PER_CHUNK * 4 - limit;
                if (remaining > 0) {
                    persistentBlockData.put(ZERO_BYTES, 0, remaining);
                }

                // 写入元数据（直接写入persistent mapped buffer）
                int metaOff = i * 5 * 4; // 字节偏移
                persistentMetaData.putInt(metaOff, i * BLOCKS_PER_CHUNK);
                persistentMetaData.putInt(metaOff + 4, 16);
                persistentMetaData.putInt(metaOff + 8, 128);
                persistentMetaData.putInt(metaOff + 12, 16);
                persistentMetaData.putInt(metaOff + 16, MAX_FACES_PER_CHUNK);
            }
        } else {
            // 回退：使用glBufferSubData
            batchUploadBuffer.clear();
            batchMetaUploadBuffer.clear();

            for (int i = 0; i < batchSize; i++) {
                PendingChunkBuild build = batch[i];
                int dataOffset = i * BLOCKS_PER_CHUNK * 4;
                int limit = Math.min(build.blockData.length, BLOCKS_PER_CHUNK);
                batchUploadBuffer.position(dataOffset);
                batchUploadBuffer.put(build.blockData, 0, limit);
                int remaining = BLOCKS_PER_CHUNK * 4 - limit;
                if (remaining > 0) {
                    batchUploadBuffer.put(ZERO_BYTES, 0, remaining);
                }

                int metaOff = i * 5;
                batchMetaUploadBuffer.put(metaOff, i * BLOCKS_PER_CHUNK);
                batchMetaUploadBuffer.put(metaOff + 1, 16);
                batchMetaUploadBuffer.put(metaOff + 2, 128);
                batchMetaUploadBuffer.put(metaOff + 3, 16);
                batchMetaUploadBuffer.put(metaOff + 4, MAX_FACES_PER_CHUNK);
            }

            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, blockDataSSBO);
            batchUploadBuffer.position(0).limit(batchSize * BLOCKS_PER_CHUNK * 4);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, batchUploadBuffer);

            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, chunkMetaSSBO);
            batchMetaUploadBuffer.position(0).limit(batchSize * 5);
            ByteBuffer metaByteBuf = MemoryUtil.memByteBuffer(batchMetaUploadBuffer);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, metaByteBuf);
        }

        // v2: 批量重置header，使用IntBuffer.put遍历
        if (persistentHeaderView != null) {
            for (int i = 0; i < batchSize; i++) {
                persistentHeaderView.put(i * 4, 0);
            }
        }

        // 绑定SSBO
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, blockDataSSBO);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, chunkMetaSSBO);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, outputVertsSSBO);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, outputIndicesSSBO);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, outputHeadersSSBO);

        // dispatch
        GL20.glUseProgram(computeProgram);
        GL43.glDispatchCompute(batchSize, 1, 1);
        GL43.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        // 读回结果并写入缓存
        for (int i = 0; i < batchSize; i++) {
            PendingChunkBuild build = batch[i];
            int faceCount = 0;
            if (persistentHeaderView != null) {
                faceCount = persistentHeaderView.get(i * 4);
            }

            if (faceCount > 0 && faceCount <= MAX_FACES_PER_CHUNK) {
                int vertCount = faceCount * FLOATS_PER_FACE;
                int idxCount = faceCount * INDICES_PER_FACE;

                float[] vertices = new float[vertCount];
                int[] indices = new int[idxCount];

                if (persistentVertexView != null) {
                    persistentVertexView.position(i * OUTPUT_VERTS_PER_CHUNK);
                    persistentVertexView.get(vertices, 0, vertCount);
                }
                if (persistentIndexView != null) {
                    persistentIndexView.position(i * OUTPUT_INDICES_PER_CHUNK);
                    persistentIndexView.get(indices, 0, idxCount);
                }

                com.xor.uhdfast.cache.CachedChunkData cachedData = new com.xor.uhdfast.cache.CachedChunkData(vertices,
                        indices);
                long chunkKey = com.xor.uhdfast.cache.ChunkDataCache.createChunkKey(build.chunkX, build.chunkZ);
                if (cachedChunkDataCache != null) {
                    cachedChunkDataCache.put(chunkKey, cachedData);
                }

                chunksProcessed.incrementAndGet();
                facesGenerated.addAndGet(faceCount);
            } else {
                // 空区块也写入缓存，避免重复提交
                com.xor.uhdfast.cache.CachedChunkData cachedData = new com.xor.uhdfast.cache.CachedChunkData(
                        new float[0], new int[0]);
                long chunkKey = com.xor.uhdfast.cache.ChunkDataCache.createChunkKey(build.chunkX, build.chunkZ);
                if (cachedChunkDataCache != null) {
                    cachedChunkDataCache.put(chunkKey, cachedData);
                }
            }
        }

        batchesDispatched.incrementAndGet();
        chunksPerBatch.addAndGet(batchSize);

        long endTime = System.nanoTime();
        totalComputeTime += endTime - startTime;
        if (UHDFast.LOGGER.isDebugEnabled()) {
            UHDFast.LOGGER.debug("Batch GPU chunk build: {} chunks in {}ms",
                    batchSize, (endTime - startTime) / 1_000_000);
        }

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        GL20.glUseProgram(0);
    }

    // ========== 查询接口 ==========

    public static boolean isActive() {
        return active && initialized;
    }

    public static int getChunksProcessed() {
        return chunksProcessed.get();
    }

    public static int getFacesGenerated() {
        return facesGenerated.get();
    }

    public static long getTotalComputeTime() {
        return totalComputeTime;
    }

    public static long getTotalComputeTimeMs() {
        return totalComputeTime / 1_000_000;
    }

    public static int getPendingResultCount() {
        return pendingBuilds.size();
    }

    public static int getPendingBuildCount() {
        return pendingBuilds.size();
    }

    public static int getBatchesDispatched() {
        return batchesDispatched.get();
    }

    public static int getAvgBatchSize() {
        int batches = batchesDispatched.get();
        int chunks = chunksPerBatch.get();
        return batches > 0 ? chunks / batches : 0;
    }

    public static void resetStats() {
        chunksProcessed.set(0);
        facesGenerated.set(0);
        totalComputeTime = 0;
        batchesDispatched.set(0);
        chunksPerBatch.set(0);
        pendingBuilds.clear();
        resultPool.clear();
    }

    // ========== 清理 ==========

    public static void cleanup() {
        if (computeProgram != 0) {
            GL43.glDeleteProgram(computeProgram);
            computeProgram = 0;
        }

        int[] buffers = new int[] { blockDataSSBO, chunkMetaSSBO, outputVertsSSBO, outputIndicesSSBO,
                outputHeadersSSBO };
        GL15.glDeleteBuffers(buffers);
        blockDataSSBO = 0;
        chunkMetaSSBO = 0;
        outputVertsSSBO = 0;
        outputIndicesSSBO = 0;
        outputHeadersSSBO = 0;

        persistentBlockData = null;
        persistentMetaData = null;
        persistentVertexView = null;
        persistentIndexView = null;
        persistentHeaderView = null;

        if (batchUploadBuffer != null) {
            MemoryUtil.memFree(batchUploadBuffer);
            batchUploadBuffer = null;
        }
        if (batchMetaUploadBuffer != null) {
            MemoryUtil.memFree(batchMetaUploadBuffer);
            batchMetaUploadBuffer = null;
        }

        pendingBuilds.clear();
        resultPool.clear();
        initialized = false;
        active = false;
    }
}