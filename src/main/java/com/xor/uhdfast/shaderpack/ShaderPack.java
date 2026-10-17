package com.xor.uhdfast.shaderpack;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 表示一个已加载的光影包。
 * <p>
 * 光影包包含顶点着色器和片段着色器源码，以及光影配置属性。
 * 每个着色器程序（如 {@code gbuffers_terrain}）对应一对顶点/片段着色器源码。
 * </p>
 * <p>
 * 该类为不可变数据容器，由 {@link ShaderPackParser} 解析后填充。
 * </p>
 */
public class ShaderPack {

    /** 光影包名称 */
    private final String name;

    /** 光影配置属性，解析自 {@code shaders/shaders.properties} */
    private final ShaderProperties properties;

    /** 顶点着色器源码映射，key=程序名（如 "gbuffers_terrain"） */
    private final Map<String, String> vertexSources;

    /** 片段着色器源码映射，key=程序名（如 "gbuffers_terrain"） */
    private final Map<String, String> fragmentSources;

    /**
     * 构造一个新的光影包。
     *
     * @param name 光影包名称
     */
    public ShaderPack(String name) {
        this.name = name;
        this.properties = new ShaderProperties();
        this.vertexSources = new HashMap<>();
        this.fragmentSources = new HashMap<>();
    }

    /**
     * 获取光影包名称。
     *
     * @return 光影包名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取光影配置属性。
     *
     * @return {@link ShaderProperties} 实例
     */
    public ShaderProperties getProperties() {
        return properties;
    }

    /**
     * 获取顶点着色器源码映射（可变，用于解析器填充）。
     *
     * @return 顶点着色器源码映射
     */
    public Map<String, String> getVertexSources() {
        return vertexSources;
    }

    /**
     * 获取片段着色器源码映射（可变，用于解析器填充）。
     *
     * @return 片段着色器源码映射
     */
    public Map<String, String> getFragmentSources() {
        return fragmentSources;
    }

    /**
     * 根据程序名获取着色器源码。
     *
     * @param programName 程序名（如 "gbuffers_terrain"）
     * @param isVertex    {@code true} 获取顶点着色器，{@code false} 获取片段着色器
     * @return 着色器源码，若不存在返回 {@code null}
     */
    public String getSource(String programName, boolean isVertex) {
        try {
            Map<String, String> sources = isVertex ? vertexSources : fragmentSources;
            return sources.get(programName);
        } catch (Exception e) {
            com.xor.uhdfast.UHDFast.LOGGER.error("Failed to get shader source for program '{}': {}",
                    programName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 检查是否存在指定名称的着色器程序。
     * <p>
     * 只要顶点或片段源码任一存在，即视为该程序存在。
     * </p>
     *
     * @param programName 程序名
     * @return 存在返回 {@code true}，否则返回 {@code false}
     */
    public boolean hasProgram(String programName) {
        try {
            return vertexSources.containsKey(programName) || fragmentSources.containsKey(programName);
        } catch (Exception e) {
            com.xor.uhdfast.UHDFast.LOGGER.error("Failed to check program existence '{}': {}",
                    programName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取所有着色器程序名集合。
     * <p>
     * 合并顶点和片段着色器的所有 key。
     * </p>
     *
     * @return 不可变的程序名集合
     */
    public Set<String> getProgramNames() {
        try {
            Set<String> names = new HashSet<>();
            names.addAll(vertexSources.keySet());
            names.addAll(fragmentSources.keySet());
            return Collections.unmodifiableSet(names);
        } catch (Exception e) {
            com.xor.uhdfast.UHDFast.LOGGER.error("Failed to get program names: {}", e.getMessage(), e);
            return Collections.emptySet();
        }
    }
}
