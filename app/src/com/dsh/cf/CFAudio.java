package com.dsh.cf;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.util.Random;

/**
 * 音效全部在内存里合成（22.05kHz / 单声道 / 16bit），apk 里一个音频文件都不放。
 * SoundPool 装载失败就整体静音，游戏照跑。
 */
public final class CFAudio {

    public static final int AK = 0, AWM = 1, PISTOL = 2, HIT = 3, DIE = 4, PLANT = 5,
            DEFUSE = 6, WIN = 7, LOSE = 8, KNIFE = 9, RELOAD = 10, PICK = 11;
    private static final int N = 12;

    private static final int SR = 22050;
    private static final float PI = 3.14159265f;

    private SoundPool sp;
    private final int[] id = new int[N];

    @SuppressWarnings("deprecation")
    public void init(Context c) {
        if (sp != null) return;
        try {
            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            sp = new SoundPool.Builder().setMaxStreams(8).setAudioAttributes(aa).build();
            short[][] raw = {gun(120, 1L, 0.42f, 46f, 900f, 300f, 0.18f, true),      // AK
                    gun(300, 7L, 0.16f, 11f, 170f, 70f, 0.42f, true),                // AWM
                    gun(95, 3L, 0.50f, 55f, 1200f, 420f, 0.15f, true),               // 沙鹰
                    gun(90, 11L, 0.12f, 26f, 130f, 80f, 0.75f, false),               // 被打中
                    gun(420, 13L, 0.16f, 6f, 300f, 90f, 0.62f, false),               // 阵亡
                    seq(new int[]{660, 990}, new int[]{0, 90}, new int[]{70, 110}),   // 埋包
                    seq(new int[]{1320, 880, 1320}, new int[]{0, 100, 210}, new int[]{80, 80, 160}), // 拆包
                    seq(new int[]{523, 659, 784, 1046}, new int[]{0, 130, 260, 400}, new int[]{120, 120, 120, 340}), // 胜利
                    seq(new int[]{392, 330, 262}, new int[]{0, 150, 310}, new int[]{140, 140, 380}), // 失败
                    gun(70, 17L, 0.85f, 32f, 2600f, 1400f, 0.10f, false),            // 刺刀
                    gun(60, 19L, 0.90f, 70f, 2000f, 1200f, 0.10f, true),             // 换弹
                    seq(new int[]{1568, 2093}, new int[]{0, 60}, new int[]{60, 120})};   // 捡东西
            // SoundPool 只认文件/URI：先把合成好的 WAV 落到缓存目录再装载
            java.io.File dir = c.getCacheDir();
            String[] nm = {"ak", "awm", "pistol", "hit", "die", "plant", "defuse",
                    "win", "lose", "knife", "reload", "pick"};
            for (int i = 0; i < N && i < raw.length; i++) {
                java.io.File f = new java.io.File(dir, "cfsfx_" + nm[i] + ".wav");
                java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                fo.write(wav(raw[i]));
                fo.flush();
                fo.close();
                id[i] = sp.load(f.getAbsolutePath(), 1);
            }
        } catch (Throwable t) {
            sp = null;
        }
    }

    public void play(int which, float vol) {
        if (sp == null || which < 0 || which >= N || id[which] == 0) return;
        if (vol > 1f) vol = 1f;
        if (vol < 0.06f) return;
        try { sp.play(id[which], vol, vol, 5, 0, 1f); } catch (Throwable ignored) { }
    }

    public void release() {
        if (sp != null) { try { sp.release(); } catch (Throwable ignored) { } sp = null; }
    }

    /* ---------------- 合成 ---------------- */

    /** 噪声 + 下滑正弦的混合冲击声：枪声、闷响、破片。 */
    static short[] gun(int ms, long seed, float lpK, float decay, float f0, float f1,
                       float toneAmt, boolean click) {
        int n = SR * ms / 1000;
        short[] s = new short[n];
        Random r = new Random(seed);
        float lpv = 0f, ph = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) SR;
            float e = (float) Math.exp(-t * decay);
            float nz = r.nextFloat() * 2f - 1f;
            lpv += (nz - lpv) * lpK;
            float f = f0 + (f1 - f0) * Math.min(1f, t * 6f);
            ph += 2f * PI * f / SR;
            float w = lpv * (1f - toneAmt) + (float) Math.sin(ph) * toneAmt;
            if (click && (i % 17 == 0)) w += (r.nextFloat() - 0.5f) * 0.35f * (float) Math.exp(-t * 90f);
            int v = (int) (w * e * 24000f);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            s[i] = (short) v;
        }
        return s;
    }

    /** 方波小音符序列：埋包/拆包/胜负提示。 */
    static short[] seq(int[] f, int[] at, int[] len) {
        int total = 0;
        for (int i = 0; i < f.length; i++) total = Math.max(total, at[i] + len[i]);
        int n = SR * total / 1000;
        short[] s = new short[n];
        for (int i = 0; i < f.length; i++) {
            int a = SR * at[i] / 1000;
            int b = Math.min(n, SR * (at[i] + len[i]) / 1000);
            float ph = 0f;
            for (int k = a; k < b; k++) {
                float t = (k - a) / (float) SR;
                float e = Math.min(1f, Math.min(t * 70f, (b - k) / (float) SR * 22f));
                if (e < 0f) e = 0f;
                ph += 2f * PI * f[i] / SR;
                int v = s[k] + (int) (Math.sin(ph) * e * 16000f);
                if (v > 32767) v = 32767;
                if (v < -32768) v = -32768;
                s[k] = (short) v;
            }
        }
        return s;
    }

    /* ---------------- WAV 封装 ---------------- */

    static byte[] wav(short[] s) {
        int n = s.length * 2;
        byte[] o = new byte[44 + n];
        str(o, 0, "RIFF");
        u32(o, 4, 36 + n);
        str(o, 8, "WAVE");
        str(o, 12, "fmt ");
        u32(o, 16, 16);
        u16(o, 20, 1);            // PCM
        u16(o, 22, 1);            // 单声道
        u32(o, 24, SR);
        u32(o, 28, SR * 2);
        u16(o, 32, 2);
        u16(o, 34, 16);
        str(o, 36, "data");
        u32(o, 40, n);
        for (int i = 0; i < s.length; i++) {
            o[44 + i * 2] = (byte) (s[i] & 0xFF);
            o[45 + i * 2] = (byte) ((s[i] >> 8) & 0xFF);
        }
        return o;
    }

    static void str(byte[] b, int p, String s) {
        for (int i = 0; i < s.length(); i++) b[p + i] = (byte) s.charAt(i);
    }

    static void u32(byte[] b, int p, int v) {
        b[p] = (byte) (v & 0xFF);
        b[p + 1] = (byte) ((v >> 8) & 0xFF);
        b[p + 2] = (byte) ((v >> 16) & 0xFF);
        b[p + 3] = (byte) ((v >> 24) & 0xFF);
    }

    static void u16(byte[] b, int p, int v) {
        b[p] = (byte) (v & 0xFF);
        b[p + 1] = (byte) ((v >> 8) & 0xFF);
    }
}
