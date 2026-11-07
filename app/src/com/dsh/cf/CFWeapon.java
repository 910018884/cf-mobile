package com.dsh.cf;

/** 武器数值表，致敬 CF 经典枪。所有单位共用同一张表。 */
public final class CFWeapon {

    public static final int AK = 0, M4 = 1, AWM = 2, DEAGLE = 3, KNIFE = 4;

    public static final String[] NAME = {"AK47", "M4A1", "AWM", "沙鹰", "军用刺刀"};
    public static final String[] KIND = {"突击步枪", "突击步枪", "狙击枪", "手枪", "近战"};

    /** 弹匣容量，0 表示无限（近战）。 */
    public static final int[] MAG = {30, 30, 10, 7, 0};
    /** 备弹。 */
    public static final int[] PACK = {90, 90, 30, 35, 0};
    /** 两发之间的间隔（秒）。 */
    public static final float[] ROF = {0.098f, 0.086f, 1.42f, 0.26f, 0.42f};
    /** 单发伤害（身体）。 */
    public static final float[] DMG = {34f, 27f, 128f, 62f, 78f};
    /** 基础散布（弧度）。 */
    public static final float[] SPREAD = {0.050f, 0.038f, 0.007f, 0.052f, 0.020f};
    /** 有效射程（格），超出后伤害衰减。 */
    public static final float[] RANGE = {18f, 17f, 34f, 13f, 1.4f};
    /** 弹速（格/秒）。 */
    public static final float[] SPEED = {27f, 28f, 46f, 23f, 0f};
    /** 换弹时间。 */
    public static final float[] RELOAD = {2.15f, 1.95f, 3.30f, 1.45f, 0f};
    /** 持握时的移速倍率。 */
    public static final float[] RUN = {1.00f, 1.02f, 0.80f, 1.06f, 1.14f};
    /** 是否全自动。 */
    public static final boolean[] AUTO = {true, true, false, false, true};
    /** 开火音效 id。 */
    public static final int[] SND = {CFAudio.AK, CFAudio.AK, CFAudio.AWM, CFAudio.PISTOL, CFAudio.KNIFE};

    /** 爆头倍率：AK 这类步枪爆头收益最大，狙击直接秒。 */
    public static final float[] HEAD = {2.6f, 2.6f, 1.35f, 2.4f, 2.2f};

    public static String ammo(int w, int mag, int res) {
        return MAG[w] == 0 ? "∞" : mag + " / " + res;
    }
}
