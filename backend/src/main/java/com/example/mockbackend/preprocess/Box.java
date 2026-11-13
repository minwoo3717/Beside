package com.example.mockbackend.preprocess;

/** An axis-aligned box in pixels: (x1, y1) top-left, (x2, y2) bottom-right, exclusive. */
public record Box(float x1, float y1, float x2, float y2) {

    public float width() {
        return Math.max(0f, x2 - x1);
    }

    public float height() {
        return Math.max(0f, y2 - y1);
    }

    public float area() {
        return width() * height();
    }

    public float longSide() {
        return Math.max(width(), height());
    }

    public float centerX() {
        return (x1 + x2) / 2f;
    }

    public float centerY() {
        return (y1 + y2) / 2f;
    }

    /** Area shared with {@code other}. */
    public float intersection(Box other) {
        float w = Math.min(x2, other.x2) - Math.max(x1, other.x1);
        float h = Math.min(y2, other.y2) - Math.max(y1, other.y1);
        return w <= 0 || h <= 0 ? 0f : w * h;
    }

    /** Intersection over union, 0 when the boxes do not touch. */
    public float iou(Box other) {
        float shared = intersection(other);
        float union = area() + other.area() - shared;
        return union <= 0 ? 0f : shared / union;
    }

    /** Each side moved out by {@code fraction} of the box's width (left, right) or height (top, bottom). */
    public Box grow(double fraction) {
        float dx = (float) (width() * fraction);
        float dy = (float) (height() * fraction);
        return new Box(x1 - dx, y1 - dy, x2 + dx, y2 + dy);
    }

    /** Clipped to a {@code width} x {@code height} picture. */
    public Box clip(int width, int height) {
        return new Box(clamp(x1, width), clamp(y1, height), clamp(x2, width), clamp(y2, height));
    }

    /** Coordinates multiplied by {@code factor} (pixels of a reduced picture to pixels of the original). */
    public Box scale(double factor) {
        return new Box((float) (x1 * factor), (float) (y1 * factor), (float) (x2 * factor), (float) (y2 * factor));
    }

    private static float clamp(float value, int max) {
        return Math.max(0f, Math.min(max, value));
    }
}
