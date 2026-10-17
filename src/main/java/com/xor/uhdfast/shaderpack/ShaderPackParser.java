package com.xor.uhdfast.shaderpack;

import com.xor.uhdfast.UHDFast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 光影包解析器，负责从 zip 文件或解压目录加载光影包。
 * <p>
 * 解析流程：
 * </p>
 * <ol>
 *   <li>定位 {@code shaders/} 目录</li>
 *   <li>读取 {@code shaders/shaders.properties}（若存在）填充 {@link ShaderProperties}</li>
 *   <li>读取 {@code shaders/lib/} 下所有 {@code .glsl} 文件作为 include 缓存</li>
 *   <li>遍历所有 {@code .vsh} 和 {@code .fsh} 文件，调用 {@link ShaderSourceLoader} 处理 include</li>
 *   <li>程序名为文件名去掉扩展名后的部分（如 {@code gbuffers_terrain.vsh} → {@code gbuffers_terrain}）</li>
 * </ol>
 * <p>
 * 解析失败时抛出 {@link RuntimeException}，并记录错误日志。
 * </p>
 */
public class ShaderPackParser {

    /** shaders 目录名 */
    private static final String SHADERS_DIR = "shaders";

    /** shaders.properties 文件相对路径 */
    private static final String PROPERTIES_PATH = "shaders/shaders.properties";

    /** lib 目录相对路径 */
    private static final String LIB_DIR = "shaders/lib/";

    /**
     * 构造解析器。
     */
    public ShaderPackParser() {
    }

    /**
     * 解析 zip 格式的光影包。
     *
     * @param zipPath  zip 文件路径
     * @param packName 光影包名称
     * @return 填充好的 {@link ShaderPack}
     * @throws RuntimeException 解析失败时抛出
     */
    public ShaderPack parse(Path zipPath, String packName) {
        if (zipPath == null) {
            String msg = "Cannot parse shader pack '" + packName + "': zipPath is null";
            UHDFast.LOGGER.error(msg);
            throw new RuntimeException(msg);
        }
        UHDFast.LOGGER.info("Parsing shader pack '{}' from {}", packName, zipPath);
        ShaderPack pack = new ShaderPack(packName);
        try (ZipFile zipFile = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            // 1. 先加载 lib 目录下的 .glsl 文件作为 include 缓存
            Map<String, String> includeCache = loadIncludeCacheFromZip(zipFile);
            UHDFast.LOGGER.debug("Loaded {} include files from lib directory", includeCache.size());

            // 2. 解析 shaders.properties
            parsePropertiesFromZip(zipFile, pack.getProperties());

            // 3. 遍历所有 .vsh 和 .fsh 条目
            int vertexCount = 0;
            int fragmentCount = 0;
            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                // 仅处理 shaders/ 目录下的文件
                if (!entryName.startsWith(SHADERS_DIR + "/")) {
                    continue;
                }
                // 跳过 lib 目录和 properties 文件
                if (entryName.startsWith(LIB_DIR) || entryName.equals(PROPERTIES_PATH)) {
                    continue;
                }
                String lower = entryName.toLowerCase();
                if (lower.endsWith(".vsh")) {
                    String programName = extractProgramName(entryName);
                    String source = readZipEntry(zipFile, entry, entryName);
                    if (source != null) {
                        source = ShaderSourceLoader.processIncludes(source, includeCache);
                        pack.getVertexSources().put(programName, source);
                        vertexCount++;
                    }
                } else if (lower.endsWith(".fsh")) {
                    String programName = extractProgramName(entryName);
                    String source = readZipEntry(zipFile, entry, entryName);
                    if (source != null) {
                        source = ShaderSourceLoader.processIncludes(source, includeCache);
                        pack.getFragmentSources().put(programName, source);
                        fragmentCount++;
                    }
                }
            }
            UHDFast.LOGGER.info("Shader pack '{}' parsed: {} vertex shaders, {} fragment shaders",
                    packName, vertexCount, fragmentCount);
            return pack;
        } catch (IOException e) {
            String msg = "Failed to parse shader pack '" + packName + "': " + e.getMessage();
            UHDFast.LOGGER.error(msg, e);
            throw new RuntimeException(msg, e);
        } catch (Exception e) {
            String msg = "Unexpected error parsing shader pack '" + packName + "': " + e.getMessage();
            UHDFast.LOGGER.error(msg, e);
            throw new RuntimeException(msg, e);
        }
    }

    /**
     * 解压目录形式的光影包。
     * <p>
     * {@code dir} 应直接包含 {@code shaders/} 子目录。
     * </p>
     *
     * @param dir      光影包根目录
     * @param packName 光影包名称
     * @return 填充好的 {@link ShaderPack}
     * @throws RuntimeException 解析失败时抛出
     */
    public ShaderPack parseDirectory(Path dir, String packName) {
        if (dir == null) {
            String msg = "Cannot parse shader pack '" + packName + "': dir is null";
            UHDFast.LOGGER.error(msg);
            throw new RuntimeException(msg);
        }
        UHDFast.LOGGER.info("Parsing shader pack '{}' from directory {}", packName, dir);
        ShaderPack pack = new ShaderPack(packName);
        Path shadersDir = dir.resolve(SHADERS_DIR);
        if (!Files.isDirectory(shadersDir)) {
            String msg = "Shaders directory not found: " + shadersDir;
            UHDFast.LOGGER.error(msg);
            throw new RuntimeException(msg);
        }
        try {
            // 1. 加载 lib 目录下的 .glsl 文件作为 include 缓存
            Map<String, String> includeCache = loadIncludeCacheFromDirectory(shadersDir);
            UHDFast.LOGGER.debug("Loaded {} include files from lib directory", includeCache.size());

            // 2. 解析 shaders.properties
            parsePropertiesFromDirectory(shadersDir, pack.getProperties());

            // 3. 遍历 shaders 目录下的 .vsh 和 .fsh 文件
            int vertexCount = 0;
            int fragmentCount = 0;
            try (Stream<Path> stream = Files.walk(shadersDir)) {
                for (Path path : (Iterable<Path>) stream::iterator) {
                    if (Files.isDirectory(path)) {
                        continue;
                    }
                    String relative = shadersDir.relativize(path).toString().replace('\\', '/');
                    // 跳过 lib 目录
                    if (relative.startsWith("lib/")) {
                        continue;
                    }
                    String lower = relative.toLowerCase();
                    if (lower.endsWith(".vsh")) {
                        String programName = extractProgramName(relative);
                        String source = readFile(path, relative);
                        if (source != null) {
                            source = ShaderSourceLoader.processIncludes(source, includeCache);
                            pack.getVertexSources().put(programName, source);
                            vertexCount++;
                        }
                    } else if (lower.endsWith(".fsh")) {
                        String programName = extractProgramName(relative);
                        String source = readFile(path, relative);
                        if (source != null) {
                            source = ShaderSourceLoader.processIncludes(source, includeCache);
                            pack.getFragmentSources().put(programName, source);
                            fragmentCount++;
                        }
                    }
                }
            }
            UHDFast.LOGGER.info("Shader pack '{}' parsed: {} vertex shaders, {} fragment shaders",
                    packName, vertexCount, fragmentCount);
            return pack;
        } catch (IOException e) {
            String msg = "Failed to parse shader pack directory '" + packName + "': " + e.getMessage();
            UHDFast.LOGGER.error(msg, e);
            throw new RuntimeException(msg, e);
        } catch (Exception e) {
            String msg = "Unexpected error parsing shader pack directory '" + packName + "': " + e.getMessage();
            UHDFast.LOGGER.error(msg, e);
            throw new RuntimeException(msg, e);
        }
    }

    /**
     * 从路径中提取程序名（去掉目录和扩展名）。
     *
     * @param entryName zip 条目名或相对路径
     * @return 程序名（如 "gbuffers_terrain"）
     */
    private String extractProgramName(String entryName) {
        String name = entryName;
        int slashIdx = name.lastIndexOf('/');
        if (slashIdx >= 0) {
            name = name.substring(slashIdx + 1);
        }
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx >= 0) {
            name = name.substring(0, dotIdx);
        }
        return name;
    }

    /**
     * 从 zip 中读取 lib 目录下所有 .glsl 文件作为 include 缓存。
     */
    private Map<String, String> loadIncludeCacheFromZip(ZipFile zipFile) {
        Map<String, String> cache = new HashMap<>();
        java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            if (!name.startsWith(LIB_DIR)) {
                continue;
            }
            if (!name.toLowerCase().endsWith(".glsl")) {
                continue;
            }
            String fileName = name.substring(name.lastIndexOf('/') + 1);
            String content = readZipEntry(zipFile, entry, name);
            if (content != null) {
                cache.put(fileName, content);
            }
        }
        return cache;
    }

    /**
     * 从目录中读取 lib 下所有 .glsl 文件作为 include 缓存。
     */
    private Map<String, String> loadIncludeCacheFromDirectory(Path shadersDir) {
        Map<String, String> cache = new HashMap<>();
        Path libDir = shadersDir.resolve("lib");
        if (!Files.isDirectory(libDir)) {
            return cache;
        }
        try (Stream<Path> stream = Files.walk(libDir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(path)) {
                    continue;
                }
                String lower = path.getFileName().toString().toLowerCase();
                if (!lower.endsWith(".glsl")) {
                    continue;
                }
                String fileName = path.getFileName().toString();
                String content = readFile(path, fileName);
                if (content != null) {
                    cache.put(fileName, content);
                }
            }
        } catch (IOException e) {
            UHDFast.LOGGER.error("Failed to load include cache from directory: {}", e.getMessage(), e);
        }
        return cache;
    }

    /**
     * 从 zip 中解析 shaders.properties。
     */
    private void parsePropertiesFromZip(ZipFile zipFile, ShaderProperties properties) {
        ZipEntry entry = zipFile.getEntry(PROPERTIES_PATH);
        if (entry == null) {
            UHDFast.LOGGER.debug("shaders.properties not found in shader pack");
            return;
        }
        try (InputStream stream = zipFile.getInputStream(entry);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            properties.parse(reader);
        } catch (IOException e) {
            UHDFast.LOGGER.error("Failed to read shaders.properties: {}", e.getMessage(), e);
        }
    }

    /**
     * 从目录解析 shaders.properties。
     */
    private void parsePropertiesFromDirectory(Path shadersDir, ShaderProperties properties) {
        Path propsFile = shadersDir.resolve("shaders.properties");
        if (!Files.isRegularFile(propsFile)) {
            UHDFast.LOGGER.debug("shaders.properties not found in shader pack directory");
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(propsFile, StandardCharsets.UTF_8)) {
            properties.parse(reader);
        } catch (IOException e) {
            UHDFast.LOGGER.error("Failed to read shaders.properties: {}", e.getMessage(), e);
        }
    }

    /**
     * 读取 zip 条目内容为字符串。
     */
    private String readZipEntry(ZipFile zipFile, ZipEntry entry, String name) {
        try (InputStream stream = zipFile.getInputStream(entry)) {
            return ShaderSourceLoader.loadSource(stream, name, null);
        } catch (IOException e) {
            UHDFast.LOGGER.error("Failed to read zip entry '{}': {}", name, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 读取文件内容为字符串。
     */
    private String readFile(Path path, String name) {
        try (InputStream stream = Files.newInputStream(path)) {
            return ShaderSourceLoader.loadSource(stream, name, null);
        } catch (IOException e) {
            UHDFast.LOGGER.error("Failed to read file '{}': {}", name, e.getMessage(), e);
            return null;
        }
    }
}
