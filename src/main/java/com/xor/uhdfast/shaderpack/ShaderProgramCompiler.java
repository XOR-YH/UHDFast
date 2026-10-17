package com.xor.uhdfast.shaderpack;

import com.mojang.blaze3d.systems.RenderSystem;
import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.util.HashMap;
import java.util.Map;

/**
 * 光影 GLSL 程序编译器。
 *
 * <p>
 * 提供从 {@link ShaderPack} 源码到 OpenGL 程序对象的完整编译流程，包括：
 * </p>
 * <ul>
 * <li>单个着色器编译（{@link #compileShader}）</li>
 * <li>程序链接（{@link #linkProgram}）</li>
 * <li>完整编译流程（{@link #compileProgram}）—— 获取源码、注入宏、编译、链接</li>
 * <li>程序删除（{@link #deleteProgram}）</li>
 * </ul>
 *
 * <p>
 * 编译失败时，{@link #compileShader} 和 {@link #linkProgram} 会记录详细错误日志
 * （含 GL info log）并抛出 {@link RuntimeException}；而 {@link #compileProgram}
 * 会捕获异常、记录日志并返回 {@code null}，由调用方决定如何处理。
 * </p>
 *
 * <p>
 * <b>线程安全性：</b>所有方法为静态方法，必须在渲染线程调用，
 * 通过 {@link RenderSystem#isOnRenderThread()} 校验。
 * </p>
 */
public final class ShaderProgramCompiler {

    /** GLSL 版本声明 */
    public static final String GLSL_VERSION = "#version 330 core";

    /** 顶点着色器通用宏定义字符串 */
    public static final String COMMON_VERTEX_MACROS = "#define MC_VERTEX_SHADER\n" +
            "#define MC_GL_VERSION_GLSL 330\n" +
            "#define MC_RENDER_QUALITY 2\n";

    /** 片段着色器通用宏定义字符串 */
    public static final String COMMON_FRAGMENT_MACROS = "#define MC_FRAGMENT_SHADER\n" +
            "#define MC_GL_VERSION_GLSL 330\n" +
            "#define MC_RENDER_QUALITY 2\n";

    /** Info log 最大读取长度 */
    private static final int MAX_INFO_LOG_LENGTH = 32768;

    /** 私有构造器，禁止实例化 */
    private ShaderProgramCompiler() {
    }

    /**
     * 编译单个着色器。
     *
     * <p>
     * 流程：创建 shader → 设置源码 → 编译 → 检查 {@code GL_COMPILE_STATUS}。
     * 编译失败时记录包含 GLSL info log 的错误日志，删除 shader 并抛出异常。
     * </p>
     *
     * @param shaderType 着色器类型（{@code GL20.GL_VERTEX_SHADER} 或
     *                   {@code GL20.GL_FRAGMENT_SHADER}）
     * @param source     GLSL 源码
     * @param name       着色器名称（仅用于日志和错误信息）
     * @return 编译后的 shader ID
     * @throws RuntimeException 如果不在渲染线程调用，或编译失败
     */
    public static int compileShader(int shaderType, String source, String name) {
        checkRenderThread();
        if (source == null || source.isEmpty()) {
            throw new RuntimeException("着色器源码为空: " + name);
        }

        int shaderId = GL20.glCreateShader(shaderType);
        if (shaderId == 0) {
            throw new RuntimeException("无法创建 shader 对象: " + name);
        }

        GL20.glShaderSource(shaderId, source);
        GL20.glCompileShader(shaderId);

        int status = GL20.glGetShaderi(shaderId, GL20.GL_COMPILE_STATUS);
        if (status == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shaderId, MAX_INFO_LOG_LENGTH);
            UHDFast.LOGGER.error("着色器编译失败 [{}]:\n{}", name, log);
            GL20.glDeleteShader(shaderId);
            throw new RuntimeException("着色器编译失败: " + name + "\n" + log);
        }

        UHDFast.LOGGER.debug("着色器编译成功: {}", name);
        return shaderId;
    }

    /**
     * 链接程序。
     *
     * <p>
     * 流程：创建 program → attach 顶点和片段 shader → link → 检查
     * {@code GL_LINK_STATUS}。链接失败时记录日志、删除程序和 shader 并抛出异常。
     * 链接成功后会删除已链接的 shader（程序已保留其代码）。
     * </p>
     *
     * @param vertexShader   已编译的顶点着色器 ID
     * @param fragmentShader 已编译的片段着色器 ID
     * @param name           程序名称（仅用于日志和错误信息）
     * @return 链接后的 program ID
     * @throws RuntimeException 如果不在渲染线程调用，或链接失败
     */
    public static int linkProgram(int vertexShader, int fragmentShader, String name) {
        checkRenderThread();

        int programId = GL20.glCreateProgram();
        if (programId == 0) {
            throw new RuntimeException("无法创建 program 对象: " + name);
        }

        GL20.glAttachShader(programId, vertexShader);
        GL20.glAttachShader(programId, fragmentShader);
        GL20.glLinkProgram(programId);

        int status = GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS);
        if (status == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(programId, MAX_INFO_LOG_LENGTH);
            UHDFast.LOGGER.error("程序链接失败 [{}]:\n{}", name, log);
            GL20.glDetachShader(programId, vertexShader);
            GL20.glDetachShader(programId, fragmentShader);
            GL20.glDeleteProgram(programId);
            GL20.glDeleteShader(vertexShader);
            GL20.glDeleteShader(fragmentShader);
            throw new RuntimeException("程序链接失败: " + name + "\n" + log);
        }

        // 链接成功后删除 shader（程序已保留代码）
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);

        UHDFast.LOGGER.debug("程序链接成功: {} (id={})", name, programId);
        return programId;
    }

    /**
     * 完整编译流程：从光影包获取源码、注入宏、编译并链接。
     *
     * <p>
     * 流程：
     * </p>
     * <ol>
     * <li>从 {@link ShaderPack} 获取顶点和片段着色器源码；任一为 {@code null} 则返回 {@code null}</li>
     * <li>调用 {@link ShaderSourceLoader#injectMacros} 注入宏定义</li>
     * <li>确保源码开头有 {@code #version 330 core} 声明</li>
     * <li>编译顶点和片段着色器</li>
     * <li>链接程序</li>
     * <li>创建并返回 {@link ProgramUniforms}</li>
     * </ol>
     *
     * <p>
     * 任何步骤失败都会记录日志并返回 {@code null}，不抛出异常。
     * </p>
     *
     * @param pack        光影包
     * @param programName 程序名（如 "gbuffers_terrain"）
     * @param macros      宏定义映射（可为 {@code null}）
     * @return 编译成功返回 {@link ProgramUniforms}；程序未定义或编译失败返回 {@code null}
     */
    public static ProgramUniforms compileProgram(ShaderPack pack, String programName,
            Map<String, String> macros) {
        if (pack == null || programName == null) {
            UHDFast.LOGGER.warn("无法编译程序: pack 或 programName 为 null");
            return null;
        }
        checkRenderThread();

        // 获取着色器源码
        String vertexSource = pack.getSource(programName, true);
        String fragmentSource = pack.getSource(programName, false);
        if (vertexSource == null || fragmentSource == null) {
            UHDFast.LOGGER.debug("程序 '{}' 缺少着色器源码，跳过编译", programName);
            return null;
        }

        try {
            // 注入宏定义
            vertexSource = ShaderSourceLoader.injectMacros(vertexSource, macros);
            fragmentSource = ShaderSourceLoader.injectMacros(fragmentSource, macros);

            // 确保 #version 声明存在
            vertexSource = ensureVersion(vertexSource);
            fragmentSource = ensureVersion(fragmentSource);

            // 编译着色器
            int vertexShader = compileShader(GL20.GL_VERTEX_SHADER, vertexSource,
                    programName + ".vsh");
            int fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, fragmentSource,
                    programName + ".fsh");

            // 链接程序
            int programId = linkProgram(vertexShader, fragmentShader, programName);

            // 创建 uniform 管理器
            UHDFast.LOGGER.info("光影程序 '{}' 编译成功 (id={})", programName, programId);
            return new ProgramUniforms(programId);
        } catch (Exception e) {
            UHDFast.LOGGER.error("编译光影程序 '{}' 失败: {}", programName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 删除 OpenGL 程序。
     *
     * <p>
     * 若 programId 为 0 则跳过。重复调用是安全的。
     * </p>
     *
     * @param programId 程序 ID
     */
    public static void deleteProgram(int programId) {
        if (programId == 0) {
            return;
        }
        checkRenderThread();
        GL20.glDeleteProgram(programId);
    }

    /**
     * 返回默认宏定义映射。
     *
     * <p>
     * 包含以下常用宏：
     * </p>
     * <ul>
     * <li>{@code MC_GL_VERSION_GLSL} = 330</li>
     * <li>{@code MC_RENDER_QUALITY} = 2</li>
     * <li>{@code MC_SHADOW_QUALITY} = 2</li>
     * <li>{@code MC_NORMAL_MAP} = 1</li>
     * <li>{@code MC_SPECULAR_MAP} = 1</li>
     * </ul>
     *
     * @return 新的宏定义映射（可修改）
     */
    public static Map<String, String> getDefaultMacros() {
        Map<String, String> macros = new HashMap<>();
        macros.put("MC_GL_VERSION_GLSL", "330");
        macros.put("MC_RENDER_QUALITY", "2");
        macros.put("MC_SHADOW_QUALITY", "2");
        macros.put("MC_NORMAL_MAP", "1");
        macros.put("MC_SPECULAR_MAP", "1");
        macros.put("MC_HAND_DEPTH", "0.5");
        return macros;
    }

    /**
     * 确保源码开头包含 {@code #version 330 core} 声明。
     *
     * <p>
     * 若源码首行已以 {@code #version} 开头则原样返回；否则在开头插入
     * {@link #GLSL_VERSION}。
     * </p>
     *
     * @param source GLSL 源码
     * @return 处理后的源码
     */
    private static String ensureVersion(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        String trimmed = source.trim();
        if (trimmed.startsWith("#version")) {
            return source;
        }
        return GLSL_VERSION + "\n" + source;
    }

    /**
     * 校验当前是否在渲染线程，否则抛出异常。
     */
    private static void checkRenderThread() {
        if (!RenderSystem.isOnRenderThread()) {
            throw new RuntimeException("着色器编译操作必须在渲染线程执行");
        }
    }
}
