package com.xor.uhdfast.shaderpack;

import com.xor.uhdfast.UHDFast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 着色器源码加载与预处理工具类。
 * <p>
 * 提供以下功能：
 * </p>
 * <ul>
 *   <li>从 {@link InputStream} 读取着色器源码为字符串</li>
 *   <li>处理 {@code #include "filename.glsl"} 指令，递归替换为文件内容</li>
 *   <li>在 {@code #version} 行之后注入 {@code #define} 宏定义</li>
 * </ul>
 * <p>
 * 该类为静态工具类，无需实例化。
 * </p>
 */
public final class ShaderSourceLoader {

    /** 匹配 {@code #include "filename"} 指令的正则 */
    private static final Pattern INCLUDE_PATTERN =
            Pattern.compile("^\\s*#\\s*include\\s+\"([^\"]+)\"\\s*$");

    /** 私有构造器，禁止实例化 */
    private ShaderSourceLoader() {
    }

    /**
     * 从输入流读取着色器源码为字符串。
     * <p>
     * 使用 UTF-8 编码逐行读取并合并为单个字符串。流会在读取完成后关闭。
     * </p>
     *
     * @param stream 输入流
     * @param name   源码名称（仅用于日志）
     * @param includeCache include 缓存（当前方法未使用，保留以兼容签名）
     * @return 源码字符串；读取失败返回 {@code null}
     */
    public static String loadSource(InputStream stream, String name, Map<String, String> includeCache) {
        if (stream == null) {
            UHDFast.LOGGER.warn("Cannot load shader source '{}': stream is null", name);
            return null;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) {
                    sb.append('\n');
                }
                sb.append(line);
                first = false;
            }
        } catch (IOException e) {
            UHDFast.LOGGER.error("Failed to read shader source '{}': {}", name, e.getMessage(), e);
            return null;
        }
        return sb.toString();
    }

    /**
     * 处理 {@code #include "filename.glsl"} 指令。
     * <p>
     * 解析每行中的 include 指令，从 {@code includeCache} 查找对应文件内容并递归替换。
     * 使用 {@link Set} 记录已处理的 include 文件以防止循环引用。
     * 未在缓存中找到的 include 将保留原行并记录警告。
     * </p>
     *
     * @param source       原始源码
     * @param includeCache include 文件缓存（key=文件名，value=源码内容）
     * @return 处理后的源码；输入为 {@code null} 时返回 {@code null}
     */
    public static String processIncludes(String source, Map<String, String> includeCache) {
        if (source == null) {
            return null;
        }
        if (includeCache == null || includeCache.isEmpty()) {
            return source;
        }
        try {
            Set<String> visited = new HashSet<>();
            return processIncludesInternal(source, includeCache, visited);
        } catch (Exception e) {
            UHDFast.LOGGER.error("Failed to process includes: {}", e.getMessage(), e);
            return source;
        }
    }

    /**
     * 递归处理 include 指令的内部实现。
     *
     * @param source       当前源码
     * @param includeCache include 缓存
     * @param visited      已处理的 include 文件名集合（用于循环引用检测）
     * @return 处理后的源码
     */
    private static String processIncludesInternal(String source, Map<String, String> includeCache,
                                                  Set<String> visited) {
        String[] lines = source.split("\n", -1);
        StringBuilder output = new StringBuilder(source.length());
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = INCLUDE_PATTERN.matcher(lines[i]);
            if (matcher.matches()) {
                String fileName = matcher.group(1);
                if (visited.contains(fileName)) {
                    UHDFast.LOGGER.warn("Circular include detected, skipping: {}", fileName);
                    output.append("// CIRCULAR INCLUDE SKIPPED: ").append(fileName);
                } else if (includeCache.containsKey(fileName)) {
                    visited.add(fileName);
                    String includedContent = includeCache.get(fileName);
                    String processed = processIncludesInternal(includedContent, includeCache, visited);
                    output.append(processed);
                    visited.remove(fileName);
                } else {
                    UHDFast.LOGGER.warn("Include file not found in cache: {}", fileName);
                    output.append(lines[i]);
                }
            } else {
                output.append(lines[i]);
            }
            if (i < lines.length - 1) {
                output.append('\n');
            }
        }
        return output.toString();
    }

    /**
     * 在 {@code #version} 行之后注入 {@code #define} 宏定义。
     * <p>
     * 查找第一个以 {@code #version} 开头的行，在其后插入所有宏定义。
     * 若未找到 {@code #version} 行，则在源码最前面插入宏定义。
     * 每个宏格式为 {@code #define KEY VALUE}。
     * </p>
     *
     * @param source 原始源码
     * @param macros 宏定义映射（key=宏名，value=宏值）
     * @return 注入宏后的源码；输入为 {@code null} 时返回 {@code null}
     */
    public static String injectMacros(String source, Map<String, String> macros) {
        if (source == null) {
            return null;
        }
        if (macros == null || macros.isEmpty()) {
            return source;
        }
        try {
            StringBuilder macroBlock = new StringBuilder();
            for (Map.Entry<String, String> entry : macros.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || key.isEmpty()) {
                    continue;
                }
                if (value == null || value.isEmpty()) {
                    macroBlock.append("#define ").append(key).append('\n');
                } else {
                    macroBlock.append("#define ").append(key).append(' ').append(value).append('\n');
                }
            }
            if (macroBlock.length() == 0) {
                return source;
            }
            String[] lines = source.split("\n", -1);
            StringBuilder output = new StringBuilder(source.length() + macroBlock.length());
            boolean injected = false;
            for (int i = 0; i < lines.length; i++) {
                output.append(lines[i]);
                if (i < lines.length - 1) {
                    output.append('\n');
                }
                // 在 #version 行之后注入
                if (!injected && lines[i].trim().startsWith("#version")) {
                    output.append(macroBlock);
                    injected = true;
                }
            }
            // 若未找到 #version 行，则将宏插入到最前面
            if (!injected) {
                StringBuilder prefixed = new StringBuilder(macroBlock.length() + source.length() + 1);
                prefixed.append(macroBlock);
                prefixed.append('\n');
                prefixed.append(source);
                return prefixed.toString();
            }
            return output.toString();
        } catch (Exception e) {
            UHDFast.LOGGER.error("Failed to inject macros: {}", e.getMessage(), e);
            return source;
        }
    }
}
