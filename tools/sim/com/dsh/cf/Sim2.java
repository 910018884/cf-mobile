package com.dsh.cf;

/** 炸弹流程专项自测：玩家埋包 → 爆炸；AI 埋包 → 玩家拆包。 */
public class Sim2 {
    static int freeInSite(int[] r) {
        for (int y = r[1]; y <= r[3]; y++)
            for (int x = r[0]; x <= r[2]; x++)
                if (CFMap.free(x, y)) return x * 1000 + y;
        return -1;
    }

    static int alive(CFGame g, int team) {
        int n = 0;
        for (int i = 0; i < g.units.size(); i++) if (g.units.get(i).alive && g.units.get(i).team == team) n++;
        return n;
    }

    static void keep(CFGame.Unit u) { if (!u.alive) u.alive = true; u.hp = 500f; }

    static void put(CFGame.Unit u, float x, float y) { u.x = x; u.y = y; }

    public static void main(String[] a) {
        CFGame g = new CFGame();
        g.audio = null;
        g.start(CFGame.BP);

        /* --- A: 第 1 局玩家当潜伏者，抱着 C4 埋包，然后等它炸 --- */
        System.out.println("[A] 第1局 玩家进攻=" + g.playerAttack + " 携带=" + (g.carrier == null ? "-" : g.carrier.name)
                + " 包点=" + (g.site == 0 ? "A" : "B"));
        g.freeze = 0f;
        int cell = freeInSite(g.site == 0 ? CFMap.SITE_A : CFMap.SITE_B);
        CFGame.Unit pl = g.player;
        put(pl, cell / 1000 + 0.5f, cell % 1000 + 0.5f);
        g.carrier = pl;
        g.inUse = true;
        int n = 0;
        while (!g.planted && n++ < 300) { keep(pl); g.update(1f / 60f); }
        System.out.println("[A] 埋包=" + g.planted + " 耗时=" + (n / 60f) + "s 坐标=(" + (int) g.bombX + "," + (int) g.bombY
                + ") 读秒=" + (int) g.bombT + "s");
        g.inUse = false;
        n = 0;
        String why = "";
        while (g.state == CFGame.ST_PLAY && n++ < 3000) {
            keep(pl);
            g.update(1f / 60f);
            if (g.state != CFGame.ST_PLAY) why = g.sub;
        }
        System.out.println("[A] 回合结束 → " + g.banner + " | " + why + " | 埋包残留=" + g.planted
                + " | 玩家存活=" + (g.player.hp > 0f));

        /* --- B: 下一局玩家当保卫者，让 AI 进攻方自己埋包，再由玩家拆包 --- */
        n = 0;
        while (g.state != CFGame.ST_PLAY && n++ < 600) g.update(1f / 60f);
        System.out.println("[B] 第" + g.round + "局 玩家进攻=" + g.playerAttack + " 携带="
                + (g.carrier == null ? "-" : g.carrier.name));
        g.freeze = 0f;
        g.inUse = false;
        cell = freeInSite(g.site == 0 ? CFMap.SITE_A : CFMap.SITE_B);
        if (g.carrier != null) put(g.carrier, cell / 1000 + 0.5f, cell % 1000 + 0.5f);
        n = 0;
        while (!g.planted && n++ < 900) {
            keep(pl);
            g.update(1f / 60f);
            if (n % 120 == 0) {
                CFGame.Unit c = g.carrier;
                int[] R = g.site == 0 ? CFMap.SITE_A : CFMap.SITE_B;
                System.out.println("   t=" + (n / 60) + "s 携带=" + (c == null ? "-" : c.name)
                        + (c == null ? "" : " 存活=" + c.alive + " 位置=(" + (int) c.x + "," + (int) c.y + ")"
                                + " 在包点=" + CFMap.inSite(R, c.x, c.y))
                        + " 掉包=" + g.dropC4 + " 埋包进度=" + (int) (g.plantProg * 10) / 10f
                        + " 我方存活=" + alive(g, CFGame.BLUE) + " 对方存活=" + alive(g, CFGame.RED));
            }
        }
        System.out.println("[B] AI 埋包=" + g.planted + " 耗时=" + (n / 60f) + "s");
        pl = g.player;
        put(pl, g.bombX, g.bombY);
        g.inUse = g.canDefuse(pl);
        n = 0;
        while (g.planted && g.state == CFGame.ST_PLAY && n++ < 900) {
            keep(pl);
            put(pl, g.bombX, g.bombY);
            g.inUse = g.canDefuse(pl);
            g.update(1f / 60f);
        }
        System.out.println("[B] 拆包后 planted=" + g.planted + " 耗时=" + (n / 60f) + "s → " + g.banner + " | " + g.sub);
        System.out.println("总比分 " + g.winMine + " : " + g.winTheir + " 状态=" + g.state);
    }
}
