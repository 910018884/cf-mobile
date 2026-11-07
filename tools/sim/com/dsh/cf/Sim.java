package com.dsh.cf;

/** 无头模拟：只跑逻辑层，验证不会炸、能打死人、爆破能走完流程。 */
public class Sim {
    public static void main(String[] a) {
        for (int mode = 0; mode < 2; mode++) run(mode);
    }

    static void run(int mode) {
        long t0 = System.currentTimeMillis();
        CFGame g = new CFGame();
        g.audio = null;
        g.start(mode);
        int steps = 0, max = 60 * 900;
        String err = null;
        try {
            while (g.state != CFGame.ST_OVER && steps < max) {
                drive(g);
                String before = g.banner;
                g.update(1f / 60f);
                steps++;
                if (!g.banner.equals(before) && mode == CFGame.BP && g.banner.indexOf("胜利") > 0) {
                    System.out.println("   第" + g.round + "局 → " + g.banner + " | " + g.sub + " | 埋包=" + g.planted);
                }
                if (mode == CFGame.TDM && steps % 600 == 0) {
                    CFGame.Unit pl = g.player;
                    System.out.println("   玩家 x=" + (int) (pl.x * 10) / 10f + " y=" + (int) (pl.y * 10) / 10f
                            + " hp=" + (int) pl.hp + " 弹" + pl.mag + "/" + pl.pack + " 射程内敌=" + nearDist(g));
                }
                if (mode == CFGame.TDM && steps % 1800 == 0) {
                    System.out.println("   t=" + (int) g.t + "s 击杀 " + g.killsMine + ":" + g.killsTheir
                            + " 存活 " + g.aliveMine() + ":" + g.aliveFoe() + " 子弹 " + g.bullets.size());
                }
                for (int i = 0; i < g.units.size(); i++) {
                    CFGame.Unit u = g.units.get(i);
                    if (Float.isNaN(u.x) || Float.isNaN(u.y) || Float.isNaN(u.aim)) throw new RuntimeException("NaN 单位 " + u.name);
                }
            }
        } catch (Throwable t) {
            err = t.toString();
            t.printStackTrace();
        }
        System.out.println("== " + (mode == CFGame.TDM ? "团队竞技" : "爆破") + " ==");
        System.out.println("  结束状态=" + g.state + " 步数=" + steps + " 游戏内时间=" + (int) g.t + "s 耗时=" + (System.currentTimeMillis() - t0) + "ms");
        System.out.println("  比分 我方" + (mode == CFGame.TDM ? g.killsMine : g.winMine) + " : " + (mode == CFGame.TDM ? g.killsTheir : g.winTheir) + " 敌方  局数=" + g.round);
        System.out.println("  玩家 击杀" + g.pKills + " 死亡" + g.pDeaths + " 爆头" + g.headshots + " 命中" + g.hits + "/" + g.shots + " = " + (int) g.accuracy() + "%");
        System.out.println("  存活 我" + g.aliveMine() + " 敌" + g.aliveFoe() + " 埋包=" + g.planted + " 携带=" + (g.carrier == null ? "-" : g.carrier.name));
        System.out.println("  结算: " + g.banner + " / " + g.sub + (err == null ? "" : "  异常:" + err));
    }

    static String nearDist(CFGame g) {
        float bd = 1e9f;
        boolean los = false;
        for (int i = 0; i < g.units.size(); i++) {
            CFGame.Unit o = g.units.get(i);
            if (!o.alive || o.team == g.player.team) continue;
            float d = (float) Math.hypot(o.x - g.player.x, o.y - g.player.y);
            if (d < bd) { bd = d; los = CFMap.los(g.player.x, g.player.y, o.x, o.y); }
        }
        return (int) (bd * 10) / 10f + (los ? "(有视线)" : "(无视线)");
    }

    static void drive(CFGame g) {
        CFGame.Unit u = g.player;
        if (u == null) return;
        CFGame.Unit tgt = null;
        float bd = 1e9f;
        for (int i = 0; i < g.units.size(); i++) {
            CFGame.Unit o = g.units.get(i);
            if (!o.alive || o.team == u.team) continue;
            float d = (float) Math.hypot(o.x - u.x, o.y - u.y);
            if (d < bd && CFMap.los(u.x, u.y, o.x, o.y)) { bd = d; tgt = o; }
        }
        float tx, ty;
        if (tgt != null) { tx = tgt.x; ty = tgt.y; }
        else if (g.planted) { tx = g.bombX; ty = g.bombY; }
        else { tx = g.site == 0 ? 33 : 9; ty = g.site == 0 ? 27 : 8; }
        // 像真人一样沿流场绕行，而不是直直撞墙
        float mx = 0f, my = 0f;
        int cx = (int) u.x, cy = (int) u.y;
        int ti = Math.max(0, Math.min(CFMap.W - 1, (int) tx));
        int tj = Math.max(0, Math.min(CFMap.H - 1, (int) ty));
        int[] fl = CFMap.flow(ti, tj);
        if (fl != null) {
            int best = -1; int bd2 = Integer.MAX_VALUE;
            int[][] d4 = {{1,0},{-1,0},{0,1},{0,-1}};
            for (int k = 0; k < 4; k++) {
                int nx = cx + d4[k][0], ny = cy + d4[k][1];
                if (nx < 0 || ny < 0 || nx >= CFMap.W || ny >= CFMap.H) continue;
                if (!CFMap.free(nx, ny)) continue;
                int dv = fl[ny * CFMap.W + nx];
                if (dv >= 0 && dv < bd2) { bd2 = dv; best = k; }
            }
            if (best >= 0) {
                float gx = cx + d4[best][0] + 0.5f, gy = cy + d4[best][1] + 0.5f;
                mx = gx - u.x; my = gy - u.y;
                float l = (float) Math.hypot(mx, my);
                if (l > 0.05f) { mx /= l; my /= l; } else { mx = my = 0f; }
            }
        }
        g.inmx = mx; g.inmy = my;
        g.inAimOn = true;
        g.inFire = tgt != null;
        if (tgt != null) {
            float dx = tgt.x - u.x, dy = tgt.y - u.y;
            g.inaim = (float) Math.atan2(dy, dx);
        }
        if (u.mag <= 0) g.tapReload = true;
        g.inUse = g.canPlant(u) || g.canDefuse(u);
    }
}
