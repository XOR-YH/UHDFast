package com.xor.uhdfast.shaderpack;

import com.xor.uhdfast.UHDFast;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 光影包生命周期管理器（单例）。
 * <p>
 * 负责光影包目录初始化、可用光影包列出、加载/卸载光影包以及启用状态管理。
 * 光影包目录默认位于 {@code <gameDir>/shaderpacks/}。
 * </p>
 * <p>
 * 典型使用流程：
 * </p>
 * <pre>{@code
 * ShaderPackManager mgr = ShaderPackManager.getInstance();
 * mgr.initialize();
 * List<String> packs = mgr.listAvailablePacks();
 * if (!packs.isEmpty() && mgr.loadPack(packs.get(0))) {
 *     mgr.setEnabled(true);
 * }
 * }</pre>
 */
public class ShaderPackManager {

    /** 单例实例 */
    private static ShaderPackManager instance;

    /** 当前已加载的光影包 */
    private ShaderPack currentPack;

    /** 光影包是否启用 */
    private boolean enabled = false;

    /** 光影包目录，默认为 {@code gameDir/shaderpacks} */
    private Path shaderPacksDir;

    /**
     * 私有构造器，禁止外部实例化。
     */
    private ShaderPackManager() {
    }

    /**
     * 获取单例实例。
     *
     * @return {@link ShaderPackManager} 单例
     */
    public static ShaderPackManager getInstance() {
        if (instance == null) {
            synchronized (ShaderPackManager.class) {
                if (instance == null) {
                    instance = new ShaderPackManager();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化管理器，创建 shaderpacks 目录。
     * <p>
     * 目录默认为 {@code Minecraft.getInstance().gameDirectory/shaderpacks}。
     * 若目录不存在则创建。
     * </p>
     */
    public void initialize() {
        try {
            Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
            this.shaderPacksDir = gameDir.resolve("shaderpacks");
            if (!Files.isDirectory(shaderPacksDir)) {
                Files.createDirectories(shaderPacksDir);
                UHDFast.LOGGER.info("Created shaderpacks directory: {}", shaderPacksDir);
            } else {
                UHDFast.LOGGER.debug("Shaderpacks directory ready: {}", shaderPacksDir);
            }
        } catch (IOException e) {
            UHDFast.LOGGER.error("Failed to initialize shaderpacks directory: {}", e.getMessage(), e);
        } catch (Exception e) {
            UHDFast.LOGGER.error("Unexpected error during shaderpack manager initialization: {}",
                    e.getMessage(), e);
        }
    }

    /**
     * 列出 shaderpacks 目录下所有 {@code .zip} 光影包文件名（不含扩展名）。
     *
     * @return 光影包名称列表；目录未初始化或为空时返回空列表
     */
    public List<String> listAvailablePacks() {
        List<String> packs = new ArrayList<>();
        if (shaderPacksDir == null || !Files.isDirectory(shaderPacksDir)) {
            UHDFast.LOGGER.debug("Shaderpacks directory not available, returning empty list");
            return packs;
        }
        try (Stream<Path> stream = Files.list(shaderPacksDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".zip");
                    })
                    .forEach(p -> {
                        String fileName = p.getFileName().toString();
                        // 去掉 .zip 扩展名
                        String packName = fileName.substring(0, fileName.length() - 4);
                        packs.add(packName);
                    });
        } catch (IOException e) {
            UHDFast.LOGGER.error("Failed to list shader packs: {}", e.getMessage(), e);
        } catch (Exception e) {
            UHDFast.LOGGER.error("Unexpected error listing shader packs: {}", e.getMessage(), e);
        }
        return packs;
    }

    /**
     * 加载指定名称的光影包。
     * <p>
     * 在 shaderpacks 目录下查找 {@code <name>.zip} 文件，调用 {@link ShaderPackParser} 解析。
     * 解析成功后设置 {@link #currentPack}。
     * </p>
     *
     * @param name 光影包名称（不含 .zip 扩展名）
     * @return 加载成功返回 {@code true}，失败返回 {@code false}
     */
    public boolean loadPack(String name) {
        if (name == null || name.isEmpty()) {
            UHDFast.LOGGER.warn("Cannot load shader pack: name is null or empty");
            return false;
        }
        if (shaderPacksDir == null || !Files.isDirectory(shaderPacksDir)) {
            UHDFast.LOGGER.error("Shaderpacks directory not initialized, cannot load pack '{}'", name);
            return false;
        }
        Path zipPath = shaderPacksDir.resolve(name + ".zip");
        if (!Files.isRegularFile(zipPath)) {
            UHDFast.LOGGER.error("Shader pack file not found: {}", zipPath);
            return false;
        }
        try {
            ShaderPackParser parser = new ShaderPackParser();
            ShaderPack pack = parser.parse(zipPath, name);
            this.currentPack = pack;
            UHDFast.LOGGER.info("Shader pack '{}' loaded successfully", name);
            return true;
        } catch (RuntimeException e) {
            UHDFast.LOGGER.error("Failed to load shader pack '{}': {}", name, e.getMessage(), e);
            this.currentPack = null;
            return false;
        } catch (Exception e) {
            UHDFast.LOGGER.error("Unexpected error loading shader pack '{}': {}", name, e.getMessage(), e);
            this.currentPack = null;
            return false;
        }
    }

    /**
     * 卸载当前光影包。
     * <p>
     * 将 {@link #currentPack} 置为 {@code null}，并禁用启用状态。
     * </p>
     */
    public void unloadPack() {
        if (currentPack != null) {
            UHDFast.LOGGER.info("Unloading shader pack '{}'", currentPack.getName());
        }
        this.currentPack = null;
        this.enabled = false;
    }

    /**
     * 获取当前已加载的光影包。
     *
     * @return 当前光影包；未加载返回 {@code null}
     */
    public ShaderPack getCurrentPack() {
        return currentPack;
    }

    /**
     * 查询光影包是否启用。
     *
     * @return 启用返回 {@code true}，否则返回 {@code false}
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置光影包启用状态。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        boolean wasEnabled = this.enabled;
        this.enabled = enabled;
        if (wasEnabled != enabled) {
            UHDFast.LOGGER.info("Shader pack enabled state changed: {} -> {}", wasEnabled, enabled);
        }
    }

    /**
     * 判断是否有可用的光影包（已加载且已启用）。
     *
     * @return currentPack 不为 null 且 enabled 为 {@code true} 时返回 {@code true}
     */
    public boolean isShaderPackLoaded() {
        return currentPack != null && enabled;
    }

    /**
     * 获取光影包目录路径。
     *
     * @return shaderpacks 目录；未初始化时返回 {@code null}
     */
    public Path getShaderPacksDir() {
        return shaderPacksDir;
    }
}
