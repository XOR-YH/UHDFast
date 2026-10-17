package com.xor.uhdfast.shaderpack;

import com.xor.uhdfast.UHDFast;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 解析与存储 {@code shaders.properties} 配置文件。
 * <p>
 * 该文件采用 {@code key=value} 格式，支持 {@code #} 注释和空行。
 * 常见键包括：
 * </p>
 * <ul>
 *   <li>{@code shadowMapWidth} / {@code shadowMapHeight}：阴影贴图分辨率</li>
 *   <li>{@code noiseTextureResolution}：噪声纹理分辨率</li>
 *   <li>{@code sunPathRotation}：太阳路径旋转角度</li>
 *   <li>{@code shadowDistance}：阴影渲染距离</li>
 * </ul>
 */
public class ShaderProperties {

    /** 存储所有键值对 */
    private final Map<String, String> properties = new HashMap<>();

    /**
     * 从 {@link BufferedReader} 解析 key=value 格式的配置。
     * <p>
     * 解析规则：
     * </p>
     * <ul>
     *   <li>忽略以 {@code #} 开头的注释行</li>
     *   <li>忽略空白行</li>
     *   <li>以第一个 {@code =} 分割键和值</li>
     *   <li>自动去除键值两端的空白字符</li>
     * </ul>
     *
     * @param reader 输入 reader
     */
    public void parse(BufferedReader reader) {
        if (reader == null) {
            UHDFast.LOGGER.warn("ShaderProperties parse: reader is null, skipping");
            return;
        }
        try {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                String trimmed = line.trim();
                // 跳过空行和注释
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eqIdx = trimmed.indexOf('=');
                if (eqIdx <= 0) {
                    UHDFast.LOGGER.warn("ShaderProperties: invalid format at line {}: {}", lineNum, line);
                    continue;
                }
                String key = trimmed.substring(0, eqIdx).trim();
                String value = trimmed.substring(eqIdx + 1).trim();
                if (key.isEmpty()) {
                    UHDFast.LOGGER.warn("ShaderProperties: empty key at line {}: {}", lineNum, line);
                    continue;
                }
                properties.put(key, value);
            }
            UHDFast.LOGGER.debug("ShaderProperties parsed {} entries", properties.size());
        } catch (IOException e) {
            UHDFast.LOGGER.error("Failed to parse shader properties: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取字符串值。
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 对应值，若不存在返回默认值
     */
    public String getString(String key, String defaultValue) {
        try {
            String value = properties.get(key);
            return value != null ? value : defaultValue;
        } catch (Exception e) {
            UHDFast.LOGGER.error("Failed to get string property '{}': {}", key, e.getMessage(), e);
            return defaultValue;
        }
    }

    /**
     * 获取整数值。
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 解析后的整数值，若不存在或格式错误返回默认值
     */
    public int getInt(String key, int defaultValue) {
        try {
            String value = properties.get(key);
            if (value == null) {
                return defaultValue;
            }
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            UHDFast.LOGGER.warn("Invalid integer for key '{}': {}", key, properties.get(key));
            return defaultValue;
        } catch (Exception e) {
            UHDFast.LOGGER.error("Failed to get int property '{}': {}", key, e.getMessage(), e);
            return defaultValue;
        }
    }

    /**
     * 获取浮点数值。
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 解析后的浮点值，若不存在或格式错误返回默认值
     */
    public float getFloat(String key, float defaultValue) {
        try {
            String value = properties.get(key);
            if (value == null) {
                return defaultValue;
            }
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            UHDFast.LOGGER.warn("Invalid float for key '{}': {}", key, properties.get(key));
            return defaultValue;
        } catch (Exception e) {
            UHDFast.LOGGER.error("Failed to get float property '{}': {}", key, e.getMessage(), e);
            return defaultValue;
        }
    }

    /**
     * 获取布尔值。
     * <p>
     * 接受 {@code true}/{@code false}（不区分大小写）。
     * </p>
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 解析后的布尔值，若不存在或格式错误返回默认值
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        try {
            String value = properties.get(key);
            if (value == null) {
                return defaultValue;
            }
            String v = value.trim().toLowerCase();
            if ("true".equals(v)) {
                return true;
            } else if ("false".equals(v)) {
                return false;
            }
            UHDFast.LOGGER.warn("Invalid boolean for key '{}': {}", key, value);
            return defaultValue;
        } catch (Exception e) {
            UHDFast.LOGGER.error("Failed to get boolean property '{}': {}", key, e.getMessage(), e);
            return defaultValue;
        }
    }

    /**
     * 检查是否存在指定键。
     *
     * @param key 配置键
     * @return 存在返回 {@code true}，否则返回 {@code false}
     */
    public boolean hasKey(String key) {
        try {
            return properties.containsKey(key);
        } catch (Exception e) {
            UHDFast.LOGGER.error("Failed to check key existence '{}': {}", key, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取所有属性的不可变视图。
     *
     * @return 不可变属性映射
     */
    public Map<String, String> getAll() {
        return Collections.unmodifiableMap(properties);
    }
}
