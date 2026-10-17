package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GPU实例化渲染器
 * 将实例化变换计算和渲染任务完全交给GPU，减少CPU-GPU数据传输
 */
public class XeInstancedRenderer {

    private static final String INSTANCED_TRANSFORM_SHADER = """
            #version 430 core

            layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

            // 输入：实例变换数据
            layout(std430, binding = 0) buffer InstanceData {
                mat4 transforms[];
            };

            // 输出：变换后的顶点数据
            layout(std430, binding = 1) buffer TransformedVertices {
                vec4 vertices[];
            };

            // 输入：基础网格顶点
            layout(std430, binding = 2) buffer BaseMesh {
                vec4 baseVertices[];
            };

            // 参数
            layout(std140, binding = 3) uniform Parameters {
                int instanceCount;
                int vertexCount;
                float scale;
                int padding;
            };

            void main() {
                uint instanceId = gl_GlobalInvocationID.x;
                uint vertexId = gl_GlobalInvocationID.y;

                if (int(instanceId) >= instanceCount || int(vertexId) >= vertexCount) return;

                // GPU执行矩阵变换
                mat4 transform = transforms[instanceId];
                vec4 baseVertex = baseVertices[vertexId];

                // 应用变换
                vec4 transformed = transform * baseVertex;
                transformed.xyz *= scale;

                // 写入结果
                uint outputIdx = instanceId * uint(vertexCount) + vertexId;
                vertices[outputIdx] = transformed;
            }
            """;

    private static final String INSTANCED_RENDER_SHADER = """
            #version 330 core

            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec2 aTexCoord;

            // 实例属性
            layout(location = 3) in vec3 instancePos;
            layout(location = 4) in vec3 instanceColor;
            layout(location = 5) in float instanceScale;

            uniform mat4 viewProjection;
            uniform mat4 model;

            out vec3 FragPos;
            out vec3 Normal;
            out vec2 TexCoord;
            out vec3 InstanceColor;

            void main() {
                // GPU执行实例化变换
                vec3 worldPos = aPos * instanceScale + instancePos;
                FragPos = vec3(model * vec4(worldPos, 1.0));
                Normal = mat3(transpose(inverse(model))) * aNormal;
                TexCoord = aTexCoord;
                InstanceColor = instanceColor;

                gl_Position = viewProjection * vec4(FragPos, 1.0);
            }
            """;

    private static final String INSTANCED_FRAGMENT_SHADER = """
            #version 330 core

            in vec3 FragPos;
            in vec3 Normal;
            in vec2 TexCoord;
            in vec3 InstanceColor;

            out vec4 FragColor;

            uniform sampler2D textureDiffuse;
            uniform vec3 lightDir;
            uniform vec3 lightColor;
            uniform vec3 ambientColor;
            uniform int useInstanceColor;

            void main() {
                // 基础光照计算
                vec3 normal = normalize(Normal);
                float diff = max(dot(normal, normalize(lightDir)), 0.0);
                vec3 diffuse = diff * lightColor;

                vec3 baseColor;
                if (useInstanceColor == 1) {
                    baseColor = InstanceColor;
                } else {
                    baseColor = texture(textureDiffuse, TexCoord).rgb;
                }

                vec3 result = (ambientColor + diffuse) * baseColor;
                FragColor = vec4(result, 1.0);
            }
            """;

    private static int transformProgram = 0;
    private static int renderProgram = 0;
    private static GPUResourcePool.GPUResource instanceDataSSBO = null;
    private static GPUResourcePool.GPUResource transformedVerticesSSBO = null;
    private static GPUResourcePool.GPUResource baseMeshSSBO = null;
    private static GPUResourcePool.GPUResource paramBuffer = null;

    private static int instanceVBO = 0;
    private static int instanceColorVBO = 0;
    private static int instanceScaleVBO = 0;

    private static boolean initialized = false;
    private static boolean active = false;

    private static final int MAX_INSTANCES = 32768;
    private static final int MAX_VERTICES = 65536;

    private static final AtomicInteger instancesRendered = new AtomicInteger(0);
    private static final AtomicInteger drawCalls = new AtomicInteger(0);
    private static final AtomicInteger gpuTransforms = new AtomicInteger(0);
    private static long totalGPUTimeNs = 0;

    private static FloatBuffer instanceDataBuffer;
    private static FloatBuffer vertexDataBuffer;

    public static void initialize() {
        if (initialized) {
            return;
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            UHDFast.LOGGER.info("Xe Instanced Renderer disabled - not Xe architecture");
            return;
        }

        try {
            compileShaders();
            createBuffers();

            instanceDataBuffer = MemoryUtil.memAllocFloat(MAX_INSTANCES * 16); // mat4 per instance
            vertexDataBuffer = MemoryUtil.memAllocFloat(MAX_VERTICES * 3);

            initialized = true;
            active = true;

            UHDFast.LOGGER.info("Xe GPU Instanced Renderer initialized");
            UHDFast.LOGGER.info("  Max instances: {}", MAX_INSTANCES);
            UHDFast.LOGGER.info("  Max vertices: {}", MAX_VERTICES);
            UHDFast.LOGGER.info("  GPU Transform Shader: enabled");

        } catch (Exception e) {
            UHDFast.LOGGER.error("Failed to initialize Xe Instanced Renderer: {}", e.getMessage());
            active = false;
        }
    }

    private static void compileShaders() {
        // 编译变换计算着色器
        int transformShader = GL43.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL43.glShaderSource(transformShader, INSTANCED_TRANSFORM_SHADER);
        GL43.glCompileShader(transformShader);

        if (GL43.glGetShaderi(transformShader, GL43.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String error = GL43.glGetShaderInfoLog(transformShader);
            GL43.glDeleteShader(transformShader);
            throw new RuntimeException("Transform compute shader compilation failed: " + error);
        }

        transformProgram = GL43.glCreateProgram();
        GL43.glAttachShader(transformProgram, transformShader);
        GL43.glLinkProgram(transformProgram);

        if (GL43.glGetProgrami(transformProgram, GL43.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String error = GL43.glGetProgramInfoLog(transformProgram);
            GL43.glDeleteProgram(transformProgram);
            GL43.glDeleteShader(transformShader);
            throw new RuntimeException("Transform program linking failed: " + error);
        }

        GL43.glDeleteShader(transformShader);

        // 编译渲染着色器
        int vertexShader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(vertexShader, INSTANCED_RENDER_SHADER);
        GL20.glCompileShader(vertexShader);

        if (GL20.glGetShaderi(vertexShader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String error = GL20.glGetShaderInfoLog(vertexShader);
            GL20.glDeleteShader(vertexShader);
            throw new RuntimeException("Instance vertex shader compilation failed: " + error);
        }

        int fragmentShader = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(fragmentShader, INSTANCED_FRAGMENT_SHADER);
        GL20.glCompileShader(fragmentShader);

        if (GL20.glGetShaderi(fragmentShader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String error = GL20.glGetShaderInfoLog(fragmentShader);
            GL20.glDeleteShader(fragmentShader);
            GL20.glDeleteShader(vertexShader);
            throw new RuntimeException("Instance fragment shader compilation failed: " + error);
        }

        renderProgram = GL20.glCreateProgram();
        GL20.glAttachShader(renderProgram, vertexShader);
        GL20.glAttachShader(renderProgram, fragmentShader);
        GL20.glLinkProgram(renderProgram);

        if (GL20.glGetProgrami(renderProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String error = GL20.glGetProgramInfoLog(renderProgram);
            GL20.glDeleteProgram(renderProgram);
            GL20.glDeleteShader(vertexShader);
            GL20.glDeleteShader(fragmentShader);
            throw new RuntimeException("Instance program linking failed: " + error);
        }

        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
    }

    private static void createBuffers() {
        // 从资源池获取SSBO和UBO资源
        instanceDataSSBO = GPUResourcePool.acquireSSBO(MAX_INSTANCES * 64);
        transformedVerticesSSBO = GPUResourcePool.acquireSSBO((long) MAX_INSTANCES * MAX_VERTICES * 16);
        baseMeshSSBO = GPUResourcePool.acquireSSBO(MAX_VERTICES * 12);
        paramBuffer = GPUResourcePool.acquireUBO(16);

        // 实例属性VBO
        int[] vbos = new int[3];
        GL15.glGenBuffers(vbos);
        instanceVBO = vbos[0];
        instanceColorVBO = vbos[1];
        instanceScaleVBO = vbos[2];

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVBO);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, MAX_INSTANCES * 12, GL15.GL_DYNAMIC_DRAW);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceColorVBO);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, MAX_INSTANCES * 12, GL15.GL_DYNAMIC_DRAW);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceScaleVBO);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, MAX_INSTANCES * 4, GL15.GL_DYNAMIC_DRAW);

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    /**
     * 执行GPU实例化渲染
     */
    public static void renderInstances(float[] instanceData, int instanceCount, float[] baseVertices,
            int vertexCount, float[] viewProjection, float[] modelMatrix) {
        if (!active || !initialized || instanceCount <= 0) {
            return;
        }

        long startTime = System.nanoTime();

        try {
            // 开始 GPU 命令批处理
            GPUCommandBatcher.beginBatch();

            // 上传实例数据
            instanceDataBuffer.clear();
            instanceDataBuffer.put(instanceData, 0, Math.min(instanceCount * 16, MAX_INSTANCES * 16));
            instanceDataBuffer.flip();

            GPUCommandBatcher.bufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, instanceDataSSBO.id, 0,
                    org.lwjgl.system.MemoryUtil.memByteBuffer(instanceDataBuffer));

            // 上传基础网格
            vertexDataBuffer.clear();
            vertexDataBuffer.put(baseVertices, 0, Math.min(vertexCount * 3, MAX_VERTICES * 3));
            vertexDataBuffer.flip();

            GPUCommandBatcher.bufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, baseMeshSSBO.id, 0, vertexDataBuffer);

            // 上传参数
            FloatBuffer paramBuf = MemoryUtil.memAllocFloat(4);
            paramBuf.put(instanceCount);
            paramBuf.put(vertexCount);
            paramBuf.put(1.0f); // scale
            paramBuf.put(0); // padding
            paramBuf.flip();

            GPUCommandBatcher.bufferSubData(GL31.GL_UNIFORM_BUFFER, paramBuffer.id, 0, paramBuf);
            MemoryUtil.memFree(paramBuf);

            // 绑定缓冲并执行变换
            GPUCommandBatcher.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, instanceDataSSBO.id);
            GPUCommandBatcher.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, transformedVerticesSSBO.id);
            GPUCommandBatcher.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, baseMeshSSBO.id);
            GPUCommandBatcher.bindBufferBase(GL31.GL_UNIFORM_BUFFER, 3, paramBuffer.id);

            GPUCommandBatcher.useProgram(transformProgram);
            int dispatchX = (instanceCount + 63) / 64;
            int dispatchY = (vertexCount + 63) / 64;
            GPUCommandBatcher.dispatchCompute(dispatchX, dispatchY, 1);
            GPUCommandBatcher.memoryBarrier(MemoryBarrierOptimizer.BARRIER_SSBO);

            // 提交批处理
            GPUCommandBatcher.endBatch();

            gpuTransforms.addAndGet(instanceCount * vertexCount);

            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
            GL20.glUseProgram(0);

            instancesRendered.addAndGet(instanceCount);
            drawCalls.incrementAndGet();

            long endTime = System.nanoTime();
            totalGPUTimeNs += (endTime - startTime);

        } catch (Exception e) {
            UHDFast.LOGGER.warn("GPU instanced rendering failed: {}", e.getMessage());
        }
    }

    /**
     * 使用实例化属性直接渲染
     * 优化：使用缓存 FloatBuffer 避免每帧 MemoryUtil.memAllocFloat
     */
    public static void renderWithInstanceAttributes(int vaoId, int vertexCount, int instanceCount,
            float[] instancePositions, float[] instanceColors, float[] instanceScales,
            float[] viewProjection, float[] modelMatrix) {
        if (!active || !initialized || instanceCount <= 0) {
            return;
        }

        try {
            // 开始 GPU 命令批处理
            GPUCommandBatcher.beginBatch();

            // 使用缓存 FloatBuffer 避免每帧分配
            ensureBufferCapacity(instanceCount);

            // 上传实例位置
            cachedPosBuffer.clear();
            cachedPosBuffer.put(instancePositions, 0, instanceCount * 3);
            cachedPosBuffer.flip();
            GPUCommandBatcher.bufferSubData(GL15.GL_ARRAY_BUFFER, instanceVBO, 0, cachedPosBuffer);

            // 上传实例颜色
            cachedColorBuffer.clear();
            cachedColorBuffer.put(instanceColors, 0, instanceCount * 3);
            cachedColorBuffer.flip();
            GPUCommandBatcher.bufferSubData(GL15.GL_ARRAY_BUFFER, instanceColorVBO, 0, cachedColorBuffer);

            // 上传实例缩放
            cachedScaleBuffer.clear();
            cachedScaleBuffer.put(instanceScales, 0, instanceCount);
            cachedScaleBuffer.flip();
            GPUCommandBatcher.bufferSubData(GL15.GL_ARRAY_BUFFER, instanceScaleVBO, 0, cachedScaleBuffer);

            // 绑定VAO和着色器
            GL30.glBindVertexArray(vaoId);
            GPUCommandBatcher.useProgram(renderProgram);

            // 设置实例属性指针
            GL20.glEnableVertexAttribArray(3);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVBO);
            GL20.glVertexAttribPointer(3, 3, GL11.GL_FLOAT, false, 0, 0);
            GL33.glVertexAttribDivisor(3, 1);

            GL20.glEnableVertexAttribArray(4);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceColorVBO);
            GL20.glVertexAttribPointer(4, 3, GL11.GL_FLOAT, false, 0, 0);
            GL33.glVertexAttribDivisor(4, 1);

            GL20.glEnableVertexAttribArray(5);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceScaleVBO);
            GL20.glVertexAttribPointer(5, 1, GL11.GL_FLOAT, false, 0, 0);
            GL33.glVertexAttribDivisor(5, 1);

            // 设置uniform - 使用缓存 FloatBuffer
            int vpLoc = GL20.glGetUniformLocation(renderProgram, "viewProjection");
            int modelLoc = GL20.glGetUniformLocation(renderProgram, "model");
            int useInstanceColorLoc = GL20.glGetUniformLocation(renderProgram, "useInstanceColor");

            cachedUniformBuffer.clear();
            cachedUniformBuffer.put(viewProjection).flip();
            GL21.glUniformMatrix4fv(vpLoc, false, cachedUniformBuffer);

            cachedUniformBuffer.clear();
            cachedUniformBuffer.put(modelMatrix).flip();
            GL21.glUniformMatrix4fv(modelLoc, false, cachedUniformBuffer);

            GL20.glUniform1i(useInstanceColorLoc, 1);

            // 执行实例化绘制
            GPUCommandBatcher.drawArraysInstanced(GL11.GL_TRIANGLES, 0, vertexCount, instanceCount);

            // 提交批处理
            GPUCommandBatcher.endBatch();

            // 清理
            GL33.glVertexAttribDivisor(3, 0);
            GL33.glVertexAttribDivisor(4, 0);
            GL33.glVertexAttribDivisor(5, 0);
            GL20.glDisableVertexAttribArray(3);
            GL20.glDisableVertexAttribArray(4);
            GL20.glDisableVertexAttribArray(5);

            GL30.glBindVertexArray(0);
            GL20.glUseProgram(0);

            instancesRendered.addAndGet(instanceCount);
            drawCalls.incrementAndGet();

        } catch (Exception e) {
            UHDFast.LOGGER.warn("GPU instanced attribute rendering failed: {}", e.getMessage());
        }
    }

    /** 缓存 FloatBuffer，避免每帧分配 */
    private static FloatBuffer cachedPosBuffer;
    private static FloatBuffer cachedColorBuffer;
    private static FloatBuffer cachedScaleBuffer;
    private static FloatBuffer cachedUniformBuffer;
    private static int cachedBufferCapacity = 0;

    private static void ensureBufferCapacity(int instanceCount) {
        int needed = Math.max(instanceCount * 3, 16);
        if (cachedBufferCapacity < needed) {
            int newCapacity = Math.max(needed, 1024);
            // 释放旧缓冲区
            if (cachedPosBuffer != null) MemoryUtil.memFree(cachedPosBuffer);
            if (cachedColorBuffer != null) MemoryUtil.memFree(cachedColorBuffer);
            if (cachedScaleBuffer != null) MemoryUtil.memFree(cachedScaleBuffer);
            if (cachedUniformBuffer != null) MemoryUtil.memFree(cachedUniformBuffer);
            // 分配新缓冲区
            cachedPosBuffer = MemoryUtil.memAllocFloat(newCapacity);
            cachedColorBuffer = MemoryUtil.memAllocFloat(newCapacity);
            cachedScaleBuffer = MemoryUtil.memAllocFloat(instanceCount);
            cachedUniformBuffer = MemoryUtil.memAllocFloat(16);
            cachedBufferCapacity = newCapacity;
        }
    }

    public static boolean isActive() {
        return active && initialized;
    }

    public static int getInstancesRendered() {
        return instancesRendered.get();
    }

    public static int getDrawCalls() {
        return drawCalls.get();
    }

    public static int getGPUTransforms() {
        return gpuTransforms.get();
    }

    public static long getTotalGPUTimeMs() {
        return totalGPUTimeNs / 1_000_000;
    }

    public static void resetStats() {
        instancesRendered.set(0);
        drawCalls.set(0);
        gpuTransforms.set(0);
        totalGPUTimeNs = 0;
    }

    public static void destroy() {
        if (transformProgram != 0) {
            GL43.glDeleteProgram(transformProgram);
            transformProgram = 0;
        }
        if (renderProgram != 0) {
            GL20.glDeleteProgram(renderProgram);
            renderProgram = 0;
        }

        // 释放SSBO/UBO资源回GPUResourcePool
        List<GPUResourcePool.GPUResource> pooledResources = new ArrayList<>();
        if (instanceDataSSBO != null)
            pooledResources.add(instanceDataSSBO);
        if (transformedVerticesSSBO != null)
            pooledResources.add(transformedVerticesSSBO);
        if (baseMeshSSBO != null)
            pooledResources.add(baseMeshSSBO);
        if (paramBuffer != null)
            pooledResources.add(paramBuffer);
        GPUResourcePool.releaseResources(pooledResources);
        instanceDataSSBO = null;
        transformedVerticesSSBO = null;
        baseMeshSSBO = null;
        paramBuffer = null;

        // VBO仍使用直接删除
        int[] vbos = new int[] { instanceVBO, instanceColorVBO, instanceScaleVBO };
        GL15.glDeleteBuffers(vbos);
        instanceVBO = 0;
        instanceColorVBO = 0;
        instanceScaleVBO = 0;

        if (instanceDataBuffer != null) {
            MemoryUtil.memFree(instanceDataBuffer);
            instanceDataBuffer = null;
        }
        if (vertexDataBuffer != null) {
            MemoryUtil.memFree(vertexDataBuffer);
            vertexDataBuffer = null;
        }
        // 释放缓存 FloatBuffer
        if (cachedPosBuffer != null) { MemoryUtil.memFree(cachedPosBuffer); cachedPosBuffer = null; }
        if (cachedColorBuffer != null) { MemoryUtil.memFree(cachedColorBuffer); cachedColorBuffer = null; }
        if (cachedScaleBuffer != null) { MemoryUtil.memFree(cachedScaleBuffer); cachedScaleBuffer = null; }
        if (cachedUniformBuffer != null) { MemoryUtil.memFree(cachedUniformBuffer); cachedUniformBuffer = null; }
        cachedBufferCapacity = 0;
        initialized = false;
        active = false;
    }
}
