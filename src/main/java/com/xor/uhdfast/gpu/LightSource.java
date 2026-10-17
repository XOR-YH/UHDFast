package com.xor.uhdfast.gpu;

/**
 * 光源数据类
 * 用于在CPU和GPU之间传递光源信息
 */
public class LightSource {

    public enum LightType {
        POINT(0),
        SPOT(1),
        DIRECTIONAL(2);

        private final int value;

        LightType(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    // 光源位置 (x, y, z)
    public final float posX;
    public final float posY;
    public final float posZ;

    // 光源颜色 (r, g, b)
    public final float colorR;
    public final float colorG;
    public final float colorB;

    // 光源强度
    public final float intensity;

    // 光源类型
    public final LightType type;

    // 光源半径（仅点光源和聚光灯有效）
    public final float radius;

    // 聚光灯方向（仅聚光灯有效）
    public final float dirX;
    public final float dirY;
    public final float dirZ;

    // 聚光灯角度（仅聚光灯有效）
    public final float spotAngle;

    public LightSource(float posX, float posY, float posZ,
            float colorR, float colorG, float colorB,
            float intensity, LightType type, float radius) {
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
        this.colorR = colorR;
        this.colorG = colorG;
        this.colorB = colorB;
        this.intensity = intensity;
        this.type = type;
        this.radius = radius;
        this.dirX = 0;
        this.dirY = 0;
        this.dirZ = 0;
        this.spotAngle = 0;
    }

    public LightSource(float posX, float posY, float posZ,
            float colorR, float colorG, float colorB,
            float intensity, LightType type, float radius,
            float dirX, float dirY, float dirZ, float spotAngle) {
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
        this.colorR = colorR;
        this.colorG = colorG;
        this.colorB = colorB;
        this.intensity = intensity;
        this.type = type;
        this.radius = radius;
        this.dirX = dirX;
        this.dirY = dirY;
        this.dirZ = dirZ;
        this.spotAngle = spotAngle;
    }

    /**
     * 创建点光源
     */
    public static LightSource pointLight(float x, float y, float z,
            float r, float g, float b,
            float intensity, float radius) {
        return new LightSource(x, y, z, r, g, b, intensity, LightType.POINT, radius);
    }

    /**
     * 创建方向光（太阳光）
     */
    public static LightSource directionalLight(float dirX, float dirY, float dirZ,
            float r, float g, float b,
            float intensity) {
        return new LightSource(0, 0, 0, r, g, b, intensity, LightType.DIRECTIONAL, 0,
                dirX, dirY, dirZ, 0);
    }
}
