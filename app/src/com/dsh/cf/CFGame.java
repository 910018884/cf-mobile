package com.dsh.cf;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

/**
 * 掌上火线 —— 逻辑层。
 * 玩法致敬《穿越火线》手游：5v5 团队竞技 + 爆破模式（C4 埋包/拆包），
 * 左摇杆走位，右摇杆瞄准自动开火，地图是「沙漠灰」味道的 44x36 网格。
 * 渲染与触控在 CFAct。
 */
public final class CFGame {

    public static final int ST_MENU = 0, ST_PLAY = 1, ST_ROUND = 2, ST_OVER = 3;
    public static final int TDM = 0, BP = 1;
    public static final int BLUE = 0, RED = 1;
    public static final int TEAM = 5;

    public static final float TDM_TARGET = 30f;      // 先杀 30 人
    public static final float TDM_TIME = 240f;
    public static final float BP_TIME = 115f;
    public static final float BOMB_T = 35f;
    public static final float PLANT_FULL = 2.6f;
    public static final float DEFUSE_BOT = 6.5f;
    public static final float DEFUSE_P = 4.5f;
    public static final float RUN_SPD = 3.6f;        // 格/秒

    // ---- 状态 ----
    public volatile int state = ST_MENU;
    public int mode = TDM;
    public float t;
    public float timeLeft = TDM_TIME;
    public int killsMine, killsTheir;               // 团队竞技比分
    public int winMine, winTheir;                   // 爆破局分
    public int round = 1;
    public String banner = "", sub = "";
    public float bannerT;
    public boolean playerAttack;                    // 本回合玩家是潜伏者
    public boolean win;                             // 结算：玩家是否获胜

    // ---- 输入（由 CFAct 写入）----
    public float inmx, inmy, inaim;
    public boolean inAimOn, inFire;
    public boolean tapReload, tapSwap;
    public boolean inUse;

    // ---- 实体 ----
    public final ArrayList<Unit> units = new ArrayList<Unit>();
    public final ArrayList<Bullet> bullets = new ArrayList<Bullet>();
    public final ArrayList<Particle> parts = new ArrayList<Particle>();
    public final ArrayList<Pickup> picks = new ArrayList<Pickup>();
    public final ArrayList<Msg> msgs = new ArrayList<Msg>();
    public final ArrayList<Floater> floats = new ArrayList<Floater>();
    public Unit player;

    // ---- 爆破 ----
    public boolean planted, dropC4;
    public float bombT, plantProg, defuseProg, freeze;
    public float bombX, bombY;
    public int site;                                 // 0 = A 点, 1 = B 点
    public Unit carrier;
    public int defuserId = -1;

    // ---- 玩家统计 ----
    public int shots, hits, headshots, pKills, pDeaths;
    public float hitMark, damageFlash, shake;
    public int bestKills;

    // ---- 内部 ----
    public CFAudio audio;
    private final Random rnd = new Random(20240808L);
    private int idSeq;
    private float flowCd, pickupCd, streakT, c4Cd;
    private int streak;
    private int atkTeam;
    private int[] fAtk, fDef, fBomb, fSite;

    private static final String[] NAME_BLUE = {"夜玫瑰", "刀锋", "奥摩", "斯沃特", "灵狐者"};
    private static final String[] NAME_RED = {"赛斯", "幻影", "猎狐者", "雷蛇", "白狼"};

    /* ================= 数据结构 ================= */

    public static final class Unit {
        public int id, team, weapon, mag, pack, kills, deaths, head;
        public float x, y, vx, vy, r = 0.34f, hp, aim, face = 1f;
        public float fireCd, reload, prot, respawnT, burstLeft, burstCd, aiHold;
        public float walkAnim, hitFlash, awmT, plantHold, defuseHold;
        int[] botFlow;
        public boolean alive, isPlayer;
        public String name;
        int aiWeapon, strafeDir;
        float strafeT, jitter;
    }

    static final class Bullet {
        float x, y, px, py, vx, vy, dmg, life, r;
        int team, weapon, color, owner;
        boolean dead;
    }

    static final class Particle {
        float x, y, vx, vy, life, max, size;
        int color, kind;   // 0 血 1 火花 2 烟尘 3 弹壳
    }

    static final class Pickup {
        float x, y, life;
        int kind;          // 0 医疗 1 弹药 2 C4 3 AWM 空投
    }

    public static final class Msg {
        String killer, victim, wpn;
        int kteam, vteam;
        boolean head;
        float life;
    }

    static final class Floater {
        float x, y, life;
        int color, kind;   // 0 伤害 1 爆头 2 提示
        String text;
    }

    /* ================= 开局 ================= */

    public void start(int m) {
        mode = m;
        killsMine = killsTheir = winMine = winTheir = 0;
        round = 1;
        shots = hits = headshots = pKills = pDeaths = 0;
        streak = 0;
        win = false;
        banner = "";
        startRound();
        state = ST_PLAY;
    }

    public void startRound() {
        units.clear();
        bullets.clear();
        parts.clear();
        picks.clear();
        floats.clear();
        planted = false;
        dropC4 = false;
        bombT = BOMB_T;
        plantProg = defuseProg = 0f;
        carrier = null;
        defuserId = -1;
        c4Cd = 0f;
        site = rnd.nextBoolean() ? 0 : 1;
        freeze = mode == BP ? 2.4f : 1.1f;
        timeLeft = mode == TDM ? TDM_TIME : BP_TIME;
        atkTeam = (mode == TDM) ? RED : ((round % 2 == 1) ? BLUE : RED);
        playerAttack = (mode == BP) && (atkTeam == BLUE);   // 玩家固定保卫者阵营 BLUE

        player = make(BLUE, true, 0);
        player.weapon = CFWeapon.AK;
        player.mag = CFWeapon.MAG[CFWeapon.AK];
        player.pack = CFWeapon.PACK[CFWeapon.AK];
        player.name = "你";
        units.add(player);
        for (int i = 1; i < TEAM; i++) units.add(make(BLUE, false, i));
        for (int i = 0; i < TEAM; i++) units.add(make(RED, false, i));

        if (mode == BP) {
            carrier = (atkTeam == player.team) ? player : null;
            if (carrier == null) {
                for (int i = units.size() - 1; i >= 0; i--) {
                    Unit c = units.get(i);
                    if (c.team == atkTeam && !c.isPlayer) { carrier = c; break; }
                }
            }
        }
        refreshFlows();
        flowCd = 0.8f;
        pickupCd = 12f;
        banner = mode == BP ? (playerAttack ? "潜伏者 · 进攻" : "保卫者 · 防守") : "团队竞技";
        sub = mode == BP ? ("本回合 " + (playerAttack ? "A/B 埋包" : "守 A/B 点，保护队友") + " · 第 " + round + " 局")
                : "先杀 30 人 · 4 分钟";
        bannerT = 2.0f;
    }

    private Unit make(int team, boolean isPlayer, int idx) {
        Unit u = new Unit();
        u.id = idSeq++;
        u.team = team;
        u.isPlayer = isPlayer;
        u.name = isPlayer ? "你" : (team == BLUE ? NAME_BLUE[idx % NAME_BLUE.length] : NAME_RED[idx % NAME_RED.length]);
        int[] sp = CFMap.spawn(team, rnd);
        u.x = sp[0] + 0.5f + (rnd.nextFloat() - 0.5f) * 0.4f;
        u.y = sp[1] + 0.5f + (rnd.nextFloat() - 0.5f) * 0.4f;
        if (!CFMap.passable(u.x, u.y, u.r)) {
            u.x = sp[0] + 0.5f;
            u.y = sp[1] + 0.5f;
        }
        u.hp = 100f;
        u.alive = true;
        u.prot = 1.1f;
        u.aim = team == BLUE ? 0.7f : 3.9f;
        u.aiWeapon = botWeapon();
        u.weapon = u.aiWeapon;
        u.mag = CFWeapon.MAG[u.weapon];
        u.pack = CFWeapon.PACK[u.weapon];
        u.burstCd = 0.3f + rnd.nextFloat() * 0.6f;
        u.aiHold = 0f;
        return u;
    }

    private int botWeapon() {
        int p = rnd.nextInt(100);
        if (p < 55) return CFWeapon.AK;
        if (p < 82) return CFWeapon.M4;
        if (p < 92) return CFWeapon.DEAGLE;
        return CFWeapon.AWM;
    }

    /* ================= 主更新 ================= */

    public void update(float dt) {
        t += dt;
        if (hitMark > 0) hitMark -= dt * 3f;
        if (damageFlash > 0) damageFlash -= dt * 2.2f;
        if (shake > 0) shake -= dt * 2.4f;
        if (bannerT > 0) bannerT -= dt;
        if (streakT > 0) { streakT -= dt; if (streakT <= 0) streak = 0; }

        decayMsgs(dt);
        decayFloats(dt);
        moveParts(dt);

        if (state == ST_ROUND) {
            if (bannerT <= 0f) nextRound();
            return;
        }
        if (state != ST_PLAY) return;

        timeLeft -= dt;
        if (freeze > 0f) freeze -= dt;

        flowCd -= dt;
        if (flowCd <= 0f) { flowCd = 0.85f; refreshFlows(); }

        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            if (u.alive) {
                if (u.isPlayer) playerCtrl(u, dt); else botCtrl(u, dt);
                u.fireCd -= dt;
                if (u.reload > 0f) {
                    u.reload -= dt;
                    if (u.reload <= 0f) finishReload(u);
                }
                if (u.prot > 0f) u.prot -= dt;
                if (u.hitFlash > 0f) u.hitFlash -= dt * 5f;
                if (u.awmT > 0f) {
                    u.awmT -= dt;
                    if (u.awmT <= 0f) backToRifle(u);
                }
                moveUnit(u, dt);
            } else if (u.respawnT > 0f) {
                u.respawnT -= dt;
                if (u.respawnT <= 0f && mode == TDM) respawn(u);
            }
        }
        separate();
        moveBullets(dt);
        bombLogic(dt);
        pickLogic(dt);
        camFollow();

        tapReload = false;
        tapSwap = false;
        checkEnd();
    }

    private void decayMsgs(float dt) {
        for (Iterator<Msg> it = msgs.iterator(); it.hasNext(); ) {
            Msg m = it.next();
            m.life -= dt;
            if (m.life <= 0f) it.remove();
        }
    }

    private void decayFloats(float dt) {
        for (Iterator<Floater> it = floats.iterator(); it.hasNext(); ) {
            Floater f = it.next();
            f.life -= dt;
            f.y -= dt * 1.1f;
            if (f.life <= 0f) it.remove();
        }
    }

    private void camFollow() {
        // 相机在 CFAct 里做插值，这里只提供目标
    }

    /* ================= 玩家操作 ================= */

    private void playerCtrl(Unit u, float dt) {
        if (tapSwap) swapWeapon(u);
        if (tapReload) startReload(u);

        float spd = RUN_SPD * CFWeapon.RUN[u.weapon];
        u.vx = inmx * spd;
        u.vy = inmy * spd;
        if (inAimOn) {
            u.aim = inaim;
            u.face = (float) Math.cos(inaim) >= 0f ? 1f : -1f;
        }
        u.walkAnim += (Math.abs(u.vx) + Math.abs(u.vy)) > 0.2f ? dt * 8f : 0f;

        // 埋包 / 拆包
        boolean use = inUse && freeze <= 0f;
        if (canPlant(u) && use) {
            plantProg += dt;
            u.plantHold = 1f;
            if (plantProg >= PLANT_FULL) doPlant(u);
        } else if (canDefuse(u) && use) {
            defuseProg += dt * (DEFUSE_BOT / DEFUSE_P);   // 玩家手快
            u.defuseHold = 1f;
            if (defuseProg >= DEFUSE_BOT) doDefuse();
        } else {
            // 只有抱着 C4 的玩家松手才让埋包进度回落；只有守包的玩家离包才让拆包进度回落
            if (u == carrier) plantProg = Math.max(0f, plantProg - dt * 1.6f);
            else if (planted && u.team != atkTeam) defuseProg = Math.max(0f, defuseProg - dt * 1.2f);
            u.plantHold = 0f;
            u.defuseHold = 0f;
        }

        // 开火
        if (u.reload > 0f) return;
        if (u.mag <= 0) {
            if (u.pack > 0) startReload(u);
            else if (u.weapon != CFWeapon.DEAGLE) { u.weapon = CFWeapon.DEAGLE; u.mag = CFWeapon.MAG[CFWeapon.DEAGLE]; u.pack = CFWeapon.PACK[CFWeapon.DEAGLE]; ding("换沙鹰", 2); }
            return;
        }
        if (inFire && freeze <= 0f) {
            if (u.fireCd <= 0f) {
                if (CFWeapon.AUTO[u.weapon]) {
                    shots++;
                    fire(u, u.aim);
                } else if (!fireLatch) {
                    fireLatch = true;
                    shots++;
                    fire(u, u.aim);
                }
            }
        } else {
            fireLatch = false;
        }
    }

    private boolean fireLatch;

    /* ================= AI ================= */

    private void botCtrl(Unit u, float dt) {
        Unit tgt = bestTarget(u);
        float spd = RUN_SPD * CFWeapon.RUN[u.weapon] * 0.94f;
        if (tgt != null) {
            float d = dist(u, tgt);
            float lead = 0.13f * d;
            float ang = (float) Math.atan2(tgt.y + tgt.vy * lead - u.y, tgt.x + tgt.vx * lead - u.x);
            u.aim = ang;
            u.face = (float) Math.cos(ang) >= 0f ? 1f : -1f;
            u.walkAnim += dt * 6f;
            botShoot(u, tgt, d, dt);
            // 走位：远了就压上，近了侧身
            float range = CFWeapon.RANGE[u.weapon];
            u.strafeT -= dt;
            if (u.strafeT <= 0f) { u.strafeT = 0.7f + rnd.nextFloat() * 1.2f; u.strafeDir = rnd.nextBoolean() ? 1 : -1; }
            float toward = d > range * 0.45f ? 1f : (d < range * 0.2f ? -0.7f : 0f);
            float ax = (float) Math.cos(ang), ay = (float) Math.sin(ang);
            float sx = -ay * u.strafeDir, sy = ax * u.strafeDir;
            u.vx = (ax * toward + sx * 0.62f) * spd;
            u.vy = (ay * toward + sy * 0.62f) * spd;
        } else {
            followFlow(u, objectiveFlow(u), dt, spd);
        }
    }

    private int[] objectiveFlow(Unit u) {
        if (mode == TDM) return u.botFlow != null ? u.botFlow : fSite;
        if (planted) {
            boolean def = u.team != atkTeam;
            if (def) return u.id == defuserId ? fBomb : fSite;
            return fSite;
        }
        if (dropC4) return u.team == atkTeam ? fSite : fSite;
        return u.team == atkTeam ? fSite : fDef;
    }

    private Unit bestTarget(Unit u) {
        Unit best = null;
        float bd = 1e9f;
        for (int i = 0; i < units.size(); i++) {
            Unit o = units.get(i);
            if (!o.alive || o.team == u.team) continue;
            if (o.prot > 0f) continue;
            float d = dist(u, o);
            float rng = CFWeapon.RANGE[u.weapon] * (u.weapon == CFWeapon.AWM ? 1.1f : 0.95f);
            if (d > rng) continue;
            if (!CFMap.los(u.x, u.y, o.x, o.y)) continue;
            if (d < bd) { bd = d; best = o; }
        }
        return best;
    }

    private void botShoot(Unit u, Unit tgt, float d, float dt) {
        if (freeze > 0f) return;
        if (u.reload > 0f) return;
        if (u.mag <= 0) { startReload(u); return; }
        u.burstCd -= dt;
        if (u.burstLeft > 0) {
            if (u.burstCd <= 0f) {
                float sp = CFWeapon.SPREAD[u.weapon] + 0.012f + 0.0035f * d;
                fire(u, u.aim + (rnd.nextFloat() - 0.5f) * sp);
                u.burstLeft--;
                u.burstCd = CFWeapon.ROF[u.weapon] * (1.15f + rnd.nextFloat() * 0.75f);
            }
        } else if (u.burstCd <= 0f) {
            u.burstLeft = u.weapon == CFWeapon.AWM ? 1 : 3 + rnd.nextInt(4);
            u.burstCd = 0.10f + rnd.nextFloat() * 0.40f;
        }
    }

    private void followFlow(Unit u, int[] flow, float dt, float spd) {
        int cx = (int) Math.floor(u.x), cy = (int) Math.floor(u.y);
        if (cx < 0) cx = 0;
        if (cy < 0) cy = 0;
        if (cx >= CFMap.W) cx = CFMap.W - 1;
        if (cy >= CFMap.H) cy = CFMap.H - 1;
        int bx = cx, by = cy;
        float bd = 1e9f;
        if (flow != null) {
            int cd = flow[cy * CFMap.W + cx];
            bd = cd < 0 ? 1e9f : cd;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = cx + dx, ny = cy + dy;
                    if (!CFMap.free(nx, ny)) continue;
                    if (dx != 0 && dy != 0 && (!CFMap.free(cx + dx, cy) || !CFMap.free(cx, cy + dy))) continue;
                    int v = flow[ny * CFMap.W + nx];
                    if (v < 0) continue;
                    float cand = v + (dx != 0 && dy != 0 ? 0.42f : 0f);
                    if (cand < bd) { bd = cand; bx = nx; by = ny; }
                }
            }
        }
        float tx = bx + 0.5f, ty = by + 0.5f;
        float dx = tx - u.x, dy = ty - u.y;
        float l = (float) Math.hypot(dx, dy);
        if (l < 1e-4f || (bx == cx && by == cy)) {
            // 目标就在脚下：小范围游荡，别叠在一起
            dx = (float) Math.cos(u.jitter);
            dy = (float) Math.sin(u.jitter);
            l = 1f;
        }
        u.jitter += dt * 1.7f + 0.02f;
        float jx = (float) Math.cos(u.jitter) * 0.22f, jy = (float) Math.sin(u.jitter * 1.3f) * 0.22f;
        u.vx = (dx / l + jx) * spd;
        u.vy = (dy / l + jy) * spd;
        u.walkAnim += dt * 8f;
        u.aim = (float) Math.atan2(u.vy, u.vx);
        u.face = u.vx >= 0f ? 1f : -1f;
    }

    private void refreshFlows() {
        int[] sC = siteCenter(site);
        fSite = CFMap.flow(sC[0], sC[1]);
        if (mode == TDM) {
            fAtk = fSite;
            fDef = CFMap.flow(22, 18);
        } else {
            fAtk = fSite;
            // 防守方守点：目标取包点外侧靠己方一侧
            fDef = CFMap.flow(site == 0 ? 26 : 14, site == 0 ? 20 : 18);
        }
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            if (!u.alive || u.isPlayer) continue;
            Unit e = nearestFoeAny(u);
            if (e != null) u.botFlow = CFMap.flow((int) e.x, (int) e.y);
        }
        if (planted) {
            fBomb = CFMap.flow((int) bombX, (int) bombY);
            Unit best = null;
            float bd = 1e9f;
            for (int i = 0; i < units.size(); i++) {
                Unit u = units.get(i);
                if (!u.alive || u.isPlayer || u.team == atkTeam) continue;
                float d = dist(u.x, u.y, bombX, bombY);
                if (d < bd) { bd = d; best = u; }
            }
            defuserId = best == null ? -1 : best.id;
        } else {
            fBomb = null;
            defuserId = -1;
        }
    }

    private int[] siteCenter(int s) {
        return s == 0 ? new int[]{33, 27} : new int[]{9, 8};
    }

    /* ================= 枪 ================= */

    private void fire(Unit u, float ang) {
        int w = u.weapon;
        u.fireCd = CFWeapon.ROF[w] * (u.isPlayer ? 1f : 1.05f + rnd.nextFloat() * 0.25f);
        if (CFWeapon.MAG[w] > 0) u.mag--;
        float mx = u.x + (float) Math.cos(ang) * 0.52f;
        float my = u.y + (float) Math.sin(ang) * 0.52f;
        sparge(mx, my, 4, 0xFFFFD27A, 1);          // 枪口火光
        shell(u.x, u.y);
        if (w == CFWeapon.KNIFE) {
            melee(u, ang);
            snd(CFAudio.KNIFE, u);
            return;
        }
        Bullet b = new Bullet();
        b.x = b.px = mx;
        b.y = b.py = my;
        float sp = CFWeapon.SPEED[w];
        b.vx = (float) Math.cos(ang) * sp;
        b.vy = (float) Math.sin(ang) * sp;
        b.dmg = CFWeapon.DMG[w];
        b.weapon = w;
        b.team = u.team;
        b.life = CFWeapon.RANGE[w] / sp + 0.05f;
        b.color = u.team == BLUE ? 0xFFFFF0B0 : 0xFFFFC890;
        b.r = w == CFWeapon.AWM ? 0.10f : 0.07f;
        b.owner = u.id;
        bullets.add(b);
        snd(CFWeapon.SND[w], u);
        if (u.isPlayer) shake = Math.max(shake, w == CFWeapon.AWM ? 0.42f : 0.16f);
        if (CFWeapon.MAG[w] > 0 && u.mag <= 0 && u.pack > 0 && !u.isPlayer) startReload(u);
    }

    private void melee(Unit u, float ang) {
        float tx = u.x + (float) Math.cos(ang) * 0.7f;
        float ty = u.y + (float) Math.sin(ang) * 0.7f;
        for (int i = 0; i < units.size(); i++) {
            Unit o = units.get(i);
            if (!o.alive || o.team == u.team || o.prot > 0f) continue;
            if (dist(o.x, o.y, tx, ty) < 0.72f) {
                hit(o, CFWeapon.DMG[CFWeapon.KNIFE], u, rnd.nextFloat() < 0.25f);
                break;
            }
        }
    }

    private void moveBullets(float dt) {
        for (Iterator<Bullet> it = bullets.iterator(); it.hasNext(); ) {
            Bullet b = it.next();
            b.life -= dt;
            if (b.life <= 0f) { it.remove(); continue; }
            b.px = b.x;
            b.py = b.y;
            float d = (float) Math.hypot(b.vx, b.vy) * dt;
            int steps = Math.max(1, (int) (d / 0.18f));
            boolean gone = false;
            for (int s = 0; s < steps && !gone; s++) {
                b.x += b.vx * dt / steps;
                b.y += b.vy * dt / steps;
                if (CFMap.solid((int) Math.floor(b.x), (int) Math.floor(b.y))) {
                    sparge(b.x, b.y, 5, 0xFFFFE0A0, 2);
                    gone = true;
                    break;
                }
                for (int i = 0; i < units.size(); i++) {
                    Unit o = units.get(i);
                    if (!o.alive || o.team == b.team || o.prot > 0f) continue;
                    if (dist(o.x, o.y, b.x, b.y) < o.r + b.r) {
                        Unit atk = byId(b.owner);
                        float falloff = 1f;
                        float trav = CFWeapon.RANGE[b.weapon] - b.life * CFWeapon.SPEED[b.weapon];
                        if (trav > CFWeapon.RANGE[b.weapon] * 0.7f) falloff = 0.78f;
                        hit(o, b.dmg * falloff, atk, rnd.nextFloat() < (b.weapon == CFWeapon.AWM ? 0.2f : 0.13f));
                        gone = true;
                        break;
                    }
                }
            }
            if (gone) it.remove();
        }
    }

    private Unit byId(int id) {
        for (int i = 0; i < units.size(); i++) if (units.get(i).id == id) return units.get(i);
        return null;
    }

    private void hit(Unit v, float dmg, Unit atk, boolean head) {
        if (!v.alive) return;
        float real = head ? dmg * CFWeapon.HEAD[atk == null ? CFWeapon.AK : atk.weapon] : dmg;
        v.hp -= real;
        v.hitFlash = 1f;
        sparge(v.x, v.y, head ? 7 : 4, 0xFFB3121F, 0);
        boolean mine = atk != null && atk.isPlayer;
        if (mine) {
            hits++;
            hitMark = 1f;
            floats(v.x, v.y, (head ? "爆头 -" : "-") + (int) real, head ? 1 : 0, 0xFFFFD24A);
            snd(CFAudio.HIT, 0.85f);
        }
        if (v.isPlayer) {
            damageFlash = 1f;
            shake = Math.max(shake, 0.34f);
            if (!mine) snd(CFAudio.HIT, 0.7f);
        }
        if (v.hp <= 0f) kill(v, atk, head);
    }

    private void kill(Unit v, Unit atk, boolean head) {
        v.alive = false;
        v.hp = 0f;
        v.deaths++;
        v.respawnT = v.isPlayer ? 3.2f : 2.6f;
        sparge(v.x, v.y, 16, 0xFFB3121F, 0);
        snd(CFAudio.DIE, v.isPlayer ? 1f : 0.5f);
        Msg m = new Msg();
        m.life = 4.2f;
        m.head = head;
        m.victim = v.name;
        m.vteam = v.team;
        m.wpn = atk == null ? "环境" : CFWeapon.NAME[atk.weapon];
        if (atk != null && atk.team == v.team) m.wpn = "友军";
        m.killer = atk == null ? "?" : atk.name;
        m.kteam = atk == null ? v.team : atk.team;
        msgs.add(m);
        if (atk == null || atk.team == v.team) {
            if (v.isPlayer) { pDeaths++; ding(atk == null ? "被炸倒了" : "注意友军枪线", 2); }
            return;
        }
        atk.kills++;
        if (head) atk.head++;
        if (atk.isPlayer) {
            pKills++;
            streak++;
            streakT = 5f;
            if (streak == 2) floats(v.x, v.y - 0.4f, "双杀！", 2, 0xFFFF7A2F);
            else if (streak == 3) floats(v.x, v.y - 0.4f, "三连杀！", 2, 0xFFFF5A1F);
            else if (streak == 5) floats(v.x, v.y - 0.4f, "ACE · 全歼", 2, 0xFFFF2F6F);
            ding("击杀 " + v.name + (head ? " · 爆头" : ""), 2);
        } else if (v.isPlayer) {
            pDeaths++;
        }
        if (mode == TDM) {
            if (atk.team == player.team) killsMine++; else killsTheir++;
        }
    }

    private void respawn(Unit u) {
        int[] sp = CFMap.spawn(u.team, rnd);
        u.x = sp[0] + 0.5f;
        u.y = sp[1] + 0.5f;
        if (!CFMap.passable(u.x, u.y, u.r)) { u.x = sp[0] + 0.5f; u.y = sp[1] + 0.5f; }
        u.hp = 100f;
        u.alive = true;
        u.prot = 1.1f;
        u.reload = 0f;
        u.vx = u.vy = 0f;
        u.awmT = 0f;
        u.weapon = u.isPlayer ? CFWeapon.AK : u.aiWeapon;
        u.mag = CFWeapon.MAG[u.weapon];
        u.pack = CFWeapon.PACK[u.weapon];
        u.burstLeft = 0f;
        u.respawnT = 0f;
    }

    private void startReload(Unit u) {
        int w = u.weapon;
        if (CFWeapon.MAG[w] == 0 || u.reload > 0f) return;
        if (u.mag >= CFWeapon.MAG[w] || u.pack <= 0) return;
        u.reload = CFWeapon.RELOAD[w];
        u.fireCd = Math.max(u.fireCd, 0.05f);
        snd(CFAudio.RELOAD, u);
    }

    private void finishReload(Unit u) {
        int w = u.weapon;
        int need = CFWeapon.MAG[w] - u.mag;
        int take = Math.min(need, u.pack);
        u.mag += take;
        u.pack -= take;
        u.reload = 0f;
    }

    private void swapWeapon(Unit u) {
        if (u.weapon == CFWeapon.AWM) return;
        int from = u.weapon;
        u.weapon = from == CFWeapon.DEAGLE ? CFWeapon.AK : CFWeapon.DEAGLE;
        u.mag = CFWeapon.MAG[u.weapon];
        u.pack = CFWeapon.PACK[u.weapon];
        u.reload = 0f;
        u.fireCd = 0.35f;
        ding("切枪 " + CFWeapon.NAME[u.weapon], 2);
    }

    private void backToRifle(Unit u) {
        u.weapon = u.isPlayer ? CFWeapon.AK : u.aiWeapon;
        u.mag = CFWeapon.MAG[u.weapon];
        u.pack = CFWeapon.PACK[u.weapon];
        u.reload = 0f;
    }

    /* ================= C4 ================= */

    public boolean canPlant(Unit u) {
        return mode == BP && !planted && u.team == atkTeam && carrier == u && u.alive
                && CFMap.inSite(site == 0 ? CFMap.SITE_A : CFMap.SITE_B, u.x, u.y) && freeze <= 0f;
    }

    public boolean canDefuse(Unit u) {
        return mode == BP && planted && u.team != atkTeam && u.alive
                && dist(u.x, u.y, bombX, bombY) < 1.15f && freeze <= 0f;
    }

    private void bombLogic(float dt) {
        if (mode != BP) return;
        if (!planted) {
            // 携带者阵亡 → 掉包
            if (carrier != null && !carrier.alive) {
                bombX = carrier.x;
                bombY = carrier.y;
                dropC4 = true;
                Pickup p = new Pickup();
                p.x = bombX;
                p.y = bombY;
                p.kind = 2;
                p.life = 999f;
                picks.add(p);
                carrier = null;
                ding("C4 掉落！去捡起来", 2);
            }
            if (dropC4) {
                for (int i = 0; i < units.size(); i++) {
                    Unit u = units.get(i);
                    if (!u.alive || u.team != atkTeam) continue;
                    if (dist(u.x, u.y, bombX, bombY) < 0.8f) {
                        carrier = u;
                        dropC4 = false;
                        removePick(2);
                        ding(u.isPlayer ? "你捡起了 C4" : u.name + " 捡起 C4", 2);
                        snd(CFAudio.PICK, 0.8f);
                        break;
                    }
                }
            }
            // AI 进攻方：押点到包点埋
            if (carrier != null && !carrier.isPlayer && carrier.alive && freeze <= 0f
                    && CFMap.inSite(site == 0 ? CFMap.SITE_A : CFMap.SITE_B, carrier.x, carrier.y)) {
                Unit near = nearestFoe(carrier, 4.5f);
                if (near == null) {
                    plantProg += dt;
                    if (plantProg >= PLANT_FULL) doPlant(carrier);
                }
            }
            return;
        }
        bombT -= dt;
        if (bombT <= 5f && ((int) (bombT * 2f)) != ((int) ((bombT + dt) * 2f))) snd(CFAudio.PLANT, 0.45f);
        // AI 拆包
        if (defuserId >= 0) {
            Unit d = null;
            for (int i = 0; i < units.size(); i++) if (units.get(i).id == defuserId) d = units.get(i);
            if (d != null && d.alive && dist(d.x, d.y, bombX, bombY) < 0.8f && nearestFoe(d, 4f) == null) {
                defuseProg += dt;
                if (defuseProg >= DEFUSE_BOT) doDefuse();
            }
        }
        if (bombT <= 0f) {
            explode();
        }
    }

    private void doPlant(Unit u) {
        planted = true;
        dropC4 = false;
        bombX = u.x;
        bombY = u.y;
        bombT = BOMB_T;
        plantProg = 0f;
        defuseProg = 0f;
        carrier = u;
        removePick(2);
        ding("C4 已安放 · " + (site == 0 ? "A" : "B") + " 点", 2);
        snd(CFAudio.PLANT, 1f);
        refreshFlows();
    }

    private void doDefuse() {
        planted = false;
        defuseProg = 0f;
        snd(CFAudio.DEFUSE, 1f);
        ding("C4 已拆除！", 2);
        roundEnd(BLUE == atkTeam ? RED : BLUE, "C4 被拆除");
    }

    private void explode() {
        planted = false;
        shake = 1.4f;
        sparge(bombX, bombY, 40, 0xFFFFC24A, 1);
        sparge(bombX, bombY, 26, 0xFF665544, 2);
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            if (!u.alive) continue;
            float d = dist(u.x, u.y, bombX, bombY);
            if (d < 3.2f) hit(u, 160f * (1f - d / 3.6f), null, false);
        }
        snd(CFAudio.DIE, 1f);
        ding("C4 爆炸", 2);
        roundEnd(atkTeam, "C4 引爆");
    }

    /** 不做视线判定的最近敌人，用于算推进流场。 */
    private Unit nearestFoeAny(Unit u) {
        Unit best = null;
        float bd = 1e9f;
        for (int i = 0; i < units.size(); i++) {
            Unit o = units.get(i);
            if (!o.alive || o.team == u.team) continue;
            float d = dist(u.x, u.y, o.x, o.y);
            if (d < bd) { bd = d; best = o; }
        }
        return best;
    }

    private Unit nearestFoe(Unit u, float rng) {
        Unit best = null;
        float bd = rng;
        for (int i = 0; i < units.size(); i++) {
            Unit o = units.get(i);
            if (!o.alive || o.team == u.team) continue;
            float d = dist(u.x, u.y, o.x, o.y);
            if (d < bd && CFMap.los(u.x, u.y, o.x, o.y)) { bd = d; best = o; }
        }
        return best;
    }

    /* ================= 拾取 ================= */

    private void pickLogic(float dt) {
        pickupCd -= dt;
        if (pickupCd <= 0f) {
            pickupCd = 16f + rnd.nextFloat() * 10f;
            int[] cell = freeCell();
            Pickup p = new Pickup();
            p.x = cell[0] + 0.5f;
            p.y = cell[1] + 0.5f;
            p.kind = rnd.nextInt(10) < 45 ? 0 : rnd.nextInt(10) < 6 ? 3 : 1;
            p.life = 34f;
            picks.add(p);
        }
        for (Iterator<Pickup> it = picks.iterator(); it.hasNext(); ) {
            Pickup p = it.next();
            p.life -= dt;
            if (p.life <= 0f && p.kind != 2) { it.remove(); continue; }
            if (player != null && player.alive && dist(player.x, player.y, p.x, p.y) < 0.7f) {
                if (p.kind == 0) { player.hp = Math.min(100f, player.hp + 45f); ding("医疗包 +45", 2); }
                else if (p.kind == 1) { player.pack = CFWeapon.PACK[player.weapon]; ding("弹药补满", 2); }
                else if (p.kind == 3) {
                    player.weapon = CFWeapon.AWM;
                    player.mag = CFWeapon.MAG[CFWeapon.AWM];
                    player.pack = CFWeapon.PACK[CFWeapon.AWM];
                    player.awmT = 14f;
                    ding("空投 AWM！14 秒", 2);
                } else continue;
                snd(CFAudio.PICK, 0.9f);
                it.remove();
            }
        }
    }

    private void removePick(int kind) {
        for (Iterator<Pickup> it = picks.iterator(); it.hasNext(); ) {
            if (it.next().kind == kind) it.remove();
        }
    }

    private int[] freeCell() {
        for (int i = 0; i < 60; i++) {
            int x = 1 + rnd.nextInt(CFMap.W - 2), y = 1 + rnd.nextInt(CFMap.H - 2);
            if (CFMap.free(x, y)) return new int[]{x, y};
        }
        return new int[]{CFMap.W / 2, CFMap.H / 2};
    }

    /* ================= 回合与结算 ================= */

    private void checkEnd() {
        if (state != ST_PLAY || freeze > 0f) return;
        if (mode == TDM) {
            if (killsMine >= TDM_TARGET) matchEnd(true, "积分领先");
            else if (killsTheir >= TDM_TARGET) matchEnd(false, "被对手打满 30");
            else if (timeLeft <= 0f) matchEnd(killsMine > killsTheir, "时间到");
            return;
        }
        int defTeam = atkTeam == BLUE ? RED : BLUE;
        int a = aliveCount(atkTeam), d = aliveCount(defTeam);
        if (planted) {
            if (d == 0) roundEnd(atkTeam, "C4 已安放，防守方全灭");
        } else {
            if (a == 0) roundEnd(defTeam, "进攻方全灭");
            else if (d == 0) roundEnd(atkTeam, "防守方全灭");
            else if (timeLeft <= 0f) roundEnd(defTeam, "进攻超时");
        }
    }

    private int aliveCount(int team) {
        int n = 0;
        for (int i = 0; i < units.size(); i++) if (units.get(i).alive && units.get(i).team == team) n++;
        return n;
    }

    private void roundEnd(int winner, String why) {
        if (state != ST_PLAY) return;
        boolean mine = (winner == player.team);
        if (mine) winMine++; else winTheir++;
        banner = (winner == atkTeam ? "潜伏者胜利" : "保卫者胜利") + (mine ? " · 我方" : " · 对方");
        sub = why + "  " + winMine + " : " + winTheir;
        snd(mine ? CFAudio.WIN : CFAudio.LOSE, 1f);
        if (winMine >= 3 || winTheir >= 3 || round >= 5) {
            matchEnd(winMine > winTheir, why);
        } else {
            state = ST_ROUND;
            bannerT = 3.0f;
        }
    }

    private void nextRound() {
        round++;
        startRound();
        state = ST_PLAY;
    }

    private void matchEnd(boolean win, String why) {
        if (state == ST_OVER) return;
        state = ST_OVER;
        this.win = win;
        banner = win ? "胜利" : "失败";
        sub = mode == TDM ? ("团队竞技  " + killsMine + " : " + killsTheir + "  " + why) : ("爆破 " + winMine + " : " + winTheir + "  " + why);
        if (pKills > bestKills) bestKills = pKills;
        snd(win ? CFAudio.WIN : CFAudio.LOSE, 1f);
    }

    public void toMenu() {
        state = ST_MENU;
        units.clear();
        bullets.clear();
        parts.clear();
        picks.clear();
        floats.clear();
        msgs.clear();
    }

    /* ================= 粒子与提示 ================= */

    private void sparge(float x, float y, int n, int color, int kind) {
        for (int i = 0; i < n; i++) {
            if (parts.size() > 260) return;
            Particle p = new Particle();
            double a = rnd.nextDouble() * Math.PI * 2;
            float sp2 = kind == 2 ? 0.6f + rnd.nextFloat() * 1.4f : 1.2f + rnd.nextFloat() * 3.2f;
            p.x = x;
            p.y = y;
            p.vx = (float) Math.cos(a) * sp2;
            p.vy = (float) Math.sin(a) * sp2;
            p.max = kind == 1 ? 0.20f + rnd.nextFloat() * 0.16f : 0.34f + rnd.nextFloat() * 0.4f;
            p.life = p.max;
            p.size = kind == 0 ? 0.035f + rnd.nextFloat() * 0.05f : 0.05f + rnd.nextFloat() * 0.09f;
            p.color = color;
            p.kind = kind;
            parts.add(p);
        }
    }

    private void shell(float x, float y) {
        if (parts.size() > 240) return;
        Particle p = new Particle();
        double a = rnd.nextDouble() * Math.PI * 2;
        p.x = x;
        p.y = y;
        p.vx = (float) Math.cos(a) * 1.4f;
        p.vy = (float) Math.sin(a) * 1.4f;
        p.max = p.life = 0.5f;
        p.size = 0.03f;
        p.color = 0xFFD8B86A;
        p.kind = 3;
        parts.add(p);
    }

    private void moveParts(float dt) {
        for (Iterator<Particle> it = parts.iterator(); it.hasNext(); ) {
            Particle p = it.next();
            p.life -= dt;
            if (p.life <= 0f) { it.remove(); continue; }
            float k = p.kind == 2 ? 1.6f : p.kind == 3 ? 1.2f : 3.2f;
            p.x += p.vx * dt / k;
            p.y += p.vy * dt / k;
            p.vx *= 0.9f;
            p.vy *= 0.9f;
        }
    }

    private void floats(float x, float y, String text, int kind, int color) {
        if (floats.size() > 40) return;
        Floater f = new Floater();
        f.x = x;
        f.y = y;
        f.text = text;
        f.kind = kind;
        f.color = color;
        f.life = kind == 2 ? 1.5f : 0.8f;
        floats.add(f);
    }

    private void ding(String text, int kind) {
        Floater f = new Floater();
        f.x = player == null ? 0f : player.x;
        f.y = player == null ? 0f : player.y - 1.1f;
        f.text = text;
        f.kind = kind;
        f.color = 0xFFEFEFEF;
        f.life = 1.6f;
        floats.add(f);
    }

    private void snd(int which, Unit u) {
        if (audio == null || player == null) return;
        float d = dist(u.x, u.y, player.x, player.y);
        audio.play(which, clamp(1f - d / 26f, 0.12f, 1f));
    }

    private void snd(int which, float vol) {
        if (audio != null) audio.play(which, clamp(vol, 0f, 1f));
    }

    private void separate() {
        for (int i = 0; i < units.size(); i++) {
            Unit a = units.get(i);
            if (!a.alive) continue;
            for (int j = i + 1; j < units.size(); j++) {
                Unit b = units.get(j);
                if (!b.alive) continue;
                float dx = b.x - a.x, dy = b.y - a.y;
                float d = (float) Math.hypot(dx, dy);
                float min = a.r + b.r + 0.06f;
                if (d < min && d > 1e-4f) {
                    float push = (min - d) * 0.5f;
                    float nx = dx / d * push, ny = dy / d * push;
                    if (CFMap.passable(a.x - nx, a.y - ny, a.r)) { a.x -= nx; a.y -= ny; }
                    if (CFMap.passable(b.x + nx, b.y + ny, b.r)) { b.x += nx; b.y += ny; }
                }
            }
        }
    }

    private void moveUnit(Unit u, float dt) {
        float l = (float) Math.hypot(u.vx, u.vy);
        if (l < 1e-5f) return;
        float nx = u.x + u.vx * dt, ny = u.y + u.vy * dt;
        if (CFMap.passable(nx, u.y, u.r)) u.x = nx; else u.vx *= 0.2f;
        if (CFMap.passable(u.x, ny, u.r)) u.y = ny; else u.vy *= 0.2f;
        if (u.x < 0.5f) u.x = 0.5f;
        if (u.y < 0.5f) u.y = 0.5f;
        if (u.x > CFMap.W - 0.5f) u.x = CFMap.W - 0.5f;
        if (u.y > CFMap.H - 0.5f) u.y = CFMap.H - 0.5f;
    }

    private static float dist(float ax, float ay, float bx, float by) {
        return (float) Math.hypot(ax - bx, ay - by);
    }

    private static float dist(Unit a, Unit b) {
        return (float) Math.hypot(a.x - b.x, a.y - b.y);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /** 玩家相机目标（世界格坐标） */
    public float camTX() {
        return player == null ? CFMap.W / 2f : player.x;
    }

    public float camTY() {
        return player == null ? CFMap.H / 2f : player.y;
    }

    public int aliveMine() {
        return aliveCount(player == null ? BLUE : player.team);
    }

    public int aliveFoe() {
        return aliveCount(player == null ? RED : (player.team == BLUE ? RED : BLUE));
    }

    public boolean useVisible() {
        return mode == BP && player != null && (canPlant(player) || canDefuse(player));
    }

    public String useLabel() {
        if (player == null) return "";
        if (canPlant(player)) return plantProg > 0.05f ? "埋包中" : "埋包";
        if (canDefuse(player)) return defuseProg > 0.05f ? "拆包中" : "拆包";
        return "";
    }

    public float useProgress() {
        if (player == null) return 0f;
        if (canPlant(player)) return Math.min(1f, plantProg / PLANT_FULL);
        if (canDefuse(player)) return Math.min(1f, defuseProg / DEFUSE_BOT);
        return 0f;
    }

    public float accuracy() {
        return shots == 0 ? 0f : 100f * hits / shots;
    }
}
