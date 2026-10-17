package com.xor.uhdfast.shaderpack;

import com.mojang.blaze3d.systems.RenderSystem;
import com.xor.uhdfast.UHDFast;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 光影程序 uniform 上传管理类。
 *
 * <p>
 * 每个 {@link ProgramUniforms} 实例关联一个已链接的 OpenGL 程序，负责缓存 uniform
 * 位置并上传 Minecraft 标准光影 uniform（如 {@code gbufferModelView}、
 * {@code cameraPosition} 等）。
 * </p>
 *
 * <p>
 * 构造时会预缓存常见 Minecraft 光影 uniform 的位置，避免每帧重复查询。
 * 后续调用 {@link #getUniformLocation(String)} 时若缓存未命中，会回退到 GL 查询并
 * 缓存结果（包括 -1，表示该 uniform 不存在或被优化掉）。
 * </p>
 *
 * <p>
 * <b>线程安全性：</b>非线程安全，所有涉及 GL 操作的方法必须在渲染线程调用，
 * 通过 {@link RenderSystem#isOnRenderThread()} 校验。
 * </p>
 */
public class ProgramUniforms {

    /** 关联的 OpenGL 程序 ID */
    private final int programId;

    /** uniform 名称到位置的缓存（值为 -1 表示该 uniform 不存在） */
    private final Map<String, Integer> uniformLocations;

    /** 帧计数器，每次 {@link #updateCommonUniforms} 调用时递增 */
    private long frameCounter;

    /**
     * 构造 uniform 管理器。
     *
     * <p>
     * 构造后会立即预缓存常见 uniform 位置。调用前需确保
     * 程序已成功链接，且当前处于渲染线程。
     * </p>
     *
     * @param programId 已链接的 OpenGL 程序 ID
     * @throws RuntimeException 如果不在渲染线程调用
     */
    public ProgramUniforms(int programId) {
        checkRenderThread();
        this.programId = programId;
        this.uniformLocations = new HashMap<>();
        this.frameCounter = 0;
        queryActiveUniforms();
    }

    /**
     * 预缓存常见 Minecraft 光影 uniform 的位置。
     *
     * <p>
     * 不使用 {@code glGetActiveUniform} 遍历（不同 LWJGL 版本签名差异较大），
     * 改为对一组已知的 Minecraft 标准光影 uniform 名称调用 {@code glGetUniformLocation}
     * 进行预查询。未找到的 uniform 会缓存为 -1，避免每帧重复查询。
     * </p>
     */
    private void queryActiveUniforms() {
        // Minecraft / Iris / OptiFine 标准光影 uniform 名称列表
        String[] knownUniforms = {
                // 矩阵
                "gbufferModelView", "gbufferProjection",
                "gbufferModelViewInverse", "gbufferProjectionInverse",
                "gbufferPreviousModelView", "gbufferPreviousProjection",
                // 相机
                "cameraPosition", "cameraPositionInverse",
                "previousCameraPosition",
                // 时间
                "frameTimeCounter", "frameCounter",
                "elapsedTime", "systemTime",
                // 视口
                "viewWidth", "viewHeight", "aspectRatio",
                "near", "far",
                // 阴影
                "shadowMap", "shadowMapResolution",
                "sunPosition", "moonPosition",
                "upPosition", "shadowLightPosition",
                // 雾
                "fogColor", "fogMode", "fogDensity", "fogStart", "fogEnd",
                // 天空
                "skyColor", "rainStrength", "wetness",
                // 实体
                "entityColor", "entityTranslate",
                // 纹理尺寸
                "textureSize", "atlasSize",
                // 通用
                "modelId", "renderStage",
                // 程序切换
                "currentColor", "currentColorInverse"
        };
        for (String name : knownUniforms) {
            int location = GL20.glGetUniformLocation(programId, name);
            uniformLocations.put(name, location);
        }
        UHDFast.LOGGER.debug("程序 {} 预缓存了 {} 个已知 uniform 位置", programId, uniformLocations.size());
    }

    /**
     * 获取 uniform 位置（带缓存）。
     *
     * <p>
     * 优先查缓存；缓存未命中时回退到 {@code glGetUniformLocation} 查询并缓存结果。
     * 未找到的 uniform 会缓存为 -1，避免重复查询。
     * </p>
     *
     * @param name uniform 名称
     * @return uniform 位置；不存在返回 -1
     */
    public int getUniformLocation(String name) {
        if (name == null) {
            return -1;
        }
        if (uniformLocations.containsKey(name)) {
            return uniformLocations.get(name);
        }
        checkRenderThread();
        int location = GL20.glGetUniformLocation(programId, name);
        uniformLocations.put(name, location);
        return location;
    }

    /**
     * 上传 Minecraft 标准光影 uniform。
     * 
     * 
     * <ul>
     * <li>{@code gbufferModelView} / {@code gbufferProjection} (mat4)</li>
     * <li>{@code gbufferModelViewInverse} / {@code gbufferProjectionInverse}
     * (mat4)</li>
     * <li>{@code cameraPosition} (vec3) / {@code cameraPositionInverse}
     * (vec3，取反)</li>
     * <li>{@code frameTimeCounter} (float) / {@code frameCounter} (int)</li>
     * <li>{@code aspectRatio} (float) / {@code viewWidth} (int) /
     * {@code viewHeight} (int)</li>
     * </ul>
     *
     * <p>
     * 调用前需确保当前程序已通过 {@code glUseProgram} 激活。逆矩阵在原始矩阵的副本上
     * 计算，不会修改传入的矩阵对象。
     * </p>
     *
     * @param camX             相机世界坐标 X
     * @param camY             相机世界坐标 Y
     * @param camZ             相机世界坐标 Z
     * @param projection       投影矩阵
     * @param modelView        模型视图矩阵
     * @param frameTimeCounter 帧时间计数器（秒）
     * @param tickCount        游戏 tick 计数（保留参数，当前使用内部帧计数器）
     * @throws RuntimeException 如果不在渲染线程调用
     */
    public void updateCommonUniforms(double camX, double camY, double camZ,
            Matrix4f projection, Matrix4f modelView,
            float frameTimeCounter, int tickCount) {
        checkRenderThread();
        frameCounter++;

        // 上传模型视图与投影矩阵
        uploadMat4("gbufferModelView", modelView);
        uploadMat4("gbufferProjection", projection);

        // 计算逆矩阵（在副本上操作，不修改原始矩阵）
        Matrix4f modelViewInverse = new Matrix4f(modelView).invert();
        Matrix4f projectionInverse = new Matrix4f(projection).invert();
        uploadMat4("gbufferModelViewInverse", modelViewInverse);
        uploadMat4("gbufferProjectionInverse", projectionInverse);

        // 上传相机位置及其取反
        uploadVec3("cameraPosition", (float) camX, (float) camY, (float) camZ);
        uploadVec3("cameraPositionInverse", (float) -camX, (float) -camY, (float) -camZ);

        // 上传时间与帧计数
        uploadFloat("frameTimeCounter", frameTimeCounter);
        uploadInt("frameCounter", (int) frameCounter);

        // 上传视口尺寸与宽高比
        Minecraft mc = Minecraft.getInstance();
        int viewWidth = mc.getWindow().getWidth();
        int viewHeight = mc.getWindow().getHeight();
        float aspectRatio = viewHeight > 0 ? (float) viewWidth / (float) viewHeight : 1.0f;
        uploadInt("viewWidth", viewWidth);
        uploadInt("viewHeight", viewHeight);
        uploadFloat("aspectRatio", aspectRatio);
    }

    /**
     * 上传单个 int uniform。
     *
     * <p>
     * 若指定名称的 uniform 不存在（位置为 -1），则跳过上传。
     * </p>
     *
     * @param name  uniform 名称
     * @param value 整数值
     */
    public void uploadInt(String name, int value) {
        int location = getUniformLocation(name);
        if (location < 0) {
            return;
        }
        checkRenderThread();
        GL20.glUniform1i(location, value);
    }

    /**
     * 上传单个 float uniform。
     *
     * <p>
     * 若指定名称的 uniform 不存在（位置为 -1），则跳过上传。
     * </p>
     *
     * @param name  uniform 名称
     * @param value 浮点数值
     */
    public void uploadFloat(String name, float value) {
        int location = getUniformLocation(name);
        if (location < 0) {
            return;
        }
        checkRenderThread();
        GL20.glUniform1f(location, value);
    }

    /**
     * 上传 vec3 uniform。
     *
     * <p>
     * 若指定名称的 uniform 不存在（位置为 -1），则跳过上传。
     * </p>
     *
     * @param name uniform 名称
     * @param x    X 分量
     * @param y    Y 分量
     * @param z    Z 分量
     */
    public void uploadVec3(String name, float x, float y, float z) {
        int location = getUniformLocation(name);
        if (location < 0) {
            return;
        }
        checkRenderThread();
        GL20.glUniform3f(location, x, y, z);
    }

    /**
     * Upload a mat4 uniform.
     *
     * <p>
     * Uses {@link MemoryStack} to allocate a 16-element {@link FloatBuffer},
     * writes the matrix data into it, and uploads via {@code glUniformMatrix4fv}
     * (no transpose). If the named uniform does not exist (location is -1),
     * the upload is skipped.
     * </p>
     *
     * @param name   uniform name
     * @param matrix 4x4 matrix (will not be modified)
     */
    public void uploadMat4(String name, Matrix4f matrix) {
        int location = getUniformLocation(name);
        if (location < 0) {
            return;
        }
        checkRenderThread();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(16);
            matrix.get(buffer);
            GL20.glUniformMatrix4fv(location, false, buffer);
        }
    }

    /** @return 关联的 OpenGL 程序 ID */
    public int getProgramId() {
        return programId;
    }

    /** @return 当前帧计数器值 */
    public long getFrameCounter() {
        return frameCounter;
    }

    /**
     * 校验当前是否在渲染线程，否则抛出异常。
     */
    private void checkRenderThread() {
        if (!RenderSystem.isOnRenderThread()) {
            throw new RuntimeException("uniform 操作必须在渲染线程执行");
        }
    }
}
