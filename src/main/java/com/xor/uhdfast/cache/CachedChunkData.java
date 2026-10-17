package com.xor.uhdfast.cache;

public class CachedChunkData {
    private final float[] vertexArray;
    private final int[] indexArray;
    private final long timestamp;
    private volatile boolean dirty;
    private Object renderChunkInfo;

    public CachedChunkData(float[] vertexArray, int[] indexArray) {
        this.vertexArray = vertexArray;
        this.indexArray = indexArray;
        this.timestamp = System.nanoTime();
        this.dirty = false;
        this.renderChunkInfo = null;
    }

    public float[] getVertexArray() {
        return vertexArray;
    }

    public int[] getIndexArray() {
        return indexArray;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getVertexCount() {
        return vertexArray != null ? vertexArray.length : 0;
    }

    public int getIndexCount() {
        return indexArray != null ? indexArray.length : 0;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public Object getRenderChunkInfo() {
        return renderChunkInfo;
    }

    public void setRenderChunkInfo(Object renderChunkInfo) {
        this.renderChunkInfo = renderChunkInfo;
    }
}
