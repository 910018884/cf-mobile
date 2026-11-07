package com.dsh.cf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.Random;

// 全部素材程序化生成，零外部资源
public final class CFRes {
    public static final int TS = 64, SW = 48, SH = 72;
    public static int[][] wall = new int[6][];
    public static int[][] ground = new int[2][];
    public static int[][][] soldier = new int[2][6][];
    public static int[][] mark = new int[2][];
    public static int[] bomb;
    public static boolean done;

    static int rgb(int r, int g, int b) {
        if (r < 0) r = 0; if (r > 255) r = 255;
        if (g < 0) g = 0; if (g > 255) g = 255;
        if (b < 0) b = 0; if (b > 255) b = 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
    static int shade(int c, float f) {
        return rgb((int) ((c >> 16 & 255) * f), (int) ((c >> 8 & 255) * f), (int) ((c & 255) * f));
    }

    public static void build() {
        if (done) return;
        Random r = new Random(20240808);
        wall[0] = sand(r); wall[1] = brick(r); wall[2] = crate(r);
        wall[3] = metal(r); wall[4] = net(r); wall[5] = concrete(r);
        ground[0] = sandyGround(r); ground[1] = tileGround(r);
        for (int t = 0; t < 2; t++) for (int f = 0; f < 6; f++) soldier[t][f] = soldierPix(t, f);
        mark[0] = letter('A'); mark[1] = letter('B');
        bomb = c4();
        done = true;
    }

    // ---------- 墙面 ----------
    static int[] sand(Random r) {
        int[] p = new int[TS * TS];
        float[] n = noise(r, 0.55f);
        for (int y = 0; y < TS; y++) {
            float g = 1f - y / (float) TS * 0.22f;
            for (int x = 0; x < TS; x++) {
                float s = n[y * TS + x];
                int base = rgb((int) (200 * g), (int) (172 * g), (int) (128 * g));
                p[y * TS + x] = shade(base, 0.86f + s * 0.28f);
            }
        }
        scratch(p, r, 6, 0xFF8F7750);
        return p;
    }
    static int[] brick(Random r) {
        int[] p = new int[TS * TS];
        float[] n = noise(r, 0.35f);
        int bh = 13, bw = 32;
        for (int y = 0; y < TS; y++) {
            int row = y / bh, off = (row % 2) * (bw / 2);
            for (int x = 0; x < TS; x++) {
                int bx = (x + off) % bw, by = y % bh;
                boolean mortar = by < 2 || bx < 2;
                float s = n[y * TS + x];
                int c = mortar ? rgb(196, 180, 156) : rgb((int) (168 + s * 40), (int) (116 + s * 30), (int) (82 + s * 24));
                p[y * TS + x] = c;
            }
        }
        return p;
    }
    static int[] crate(Random r) {
        int[] p = new int[TS * TS];
        float[] n = noise(r, 0.3f);
        for (int y = 0; y < TS; y++) for (int x = 0; x < TS; x++) {
            boolean frame = x < 4 || y < 4 || x > TS - 5 || y > TS - 5;
            boolean plank = y % 16 < 2;
            boolean brace = Math.abs(x - y) < 3 || Math.abs(x + y - TS) < 3;
            float s = n[y * TS + x];
            int c;
            if (frame) c = rgb(104, 68, 34);
            else if (plank) c = rgb(96, 60, 30);
            else if (brace) c = rgb((int) (172 + s * 24), (int) (126 + s * 20), (int) (76 + s * 16));
            else c = rgb((int) (142 + s * 34), (int) (96 + s * 26), (int) (52 + s * 18));
            p[y * TS + x] = c;
        }
        return p;
    }
    static int[] metal(Random r) {
        int[] p = new int[TS * TS];
        float[] n = noise(r, 0.25f);
        for (int y = 0; y < TS; y++) for (int x = 0; x < TS; x++) {
            boolean seam = x % 32 < 1 || y % 32 < 1;
            boolean rivet = (x % 32 == 5 || x % 32 == 27) && (y % 32 == 5 || y % 32 == 27);
            float s = n[y * TS + x];
            int c;
            if (rivet) c = rgb(176, 186, 198);
            else if (seam) c = rgb(74, 82, 94);
            else c = rgb((int) (118 + s * 34), (int) (130 + s * 34), (int) (146 + s * 30));
            p[y * TS + x] = c;
        }
        scratch(p, r, 4, 0xFF59636F);
        return p;
    }
    static int[] net(Random r) {
        int[] p = new int[TS * TS];
        for (int y = 0; y < TS; y++) for (int x = 0; x < TS; x++) {
            int c = rgb(52, 58, 46);
            p[y * TS + x] = c;
        }
        for (int i = 0; i < 26; i++) blob(p, r.nextInt(TS), r.nextInt(TS), 4 + r.nextInt(9),
                rgb(60 + r.nextInt(40), 66 + r.nextInt(40), 44 + r.nextInt(30)));
        for (int y = 0; y < TS; y++) for (int x = 0; x < TS; x++)
            if ((x % 8 == 0 || y % 8 == 0) && r.nextBoolean()) p[y * TS + x] = 0xFF2B3126;
        return p;
    }
    static int[] concrete(Random r) {
        int[] p = new int[TS * TS];
        float[] n = noise(r, 0.4f);
        for (int y = 0; y < TS; y++) for (int x = 0; x < TS; x++) {
            float s = n[y * TS + x];
            p[y * TS + x] = rgb((int) (148 + s * 30), (int) (148 + s * 30), (int) (140 + s * 26));
        }
        scratch(p, r, 5, 0xFF7C7C74);
        return p;
    }
    static int[] sandyGround(Random r) {
        int[] p = new int[TS * TS];
        float[] n = noise(r, 0.7f);
        for (int y = 0; y < TS; y++) for (int x = 0; x < TS; x++) {
            float s = n[y * TS + x];
            p[y * TS + x] = rgb((int) (178 + s * 26), (int) (156 + s * 24), (int) (118 + s * 20));
        }
        for (int i = 0; i < 60; i++) {
            int x = r.nextInt(TS), y = r.nextInt(TS);
            p[y * TS + x] = 0xFF8E7A56;
        }
        return p;
    }
    static int[] tileGround(Random r) {
        int[] p = new int[TS * TS];
        float[] n = noise(r, 0.3f);
        for (int y = 0; y < TS; y++) for (int x = 0; x < TS; x++) {
            boolean grout = x % 32 < 2 || y % 32 < 2;
            float s = n[y * TS + x];
            p[y * TS + x] = grout ? rgb(104, 104, 100)
                    : rgb((int) (136 + s * 22), (int) (134 + s * 22), (int) (126 + s * 20));
        }
        return p;
    }

    // ---------- 精灵 ----------
    static int[] soldierPix(int team, int frame) {
        Bitmap b = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int uniform = team == 0 ? 0xFF33507A : 0xFF8E6034;
        int vest = team == 0 ? 0xFF22354F : 0xFF6A4526;
        int helm = team == 0 ? 0xFF1C2C42 : 0xFFA03B2C;
        int skin = 0xFFD9A97C;
        int boot = 0xFF26262A;
        p.setColor(0x40000000);
        c.drawOval(new RectF(9, SH - 11, SW - 9, SH - 2), p);
        if (frame == 5) {
            p.setColor(uniform);
            c.drawRoundRect(new RectF(6, SH - 26, SW - 8, SH - 9), 7, 7, p);
            p.setColor(skin); c.drawCircle(11, SH - 24, 6, p);
            p.setColor(boot); c.drawRect(new RectF(SW - 22, SH - 16, SW - 6, SH - 10), p);
            int[] px = new int[SW * SH]; b.getPixels(px, 0, SW, 0, 0, SW, SH); return px;
        }
        float leg = frame == 1 ? -4 : frame == 3 ? 4 : 0;
        // 腿
        p.setColor(uniform);
        c.drawRect(new RectF(18, 44, 24, SH - 12 + leg), p);
        c.drawRect(new RectF(25, 44, 31, SH - 12 - leg), p);
        p.setColor(boot);
        c.drawRect(new RectF(17, SH - 14 + leg, 25, SH - 9 + leg), p);
        c.drawRect(new RectF(24, SH - 14 - leg, 32, SH - 9 - leg), p);
        // 躯干 + 战术背心
        p.setColor(uniform);
        c.drawRoundRect(new RectF(14, 24, 35, 47), 4, 4, p);
        p.setColor(vest);
        c.drawRoundRect(new RectF(16, 26, 33, 42), 3, 3, p);
        p.setColor(0xFF1F1F22);
        c.drawRect(new RectF(16, 33, 33, 35), p);
        // 背包
        p.setColor(0xFF3A3A34);
        c.drawRoundRect(new RectF(11, 27, 16, 40), 2, 2, p);
        // 头 + 头盔
        p.setColor(skin); c.drawCircle(24, 17, 8, p);
        p.setColor(helm);
        c.drawArc(new RectF(15, 7, 33, 25), 180, 180, true, p);
        c.drawRect(new RectF(15, 14, 33, 17), p);
        p.setColor(0xFF101014);
        c.drawRect(new RectF(19, 15, 29, 17), p);   // 护目镜
        // 手臂 + 步枪（持枪指向玩家）
        p.setColor(uniform);
        c.drawRect(new RectF(33, 28, 41, 33), p);
        c.drawRect(new RectF(9, 30, 16, 34), p);
        p.setColor(0xFF2A2A2E);
        c.drawRect(new RectF(6, 27, 44, 30), p);
        c.drawRect(new RectF(20, 30, 24, 36), p);
        if (frame == 4) {
            p.setColor(0xFFFFE08A);
            c.drawCircle(6, 28, 6, p);
            p.setColor(0xFFFFF6C8);
            c.drawCircle(6, 28, 3, p);
        }
        int[] px = new int[SW * SH];
        b.getPixels(px, 0, SW, 0, 0, SW, SH);
        return px;
    }

    static int[] letter(char ch) {
        Bitmap b = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x66FF3020);
        c.drawCircle(24, 24, 22, p);
        p.setColor(0xCCFF4A32);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        c.drawCircle(24, 24, 21, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(26);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        c.drawText(String.valueOf(ch), 24, 33, p);
        int[] px = new int[48 * 48];
        b.getPixels(px, 0, 48, 0, 0, 48, 48);
        return px;
    }

    static int[] c4() {
        Bitmap b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF3A4030);
        c.drawRoundRect(new RectF(3, 8, 21, 20), 3, 3, p);
        p.setColor(0xFFD8B050);
        c.drawRect(new RectF(6, 10, 18, 13), p);
        p.setColor(0xFFFF3020);
        c.drawCircle(17, 17, 2, p);
        p.setColor(0xFF20201E);
        c.drawLine(12, 8, 6, 2, p);
        int[] px = new int[24 * 24];
        b.getPixels(px, 0, 24, 0, 0, 24, 24);
        return px;
    }

    // ---------- 噪声 ----------
    static float[] noise(Random r, float smooth) {
        float[] a = new float[TS * TS];
        for (int i = 0; i < a.length; i++) a[i] = r.nextFloat();
        int radius = smooth > 0.6f ? 1 : 2;
        float[] b = new float[a.length];
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < TS; y++) for (int x = 0; x < TS; x++) {
                float s = 0; int n = 0;
                for (int dy = -radius; dy <= radius; dy++) for (int dx = -radius; dx <= radius; dx++) {
                    int xx = (x + dx + TS) % TS, yy = (y + dy + TS) % TS;
                    s += a[yy * TS + xx]; n++;
                }
                b[y * TS + x] = s / n;
            }
            System.arraycopy(b, 0, a, 0, a.length);
        }
        return a;
    }
    static void scratch(int[] p, Random r, int n, int col) {
        for (int i = 0; i < n; i++) {
            int x = r.nextInt(TS), y = r.nextInt(TS), len = 6 + r.nextInt(22);
            boolean hor = r.nextBoolean();
            for (int k = 0; k < len; k++) {
                int xx = hor ? x + k : x, yy = hor ? y : y + k;
                if (xx < TS && yy < TS) p[yy * TS + xx] = col;
            }
        }
    }
    static void blob(int[] p, int cx, int cy, int rad, int col) {
        for (int y = Math.max(0, cy - rad); y < Math.min(TS, cy + rad); y++)
            for (int x = Math.max(0, cx - rad); x < Math.min(TS, cx + rad); x++)
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) < rad * rad) p[y * TS + x] = col;
    }
}
