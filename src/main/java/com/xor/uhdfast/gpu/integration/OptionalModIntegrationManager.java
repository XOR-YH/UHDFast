package com.xor.uhdfast.gpu.integration;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.compat.ModCompatibility;

/**
 * Runtime integration manager for optional mod dependencies.
 * 
 * This class handles optional mod integration WITHOUT requiring the mods
 * to be present at compile time. All integration is done via reflection
 * and runtime detection.
 * 
 * Supported optional mods:
 * - Distant Horizons: GPU-accelerated LOD generation
 * - Chunky: GPU-accelerated chunk pregeneration
 * 
 * When these mods are not installed, UHDFast works perfectly fine with zero
 * impact.
 * When they ARE installed, GPU acceleration is automatically enabled.
 */
public class OptionalModIntegrationManager {

    private static volatile boolean initialized = false;

    /**
     * Initialize optional mod integrations.
     * Called during mod setup, safe to call even when optional mods are absent.
     */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }

        UHDFast.LOGGER.info("OptionalModIntegration: Checking for optional mod integrations...");

        // Initialize Chunky GPU integration if Chunky is detected
        if (ModCompatibility.hasChunky()) {
            try {
                ChunkyGPUIntegration.initialize();
                UHDFast.LOGGER.info("  Chunky GPU integration: ENABLED");
            } catch (Exception e) {
                UHDFast.LOGGER.warn("  Chunky GPU integration: FAILED to initialize ({})", e.getMessage());
            }
        } else {
            UHDFast.LOGGER.info("  Chunky GPU integration: SKIPPED (mod not detected)");
        }

        // Initialize Distant Horizons GPU integration if DH is detected
        if (ModCompatibility.hasDistantHorizonsAPI()) {
            try {
                DistantHorizonsGPUIntegration.initialize();
                UHDFast.LOGGER.info("  Distant Horizons GPU integration: ENABLED");
            } catch (Exception e) {
                UHDFast.LOGGER.warn("  Distant Horizons GPU integration: FAILED to initialize ({})", e.getMessage());
            }
        } else {
            UHDFast.LOGGER.info("  Distant Horizons GPU integration: SKIPPED (mod not detected)");
        }

        initialized = true;
    }

    /**
     * Clean up all optional mod integrations.
     */
    public static synchronized void cleanup() {
        if (!initialized) {
            return;
        }

        if (ModCompatibility.hasChunky()) {
            try {
                ChunkyGPUIntegration.cleanup();
            } catch (Exception e) {
                // ignore during shutdown
            }
        }

        if (ModCompatibility.hasDistantHorizonsAPI()) {
            try {
                DistantHorizonsGPUIntegration.cleanup();
            } catch (Exception e) {
                // ignore during shutdown
            }
        }

        initialized = false;
    }

    /**
     * Get a summary of active optional integrations.
     */
    public static String getIntegrationStatus() {
        StringBuilder sb = new StringBuilder("Optional integrations:");

        if (ModCompatibility.hasChunky()) {
            sb.append(" Chunky[");
            sb.append(ChunkyGPUIntegration.isInitialized() ? "active" : "inactive");
            if (ChunkyGPUIntegration.isActive()) {
                sb.append(", ").append(ChunkyGPUIntegration.getStatsString());
            }
            sb.append("]");
        }

        if (ModCompatibility.hasDistantHorizonsAPI()) {
            sb.append(" DH[");
            sb.append(DistantHorizonsGPUIntegration.isInitialized() ? "active" : "inactive");
            if (DistantHorizonsGPUIntegration.isActive()) {
                sb.append(", ").append(DistantHorizonsGPUIntegration.getStatsString());
            }
            sb.append("]");
        }

        if (sb.toString().equals("Optional integrations:")) {
            sb.append(" none");
        }

        return sb.toString();
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
