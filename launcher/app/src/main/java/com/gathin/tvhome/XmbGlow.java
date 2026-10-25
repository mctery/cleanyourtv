package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

/**
 * The XMB's glows, drawn in software (the blur is not the GPU's) and kept: the blurred silhouette behind a selected
 * category or item icon, the glow behind a selected tile, and a status icon over its two glows. Each is made once
 * per shape, size and colour and kept in a cache of about 8 MB, so moving through the categories does not blur the
 * same icons again; Home empties it when Android asks for memory ({@link #trim}).
 */
final class XmbGlow {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };

    private XmbGlow() {
    }

    static void trim() {
        CACHE.evictAll();
    }

    /**
     * A blurred silhouette of the drawable in the colour, the icon's size plus the blur on each side. id is the
     * drawable's resource, for the cache; with 0 the glow is made and not kept.
     */
    static Bitmap of(int id, Drawable d, int size, int blur, int color) {
        String key = id != 0 ? "s" + id + '/' + size + '/' + blur + '/' + color : null;
        Bitmap b = key != null ? CACHE.get(key) : null;
        if (b != null) return b;
        Bitmap mask = mask(d, size, blur);
        b = Bitmap.createBitmap(mask.getWidth(), mask.getHeight(), Bitmap.Config.ARGB_8888);
        glow(new Canvas(b), mask, blur * 0.6f, color);
        mask.recycle();
        if (key != null) CACHE.put(key, b);
        return b;
    }

    /** The same round a rounded rectangle: the glow behind a selected tile. */
    static Bitmap rect(int w, int h, int radius, int blur, int color) {
        String key = "r" + w + 'x' + h + '/' + radius + '/' + blur + '/' + color;
        Bitmap b = CACHE.get(key);
        if (b != null) return b;
        Bitmap shape = Bitmap.createBitmap(w + 2 * blur, h + 2 * blur, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        new Canvas(shape).drawRoundRect(new RectF(blur, blur, blur + w, blur + h), radius, radius, p);
        Bitmap mask = shape.extractAlpha();
        shape.recycle();
        b = Bitmap.createBitmap(mask.getWidth(), mask.getHeight(), Bitmap.Config.ARGB_8888);
        glow(new Canvas(b), mask, blur * 0.6f, color);
        mask.recycle();
        CACHE.put(key, b);
        return b;
    }

    /**
     * A status icon: the drawable in its tint over two glows of its shape, a wide one (outer) and a tight one
     * (inner), on a square of the icon's size plus pad on each side.
     */
    static Bitmap icon(int id, Drawable d, int size, int pad, int tint, int inner, float innerBlur, int outer,
                       float outerBlur) {
        String key = "i" + id + '/' + size + '/' + pad + '/' + tint + '/' + inner + '/' + innerBlur + '/' + outer
                + '/' + outerBlur;
        Bitmap b = CACHE.get(key);
        if (b != null) return b;
        Bitmap mask = mask(d, size, pad);
        b = Bitmap.createBitmap(mask.getWidth(), mask.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        glow(c, mask, outerBlur, outer);
        glow(c, mask, innerBlur, inner);
        mask.recycle();
        Drawable top = copy(d);
        top.setTint(tint);
        top.setBounds(pad, pad, pad + size, pad + size);
        top.draw(c);
        CACHE.put(key, b);
        return b;
    }

    /**
     * A word drawn as an icon: the text in its tint over the same two glows, on its ink's bounds plus pad on each
     * side, so it lines up with the icons by its middle (the PM2.5 label, which no picture says as plainly).
     */
    static Bitmap label(String text, Typeface face, float size, int pad, int tint, int inner, float innerBlur, int outer,
                        float outerBlur) {
        String key = "t" + text + '/' + System.identityHashCode(face) + '/' + size + '/' + pad + '/' + tint + '/' + inner
                + '/' + innerBlur + '/' + outer + '/' + outerBlur;
        Bitmap b = CACHE.get(key);
        if (b != null) return b;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(Color.WHITE);
        android.graphics.Rect r = new android.graphics.Rect();
        p.getTextBounds(text, 0, text.length(), r);
        int w = r.width() + 2 * pad, h = r.height() + 2 * pad;
        float x = pad - r.left, y = pad - r.top;
        Bitmap shape = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(shape).drawText(text, x, y, p);
        Bitmap mask = shape.extractAlpha();
        shape.recycle();
        b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        glow(c, mask, outerBlur, outer);
        glow(c, mask, innerBlur, inner);
        mask.recycle();
        p.setColor(tint);
        c.drawText(text, x, y, p);
        CACHE.put(key, b);
        return b;
    }

    /** A copy to tint, so the drawable on screen keeps its own tint. */
    private static Drawable copy(Drawable d) {
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable().mutate() : d.mutate();
    }

    /** The drawable's shape as an alpha mask, size square with pad round it. */
    private static Bitmap mask(Drawable d, int size, int pad) {
        int full = size + 2 * pad;
        Bitmap shape = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888);
        Drawable white = copy(d);
        white.setTint(Color.WHITE);
        white.setBounds(pad, pad, pad + size, pad + size);
        white.draw(new Canvas(shape));
        Bitmap alpha = shape.extractAlpha();
        shape.recycle();
        return alpha;
    }

    private static void glow(Canvas c, Bitmap mask, float blur, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setMaskFilter(new BlurMaskFilter(Math.max(1f, blur), BlurMaskFilter.Blur.NORMAL));
        c.drawBitmap(mask, 0, 0, p);
    }
}
