package com.xor.uhdfast.shaderpack;

import com.mojang.blaze3d.systems.RenderSystem;
import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.GL20;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 光影程序编译缓存。
 *
 * <p>
 * 缓存已编译的 OpenGL 程序及其 {@link ProgramUniforms} 管理器，避免每帧重新编译。
 * 同时记录编译失败程序的错误信息，避免重复尝试编译已知失败的程序。
 * </p>
 *
 * <p>
 * 缓存维护三组映射：
 * </p>
 * <ul>
 * <li>{@code programCache}：程序名 → program ID</li>
 * <li>{@code uniformsCache}：程序名 → {@link ProgramUniforms}</li>
 * <li>{@code compileErrors}：程序名 → 错误信息</li>
 * </ul>
 *
 * <p>
 * 调用 {@link #clear()} 或 {@link #removeProgram(String)} 会删除对应的 GL 程序对象，
 * 这些方法必须在渲染线程调用。
 * </p>
 *
 * <p>
 * <b>线程安全性：</b>非线程安全。涉及 GL 资源删除的方法（{@link #clear()}、
 * {@link #removeProgram(String)}）必须在渲染线程调用；纯缓存查询方法可在任意线程调用，
 * 但需注意与删除操作的同步。
 * </p>
 */
public class ShaderProgramCache {

    /** 程序名到 program ID 的映射 */
    private final Map<String, Integer> programCache;

    /** 程序名到 uniform 管理器的映射 */
    private final Map<String, ProgramUniforms> uniformsCache;

    /** 程序名到编译错误信息的映射 */
    private final Map<String, String> compileErrors;

    /**
     * 构造空的程序缓存。
     */
    public ShaderProgramCache() {
        this.programCache = new HashMap<>();
        this.uniformsCache = new HashMap<>();
        this.compileErrors = new HashMap<>();
    }

    /**
     * 获取缓存的程序 uniform 管理器。
     *
     * @param name 程序名
     * @return uniform 管理器；未缓存返回 {@code null}
     */
    public ProgramUniforms getProgram(String name) {
        return uniformsCache.get(name);
    }

    /**
     * 获取缓存的 program ID。
     *
     * @param name 程序名
     * @return program ID；未缓存返回 0
     */
    public int getProgramId(String name) {
        Integer id = programCache.get(name);
        return id != null ? id : 0;
    }

    /**
     * 缓存已编译的程序。
     *
     * <p>
     * 若同名程序已存在，旧程序不会被自动删除（需调用方先调用
     * {@link #removeProgram(String)}）。缓存后会清除该程序的编译错误记录。
     * </p>
     *
     * @param name      程序名
     * @param programId program ID
     * @param uniforms  uniform 管理器
     */
    public void putProgram(String name, int programId, ProgramUniforms uniforms) {
        programCache.put(name, programId);
        uniformsCache.put(name, uniforms);
        // 缓存成功后清除之前的错误记录
        compileErrors.remove(name);
    }

    /**
     * 记录编译错误。
     *
     * @param name  程序名
     * @param error 错误信息
     */
    public void recordError(String name, String error) {
        compileErrors.put(name, error);
    }

    /**
     * 获取编译错误信息。
     *
     * @param name 程序名
     * @return 错误信息；无记录返回 {@code null}
     */
    public String getError(String name) {
        return compileErrors.get(name);
    }

    /**
     * 检查指定程序是否存在编译错误。
     *
     * @param name 程序名
     * @return 存在错误返回 {@code true}，否则返回 {@code false}
     */
    public boolean hasError(String name) {
        return compileErrors.containsKey(name);
    }

    /**
     * 清除所有缓存并删除所有 GL 程序。
     *
     * <p>
     * 遍历 {@link #programCache} 中所有 program ID 调用 {@code glDeleteProgram}，
     * 然后清空三个缓存映射。重复调用是安全的。
     * </p>
     *
     * @throws RuntimeException 如果不在渲染线程调用
     */
    public void clear() {
        checkRenderThread();
        for (Integer id : programCache.values()) {
            if (id != null && id != 0) {
                GL20.glDeleteProgram(id);
            }
        }
        int count = programCache.size();
        programCache.clear();
        uniformsCache.clear();
        compileErrors.clear();
        if (count > 0) {
            UHDFast.LOGGER.debug("已清除 {} 个缓存程序", count);
        }
    }

    /**
     * 删除单个缓存项并删除对应的 GL 程序。
     *
     * <p>
     * 从三个缓存映射中移除指定程序，并调用 {@code glDeleteProgram} 释放 GL 资源。
     * 若程序不存在则无操作。重复调用是安全的。
     * </p>
     *
     * @param name 程序名
     * @throws RuntimeException 如果程序存在且不在渲染线程调用
     */
    public void removeProgram(String name) {
        Integer id = programCache.remove(name);
        uniformsCache.remove(name);
        compileErrors.remove(name);
        if (id != null && id != 0) {
            checkRenderThread();
            GL20.glDeleteProgram(id);
        }
    }

    /**
     * 返回缓存程序数量。
     *
     * @return 已缓存的程序数量
     */
    public int size() {
        return programCache.size();
    }

    /**
     * 返回所有已缓存的程序名。
     *
     * @return 程序名集合（为 {@link #programCache} 的 key 视图，修改会影响缓存）
     */
    public Set<String> getProgramNames() {
        return programCache.keySet();
    }

    /**
     * 校验当前是否在渲染线程，否则抛出异常。
     */
    private void checkRenderThread() {
        if (!RenderSystem.isOnRenderThread()) {
            throw new RuntimeException("程序缓存 GL 操作必须在渲染线程执行");
        }
    }
}
