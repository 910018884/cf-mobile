package com.dsh.cf;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;

/**
 * 掌上火线 —— 表现层。
 * 原生 Java + Canvas（SurfaceView），零第三方依赖、零图片资源：
 * 地形与角色像素图由 CFRes 程序化生成，枪声音效由 CFAudio 现场合成。
 *
 * 操作：左半屏拖动 = 走位；右半屏拖动 = 瞄准并自动开火；
 * 右下「弹」换弹、「用」埋包/拆包（按住）、左下「枪」切沙鹰 / AK。
 */
public class CFAct extends Activity {

    private GameView view;

    @Override
    protected void onCreate(Bundle b) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onCreate(b);
        view = new GameView(this);
        setContentView(view);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (view != null) view.pauseGame();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (view != null) view.resumeGame();
    }

    /* ================================================================== */

    static final class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

        private final CFGame g = new CFGame();
        private final CFAudio audio = new CFAudio();
        private final SharedPreferences pref;

        private Thread thread;
        private volatile boolean running, paused;
        private int w = 1, h = 1;

        // 程序化素材
        private final Bitmap[] tile = new Bitmap[6];
        private final Bitmap[] ground = new Bitmap[2];
        private final Bitmap[][] soldier = new Bitmap[2][6];
        private final Bitmap[] mark = new Bitmap[2];
        private Bitmap bombIc;
        private boolean resReady;

        // 相机（世界像素）
        private float scale = 1f, camX, camY;
        private boolean camInit;

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pTx = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pRaw = new Paint();
        private final Rect dst = new Rect();
        private final RectF rf = new RectF();

        // 触控
        private int moveId = -2, aimId = -2, useId = -2, relId = -2, swpId = -2;
        private float mbx, mby, mcx, mcy, abx, aby, acx, acy;

        // 菜单按钮
        private final RectF rTdm = new RectF(), rBp = new RectF();
        private final RectF rAgain = new RectF(), rMenu = new RectF();

        private int lastState = CFGame.ST_MENU;

        GameView(Context c) {
            super(c);
            getHolder().addCallback(this);
            pTx.setTypeface(Typeface.DEFAULT_BOLD);
            pref = c.getSharedPreferences("cf", MODE_PRIVATE);
            g.audio = audio;
            g.bestKills = pref.getInt("kills", 0);
            audio.init(c);
            loadRes();
        }

        private void loadRes() {
            try {
                CFRes.build();
                for (int i = 0; i < 6; i++) tile[i] = bmp(CFRes.wall[i], CFRes.TS, CFRes.TS);
                for (int i = 0; i < 2; i++) ground[i] = bmp(CFRes.ground[i], CFRes.TS, CFRes.TS);
                for (int t = 0; t < 2; t++) {
                    for (int f = 0; f < 6; f++) soldier[t][f] = bmp(CFRes.soldier[t][f], CFRes.SW, CFRes.SH);
                }
                for (int i = 0; i < 2; i++) mark[i] = bmp(CFRes.mark[i], 48, 48);
                bombIc = bmp(CFRes.bomb, 24, 24);
                resReady = true;
            } catch (Throwable t) {
                resReady = false;
            }
        }

        private static Bitmap bmp(int[] px, int bw, int bh) {
            return Bitmap.createBitmap(px, 0, 0, bw, bh, Bitmap.Config.ARGB_8888);
        }

        /* ---------------- 生命周期 ---------------- */

        public void surfaceCreated(SurfaceHolder holder) { startThread(); }

        public void surfaceChanged(SurfaceHolder holder, int fmt, int ww, int hh) {
            w = Math.max(1, ww);
            h = Math.max(1, hh);
            layout();
        }

        public void surfaceDestroyed(SurfaceHolder holder) { stopThread(); }

        void startThread() {
            if (thread != null) return;
            running = true;
            paused = false;
            thread = new Thread(this, "cf-game");
            thread.start();
        }

        void stopThread() {
            running = false;
            if (thread != null) {
                try { thread.join(); } catch (InterruptedException ignored) { }
                thread = null;
            }
        }

        void pauseGame() { paused = true; stopThread(); }

        void resumeGame() { if (running) startThread(); }

        private void layout() {
            float ts = h / (13.4f * CFRes.TS);        // 短边方向看 13.4 格
            float tw = w / (42f * CFRes.TS);          // 长边别把整张地图塞满
            scale = Math.max(0.5f, Math.min(ts, tw));
            pRaw.setFilterBitmap(false);
        }

        /* ---------------- 主循环 ---------------- */

        public void run() {
            long prev = System.nanoTime();
            while (running) {
                long now = System.nanoTime();
                float dt = (now - prev) / 1e9f;
                prev = now;
                if (dt > 0.05f) dt = 0.05f;
                if (dt < 0.001f) dt = 0.001f;

                Canvas c = null;
                SurfaceHolder sh = getHolder();
                try {
                    c = sh.lockCanvas();
                    if (c != null) {
                        synchronized (this) {
                            g.update(dt);
                            render(c);
                            if (g.state == CFGame.ST_OVER && lastState != CFGame.ST_OVER) {
                                pref.edit().putInt("kills", g.bestKills).apply();
                            }
                            lastState = g.state;
                        }
                    }
                } finally {
                    if (c != null) {
                        try { sh.unlockCanvasAndPost(c); } catch (Throwable ignored) { }
                    }
                }
                try { Thread.sleep(8); } catch (InterruptedException ignored) { }
            }
        }

        /* ---------------- 触控 ---------------- */

        @Override
        public synchronized boolean onTouchEvent(MotionEvent e) {
            int act = e.getActionMasked();
            int idx = e.getActionIndex();
            int id = e.getPointerId(idx);
            float x = e.getX(idx), y = e.getY(idx);
            int st = g.state;
            boolean down = act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN;

            if (st == CFGame.ST_MENU) {
                if (down) {
                    if (hit(rTdm, x, y)) { g.start(CFGame.TDM); audio.play(CFAudio.PICK, 0.8f); }
                    else if (hit(rBp, x, y)) { g.start(CFGame.BP); audio.play(CFAudio.PICK, 0.8f); }
                }
                return true;
            }
            if (st == CFGame.ST_OVER) {
                if (down) {
                    if (hit(rAgain, x, y)) { g.start(g.mode); audio.play(CFAudio.PICK, 0.8f); }
                    else if (hit(rMenu, x, y)) { g.toMenu(); audio.play(CFAudio.PICK, 0.8f); }
                }
                return true;
            }
            if (st != CFGame.ST_PLAY && st != CFGame.ST_ROUND) return true;

            if (down) {
                float br = btnR();
                if (g.state == CFGame.ST_PLAY) {
                    if (inBtn(cReloadX(), cReloadY(), br, x, y)) { g.tapReload = true; relId = id; return true; }
                    if (g.useVisible() && inBtn(cUseX(), cUseY(), br, x, y)) { g.inUse = true; useId = id; return true; }
                    if (inBtn(cSwapX(), cSwapY(), br, x, y)) { g.tapSwap = true; swpId = id; return true; }
                    if (x < w * 0.46f) {
                        if (moveId == -2) { moveId = id; mbx = mcx = x; mby = mcy = y; }
                    } else {
                        if (aimId == -2) { aimId = id; abx = acx = x; aby = acy = y; }
                    }
                }
            } else if (act == MotionEvent.ACTION_MOVE) {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int pid = e.getPointerId(i);
                    if (pid == moveId) { mcx = e.getX(i); mcy = e.getY(i); }
                    else if (pid == aimId) { acx = e.getX(i); acy = e.getY(i); }
                }
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_POINTER_UP
                    || act == MotionEvent.ACTION_CANCEL) {
                if (id == moveId) { moveId = -2; g.inmx = 0f; g.inmy = 0f; }
                if (id == aimId) { aimId = -2; g.inAimOn = false; g.inFire = false; }
                if (id == useId) { useId = -2; g.inUse = false; }
                if (id == relId) relId = -2;
                if (id == swpId) swpId = -2;
            }
            return true;
        }

        /** 摇杆 → 逻辑输入 */
        private void syncSticks() {
            float ring = ring();
            if (moveId != -2) {
                float dx = mcx - mbx, dy = mcy - mby;
                float l = (float) Math.hypot(dx, dy);
                if (l > ring * 0.16f) {
                    float k = Math.min(1f, l / ring);
                    g.inmx = dx / l * k;
                    g.inmy = dy / l * k;
                } else {
                    g.inmx = 0f;
                    g.inmy = 0f;
                }
            }
            if (aimId != -2) {
                float dx = acx - abx, dy = acy - aby;
                float l = (float) Math.hypot(dx, dy);
                if (l > ring * 0.22f) {
                    g.inAimOn = true;
                    g.inFire = true;
                    g.inaim = (float) Math.atan2(dy, dx);
                } else {
                    g.inFire = false;
                }
            }
        }

        /* ---------------- 渲染 ---------------- */

        private void render(Canvas c) {
            if (!resReady) loadRes();
            int st = g.state;
            if (st == CFGame.ST_MENU) { drawMenu(c); return; }
            drawWorld(c);
            drawHud(c);
            if (st == CFGame.ST_ROUND) drawBanner(c);
            else if (st == CFGame.ST_OVER) drawOver(c);
        }

        private void drawWorld(Canvas c) {
            c.drawColor(0xFF241F19);
            float vw = w / scale, vh = h / scale;
            if (g.player != null) {
                float tx = g.player.x * CFRes.TS - vw / 2f;
                float ty = g.player.y * CFRes.TS - vh / 2f;
                if (!camInit) { camX = tx; camY = ty; camInit = true; }
                camX += (tx - camX) * 0.18f;
                camY += (ty - camY) * 0.18f;
            }
            camX = clamp(camX, 0f, CFMap.W * CFRes.TS - vw);
            camY = clamp(camY, 0f, CFMap.H * CFRes.TS - vh);
            float shx = 0f, shy = 0f;
            if (g.shake > 0f) {
                shx = (float) Math.sin(g.t * 61f) * g.shake * 24f;
                shy = (float) Math.cos(g.t * 47f) * g.shake * 24f;
            }

            c.save();
            c.scale(scale, scale);
            c.translate(-camX + shx, -camY + shy);

            if (!resReady) {
                c.drawColor(0xFF8A7A5A);
            } else {
                int x0 = Math.max(0, (int) (camX / CFRes.TS) - 1);
                int x1 = Math.min(CFMap.W - 1, (int) ((camX + vw) / CFRes.TS) + 1);
                int y0 = Math.max(0, (int) (camY / CFRes.TS) - 1);
                int y1 = Math.min(CFMap.H - 1, (int) ((camY + vh) / CFRes.TS) + 1);
                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) {
                        byte t = CFMap.at(x, y);
                        Bitmap bm = t == 0 ? (tileGround(x, y) ? ground[1] : ground[0]) : tile[CFMap.tex(t)];
                        c.drawBitmap(bm, null, rect(x * CFRes.TS, y * CFRes.TS, CFRes.TS, CFRes.TS), pRaw);
                    }
                }
                drawMark(c, CFMap.SITE_A, 0);
                drawMark(c, CFMap.SITE_B, 1);
            }

            drawBomb(c);
            drawPickups(c);
            drawBullets(c);
            drawUnits(c);
            drawParts(c);
            drawFloats(c);
            drawAim(c);
            c.restore();
        }

        private Rect rect(float x, float y, float ww, float hh) {
            dst.set((int) x, (int) y, (int) (x + ww), (int) (y + hh));
            return dst;
        }

        private boolean tileGround(int x, int y) {
            float fx = x + 0.5f, fy = y + 0.5f;
            if (CFMap.inSite(CFMap.SITE_A, fx, fy) || CFMap.inSite(CFMap.SITE_B, fx, fy)) return true;
            return (x > 33 && y < 8) || (x < 10 && y > 27);
        }

        private void drawMark(Canvas c, int[] site, int kind) {
            if (mark[kind] == null) return;
            float cx = (site[0] + site[2] + 1) / 2f * CFRes.TS;
            float cy = (site[1] + site[3] + 1) / 2f * CFRes.TS;
            p.setAlpha(110);
            c.drawBitmap(mark[kind], cx - 24f, cy - 24f, p);
            p.setAlpha(255);
        }

        private void drawBomb(Canvas c) {
            if (!g.planted || bombIc == null) return;
            float x = g.bombX * CFRes.TS, y = g.bombY * CFRes.TS;
            float pulse = (float) Math.abs(Math.sin(g.t * (g.bombT < 6f ? 9f : 3f)));
            p.setColor(0x66FF3020);
            p.setAlpha((int) (70 + 130 * pulse));
            c.drawCircle(x, y, 34f + 14f * pulse, p);
            p.setAlpha(255);
            p.setColor(0);
            c.drawBitmap(bombIc, x - 12f, y - 12f, pRaw);
            txt(c, (int) Math.ceil(g.bombT) + "s", x, y - 24f, 26f, 0xFFFF4030, Paint.Align.CENTER);
        }

        private void drawPickups(Canvas c) {
            for (int i = 0; i < g.picks.size(); i++) {
                CFGame.Pickup pk = g.picks.get(i);
                float x = pk.x * CFRes.TS, y = pk.y * CFRes.TS;
                float bob = (float) Math.sin(g.t * 3f + i) * 4f;
                if (pk.kind == 2) {
                    if (bombIc != null) c.drawBitmap(bombIc, x - 12f, y - 12f + bob, pRaw);
                    continue;
                }
                p.setColor(pk.kind == 0 ? 0xFFD8433C : pk.kind == 1 ? 0xFFD8B04A : 0xFF4A90D8);
                rf.set(x - 15f, y - 15f + bob, x + 15f, y + 15f + bob);
                c.drawRoundRect(rf, 5f, 5f, p);
                String lab = pk.kind == 0 ? "+" : pk.kind == 1 ? "Ⅶ" : "A";
                txt(c, lab, x, y + 9f + bob, 22f, 0xFFFFFFFF, Paint.Align.CENTER);
            }
            p.setColor(0);
        }

        private void drawBullets(Canvas c) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3.2f);
            for (int i = 0; i < g.bullets.size(); i++) {
                CFGame.Bullet b = g.bullets.get(i);
                p.setColor(b.color);
                p.setAlpha(225);
                c.drawLine(b.px * CFRes.TS, b.py * CFRes.TS, b.x * CFRes.TS, b.y * CFRes.TS, p);
            }
            p.setAlpha(255);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0);
        }

        private void drawUnits(Canvas c) {
            for (int i = 0; i < g.units.size(); i++) {
                CFGame.Unit u = g.units.get(i);
                float x = u.x * CFRes.TS, y = u.y * CFRes.TS;
                p.setColor(u.team == CFGame.BLUE ? 0x5A3C86D8 : 0x5AD85A32);
                rf.set(x - 20f, y + 6f, x + 20f, y + 21f);
                c.drawOval(rf, p);

                if (!resReady) {
                    p.setColor(u.team == CFGame.BLUE ? 0xFF4E8FD8 : 0xFFD8603C);
                    c.drawCircle(x, y - 10f, 18f, p);
                    p.setColor(0);
                } else {
                    int frame;
                    if (!u.alive) {
                        frame = 5;
                    } else if (u.fireCd > CFWeapon.ROF[u.weapon] * 0.5f) {
                        frame = 4;
                    } else if (Math.hypot(u.vx, u.vy) > 0.35f) {
                        frame = 1 + ((int) u.walkAnim % 2) * 2;
                    } else {
                        frame = 0;
                    }
                    Bitmap bm = soldier[u.team][frame];
                    p.setAlpha(u.alive ? 255 : 120);
                    c.save();
                    if (!u.alive) {
                        c.rotate(u.id % 2 == 0 ? 76f : -76f, x, y);
                    } else if (u.face < 0f) {
                        c.scale(-1f, 1f, x, y);
                    }
                    c.drawBitmap(bm, x - CFRes.SW / 2f, y - CFRes.SH * 0.78f, pRaw);
                    c.restore();
                    p.setAlpha(255);
                    if (u.alive && u.hitFlash > 0f) {
                        p.setAlpha((int) (u.hitFlash * 120));
                        p.setColor(0xFFFF4030);
                        rf.set(x - 22f, y - CFRes.SH * 0.78f, x + 22f, y + 14f);
                        c.drawOval(rf, p);
                        p.setAlpha(255);
                        p.setColor(0);
                    }
                    if (u.alive && u.prot > 0f) {
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(3f);
                        p.setColor(0x809FE8FF);
                        c.drawCircle(x, y - 12f, 30f, p);
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(0);
                    }
                }
                if (!u.isPlayer && u.alive) {
                    float bw = 40f;
                    float hpw = bw * clamp(u.hp / 100f, 0f, 1f);
                    p.setColor(0x99000000);
                    c.drawRect(x - bw / 2f, y - 62f, x + bw / 2f, y - 55f, p);
                    p.setColor(u.hp > 55f ? 0xFF59C05A : u.hp > 25f ? 0xFFD8B04A : 0xFFD84A3C);
                    c.drawRect(x - bw / 2f, y - 62f, x - bw / 2f + hpw, y - 55f, p);
                    p.setColor(0);
                }
            }
        }

        private void drawParts(Canvas c) {
            for (int i = 0; i < g.parts.size(); i++) {
                CFGame.Particle q = g.parts.get(i);
                p.setColor(q.color);
                p.setAlpha((int) (255 * clamp(q.life / Math.max(0.02f, q.max), 0f, 1f)));
                c.drawCircle(q.x * CFRes.TS, q.y * CFRes.TS, q.size * CFRes.TS, p);
            }
            p.setAlpha(255);
            p.setColor(0);
        }

        private void drawFloats(Canvas c) {
            for (int i = 0; i < g.floats.size(); i++) {
                CFGame.Floater f = g.floats.get(i);
                p.setAlpha((int) (255 * clamp(f.life * 1.6f, 0f, 1f)));
                txt(c, f.text, f.x * CFRes.TS, f.y * CFRes.TS, f.kind > 1 ? 26f : 21f, f.color, Paint.Align.CENTER);
                p.setAlpha(255);
            }
        }

        /** 玩家准星与枪线 */
        private void drawAim(Canvas c) {
            CFGame.Unit u = g.player;
            if (u == null || !u.alive) return;
            float x = u.x * CFRes.TS, y = u.y * CFRes.TS;
            float len = CFRes.TS * (u.weapon == CFWeapon.AWM ? 9f : 6.2f);
            float cx = x + (float) Math.cos(u.aim) * len, cy = y + (float) Math.sin(u.aim) * len;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(0x33FFFFFF);
            Path pt = new Path();
            pt.moveTo(x + (float) Math.cos(u.aim) * 24f, y + (float) Math.sin(u.aim) * 24f);
            pt.lineTo(cx, cy);
            c.drawPath(pt, p);
            p.setColor(0xCCFFFFFF);
            float r = 13f;
            c.drawLine(cx - r, cy, cx - 4f, cy, p);
            c.drawLine(cx + 4f, cy, cx + r, cy, p);
            c.drawLine(cx, cy - r, cx, cy - 4f, p);
            c.drawLine(cx, cy + 4f, cx, cy + r, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0);
        }

        /* ---------------- HUD ---------------- */

        private void drawHud(Canvas c) {
            CFGame.Unit u = g.player;
            if (u != null && u.alive && g.state == CFGame.ST_PLAY) syncSticks();

            float ring = ring();
            if (moveId != -2) joystick(c, mbx, mby, mcx, mcy, ring, 0x33FFFFFF, 0x66FFFFFF);
            if (aimId != -2) joystick(c, abx, aby, acx, acy, ring, 0x33FF6A3C, 0x66FF6A3C);

            drawMini(c);
            drawTopBar(c);
            drawFeed(c);
            drawHp(c, u);
            drawAmmo(c, u);
            if (g.state == CFGame.ST_PLAY) drawButtons(c);

            // 受击红边
            float hurt = g.damageFlash;
            if (u != null && u.alive && u.hp < 35f) hurt = Math.max(hurt, 0.4f);
            if (hurt > 0f) {
                p.setColor(0xFFB3121F);
                p.setAlpha((int) (110 * Math.min(1f, hurt)));
                float bw = Math.min(w, h) * 0.028f;
                c.drawRect(0, 0, w, bw, p);
                c.drawRect(0, h - bw, w, h, p);
                c.drawRect(0, 0, bw, h, p);
                c.drawRect(w - bw, 0, w, h, p);
                p.setAlpha(255);
                p.setColor(0);
            }

            // 命中标记
            if (g.hitMark > 0f && u != null && u.alive) {
                float sx = (u.x * CFRes.TS - camX) * scale, sy = (u.y * CFRes.TS - camY) * scale;
                float len = CFRes.TS * (u.weapon == CFWeapon.AWM ? 9f : 6.2f) * scale;
                float cx = sx + (float) Math.cos(u.aim) * len, cy = sy + (float) Math.sin(u.aim) * len;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(2f, h * 0.004f));
                p.setColor(0xFFFFFFFF);
                p.setAlpha((int) (255 * Math.min(1f, g.hitMark)));
                float d = h * 0.016f;
                c.drawLine(cx - d, cy - d, cx - d * 0.35f, cy - d * 0.35f, p);
                c.drawLine(cx + d, cy - d, cx + d * 0.35f, cy - d * 0.35f, p);
                c.drawLine(cx - d, cy + d, cx - d * 0.35f, cy + d * 0.35f, p);
                c.drawLine(cx + d, cy + d, cx + d * 0.35f, cy + d * 0.35f, p);
                p.setAlpha(255);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0);
            }

            if (g.state == CFGame.ST_PLAY) {
                if (u != null && !u.alive) {
                    txt(c, u.respawnT > 0f ? "阵亡 · " + (int) Math.ceil(u.respawnT) + " 秒后重生" : "阵亡 · 观战中",
                            w / 2f, h * 0.42f, h * 0.055f, 0xE6FFFFFF, Paint.Align.CENTER);
                }
                if (g.freeze > 0f) {
                    txt(c, "冻结时间 " + (int) Math.ceil(g.freeze), w / 2f, h * 0.2f, h * 0.05f, 0xE6FFE08A, Paint.Align.CENTER);
                }
                if (g.bannerT > 0f) {
                    txt(c, g.banner, w / 2f, h * 0.115f, h * 0.058f, 0xE6FFE08A, Paint.Align.CENTER);
                    txt(c, g.sub, w / 2f, h * 0.175f, h * 0.030f, 0xA6FFFFFF, Paint.Align.CENTER);
                }
            }
        }

        private float ring() { return Math.min(w, h) * 0.15f; }

        private void joystick(Canvas c, float ox, float oy, float kx, float ky, float ring, int out, int knob) {
            p.setColor(out);
            c.drawCircle(ox, oy, ring, p);
            p.setColor(knob);
            float dx = kx - ox, dy = ky - oy;
            float l = (float) Math.hypot(dx, dy);
            float k = l > ring ? ring / l : 1f;
            c.drawCircle(ox + dx * k, oy + dy * k, ring * 0.42f, p);
            p.setColor(0);
        }

        private void drawMini(Canvas c) {
            float cell = Math.max(3f, h * 0.0062f);
            float mw = cell * CFMap.W, mh = cell * CFMap.H;
            float ox = h * 0.02f, oy = h * 0.024f;
            rf.set(ox - 6, oy - 6, ox + mw + 6, oy + mh + 6);
            p.setColor(0x80101410);
            c.drawRoundRect(rf, 8f, 8f, p);
            p.setColor(0x33FFFFFF);
            for (int y = 0; y < CFMap.H; y++) {
                for (int x = 0; x < CFMap.W; x++) {
                    if (!CFMap.solid(x, y)) continue;
                    c.drawRect(ox + x * cell, oy + y * cell, ox + (x + 1) * cell, oy + (y + 1) * cell, p);
                }
            }
            p.setColor(0x4DFFB040);
            siteRect(c, CFMap.SITE_B, ox, oy, cell);
            siteRect(c, CFMap.SITE_A, ox, oy, cell);
            for (int i = 0; i < g.units.size(); i++) {
                CFGame.Unit u = g.units.get(i);
                if (!u.alive) continue;
                p.setColor(u.isPlayer ? 0xFFFFFFFF : u.team == CFGame.BLUE ? 0xFF5EA8FF : 0xFFFF6A3C);
                c.drawCircle(ox + u.x * cell, oy + u.y * cell, u.isPlayer ? cell * 1.2f : cell * 0.9f, p);
            }
            if (g.planted) {
                p.setColor(0xFFFF2020);
                c.drawCircle(ox + g.bombX * cell, oy + g.bombY * cell,
                        cell * (1.2f + 0.6f * (float) Math.abs(Math.sin(g.t * 5f))), p);
            }
            p.setColor(0x99FFFFFF);
            c.drawText(g.mode == CFGame.BP ? (g.playerAttack ? "潜伏者 · 进攻" : "保卫者 · 防守") : "团队竞技",
                    ox, oy + mh + h * 0.040f, p);
            p.setColor(0);
        }

        private void siteRect(Canvas c, int[] s, float ox, float oy, float cell) {
            c.drawRect(ox + (s[0] - 0.5f) * cell, oy + (s[1] - 0.5f) * cell,
                    ox + (s[2] + 1.5f) * cell, oy + (s[3] + 1.5f) * cell, p);
        }

        private void drawTopBar(Canvas c) {
            float cx = w / 2f;
            float bw = Math.min(w * 0.40f, h * 0.74f), bh = h * 0.090f;
            rf.set(cx - bw / 2f, h * 0.014f, cx + bw / 2f, h * 0.014f + bh);
            p.setColor(0x80101410);
            c.drawRoundRect(rf, 10f, 10f, p);
            float my = h * 0.014f + bh * 0.46f;
            int mine = g.mode == CFGame.TDM ? g.killsMine : g.winMine;
            int foe = g.mode == CFGame.TDM ? g.killsTheir : g.winTheir;
            txt(c, "我方 " + mine, cx - bw * 0.31f, my, h * 0.040f, 0xFF6FB4FF, Paint.Align.CENTER);
            txt(c, foe + " 敌方", cx + bw * 0.31f, my, h * 0.040f, 0xFFFF7A4A, Paint.Align.CENTER);
            int tm = Math.max(0, (int) g.timeLeft);
            txt(c, (tm / 60) + ":" + (tm % 60 < 10 ? "0" : "") + (tm % 60), cx, my, h * 0.044f, 0xFFFFFFFF, Paint.Align.CENTER);
            String left = g.mode == CFGame.TDM
                    ? "存活 " + g.aliveMine() + " v " + g.aliveFoe() + " · 目标 " + (int) CFGame.TDM_TARGET + " 杀"
                    : (g.planted ? "C4 引爆倒计时 " + (int) Math.ceil(g.bombT) + "s"
                            : "第 " + g.round + " 局 · " + (g.site == 0 ? "A" : "B") + " 点");
            txt(c, left, cx, h * 0.014f + bh + h * 0.034f, h * 0.028f, 0x99FFFFFF, Paint.Align.CENTER);
        }

        private void drawFeed(Canvas c) {
            float y = h * 0.14f;
            float size = h * 0.030f;
            int from = Math.max(0, g.msgs.size() - 4);
            for (int i = from; i < g.msgs.size(); i++) {
                CFGame.Msg m = g.msgs.get(i);
                p.setAlpha((int) (255 * clamp(m.life / 1.2f, 0f, 1f)));
                String s = m.killer + (m.head ? " ☠ " : " ▸ ") + m.wpn + " ▸ " + m.victim;
                txt(c, s, w - h * 0.022f, y, size,
                        m.kteam == CFGame.BLUE ? 0xFF8FC4FF : 0xFFFFA070, Paint.Align.RIGHT);
                p.setAlpha(255);
                y += size * 1.45f;
            }
        }

        private void drawHp(Canvas c, CFGame.Unit u) {
            float bw = Math.min(w * 0.20f, h * 0.34f), bh = h * 0.030f;
            float x = h * 0.024f, y = h - h * 0.120f;
            rf.set(x, y, x + bw, y + bh);
            p.setColor(0x80101410);
            c.drawRoundRect(rf, bh / 2f, bh / 2f, p);
            float hp = u == null ? 0f : clamp(u.hp, 0f, 100f);
            if (hp > 0f) {
                p.setColor(hp > 55f ? 0xFF3FA24C : hp > 25f ? 0xFFD8A93C : 0xFFC43A2E);
                rf.set(x + 3f, y + 3f, x + 3f + (bw - 6f) * hp / 100f, y + bh - 3f);
                c.drawRoundRect(rf, bh / 2f, bh / 2f, p);
            }
            p.setColor(0);
            txt(c, "HP " + (int) hp, x + 10f, y + bh * 0.78f, h * 0.026f, 0xFFFFFFFF, Paint.Align.LEFT);
            if (u != null && u.kills > 0) {
                txt(c, u.kills + " 杀 " + u.head + " 爆头", x + bw - 4f, y + bh * 0.78f, h * 0.026f, 0xFFFFD24A, Paint.Align.RIGHT);
            }
        }

        private void drawAmmo(Canvas c, CFGame.Unit u) {
            if (u == null) return;
            float x = w - h * 0.032f, y = h - h * 0.105f;
            String lab = CFWeapon.NAME[u.weapon] + (u.awmT > 0f ? " 空投 " + (int) Math.ceil(u.awmT) + "s" : "");
            txt(c, lab, x, y - h * 0.048f, h * 0.030f, 0xCCFFFFFF, Paint.Align.RIGHT);
            txt(c, CFWeapon.ammo(u.weapon, u.mag, u.pack), x, y, h * 0.062f,
                    u.mag == 0 && u.pack == 0 ? 0xFFFF5A3C : 0xFFFFFFFF, Paint.Align.RIGHT);
            if (u.reload > 0f) {
                float full = CFWeapon.RELOAD[u.weapon];
                float prog = clamp(1f - u.reload / full, 0f, 1f);
                float cx = x - h * 0.030f, cy = y - h * 0.115f, r = h * 0.030f;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(h * 0.008f);
                p.setColor(0x44FFFFFF);
                c.drawCircle(cx, cy, r, p);
                p.setColor(0xFFFFD24A);
                rf.set(cx - r, cy - r, cx + r, cy + r);
                c.drawArc(rf, -90f, 360f * prog, false, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0);
            }
        }

        /* ---- 按钮 ---- */

        private float btnR() { return h * 0.055f; }

        private float cReloadX() { return w - btnR() - h * 0.038f; }

        private float cReloadY() { return h - btnR() - h * 0.038f; }

        private float cUseX() { return w - btnR() - h * 0.038f; }

        private float cUseY() { return cReloadY() - btnR() * 2.6f; }

        private float cSwapX() { return btnR() + h * 0.038f; }

        private float cSwapY() { return h - btnR() - h * 0.038f; }

        private void drawButtons(Canvas c) {
            float r = btnR();
            circleBtn(c, cReloadX(), cReloadY(), r, "弹", 0xCC2A3540, 0xE6FFFFFF);
            circleBtn(c, cSwapX(), cSwapY(), r, "枪", 0xCC2A3540, 0xE6FFFFFF);
            if (g.useVisible()) {
                circleBtn(c, cUseX(), cUseY(), r, g.useLabel(), 0xCC8A3A20, 0xE6FFD8A0);
                float pr = g.useProgress();
                if (pr > 0f) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * 0.22f);
                    p.setColor(0xFFFFD24A);
                    float cx = cUseX(), cy = cUseY();
                    rf.set(cx - r, cy - r, cx + r, cy + r);
                    c.drawArc(rf, -90f, 360f * pr, false, p);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0);
                }
            }
        }

        private void circleBtn(Canvas c, float cx, float cy, float r, String lab, int bg, int fg) {
            p.setColor(bg);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.09f);
            p.setColor(0x66FFFFFF);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.FILL);
            txt(c, lab, cx, cy + r * 0.34f, r * (lab.length() > 1 ? 0.58f : 0.80f), fg, Paint.Align.CENTER);
        }

        /* ---------------- 菜单 / 回合横幅 / 结算 ---------------- */

        private void drawMenu(Canvas c) {
            LinearGradient lg = new LinearGradient(0, 0, w, h, 0xFF141A20, 0xFF43301E, Shader.TileMode.CLAMP);
            p.setShader(lg);
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(h * 0.006f);
            p.setColor(0x22FFFFFF);
            float r0 = Math.min(h * 0.30f, w * 0.18f);
            float ccx = w * 0.80f, ccy = h * 0.5f;
            c.drawCircle(ccx, ccy, r0, p);
            c.drawCircle(ccx, ccy, r0 * 0.62f, p);
            c.drawLine(ccx - r0, ccy, ccx - r0 * 0.7f, ccy, p);
            c.drawLine(ccx + r0 * 0.7f, ccy, ccx + r0, ccy, p);
            c.drawLine(ccx, ccy - r0, ccx, ccy - r0 * 0.7f, p);
            c.drawLine(ccx, ccy + r0 * 0.7f, ccx, ccy + r0, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0);

            float lx = w * 0.06f;
            txt(c, "掌 上 火 线", lx, h * 0.24f, h * 0.112f, 0xFFFFF2D0, Paint.Align.LEFT);
            txt(c, "CF 手游复刻 · 原生 Canvas 硬核版", lx, h * 0.325f, h * 0.036f, 0xB3FFFFFF, Paint.Align.LEFT);

            float bw = Math.min(w * 0.36f, h * 0.92f), bh = h * 0.118f;
            rTdm.set(lx, h * 0.42f, lx + bw, h * 0.42f + bh);
            rBp.set(lx, h * 0.42f + bh * 1.28f, lx + bw, h * 0.42f + bh * 2.28f);
            menuBtn(c, rTdm, "团队竞技", "5v5 · 先杀 30 人 · 4 分钟");
            menuBtn(c, rBp, "爆破模式", "5v5 · C4 埋包/拆包 · 五局三胜");

            txtMulti(c, "左半屏拖动 = 走位      右半屏拖动 = 瞄准 + 自动开火\n"
                    + "右下「弹」换弹 · 「用」埋包/拆包（按住）· 左下「枪」切沙鹰\n"
                    + "地图：沙漠灰 44×36 · 中门 / A 包点 / B 包点 · AWM 空投与补给随机刷新",
                    lx, h * 0.80f, h * 0.028f, 0x99FFFFFF);
            txt(c, "历史最高击杀 " + g.bestKills + " · 零依赖零图片，地形角色音效全部程序化生成",
                    lx, h * 0.95f, h * 0.026f, 0x80FFFFFF, Paint.Align.LEFT);
        }

        private void menuBtn(Canvas c, RectF r, String title, String sub) {
            rf.set(r.left, r.top, r.right, r.bottom);
            p.setColor(0xF2C18A);
            p.setAlpha(34);
            c.drawRoundRect(rf, 12f, 12f, p);
            p.setAlpha(255);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3f);
            p.setColor(0x99F2C18A);
            c.drawRoundRect(rf, 12f, 12f, p);
            p.setStyle(Paint.Style.FILL);
            float cy = r.centerY();
            txt(c, title, r.left + h * 0.035f, cy - h * 0.004f, h * 0.050f, 0xFFFFF2D0, Paint.Align.LEFT);
            txt(c, sub, r.left + h * 0.035f, cy + h * 0.036f, h * 0.026f, 0xA6FFFFFF, Paint.Align.LEFT);
            p.setColor(0);
        }

        private void drawBanner(Canvas c) {
            p.setColor(0xAA000000);
            c.drawRect(0, 0, w, h, p);
            p.setColor(0);
            txt(c, g.banner, w / 2f, h * 0.42f, h * 0.10f, 0xFFFFF2D0, Paint.Align.CENTER);
            txt(c, g.sub, w / 2f, h * 0.52f, h * 0.042f, 0xB3FFFFFF, Paint.Align.CENTER);
            txt(c, "下一局准备中…", w / 2f, h * 0.62f, h * 0.032f, 0x80FFFFFF, Paint.Align.CENTER);
        }

        private void drawOver(Canvas c) {
            p.setColor(0xCC0A0E12);
            c.drawRect(0, 0, w, h, p);
            p.setColor(0);
            txt(c, g.win ? "使 命 达 成" : "任 务 失 败", w / 2f, h * 0.20f, h * 0.095f,
                    g.win ? 0xFFFFD24A : 0xFFFF7A5A, Paint.Align.CENTER);
            txt(c, g.sub, w / 2f, h * 0.29f, h * 0.038f, 0xB3FFFFFF, Paint.Align.CENTER);

            String[] kv = {"击杀 " + g.pKills, "死亡 " + g.pDeaths, "爆头 " + g.headshots,
                    "命中率 " + (int) g.accuracy() + "%",
                    "KD " + (g.pDeaths == 0 ? g.pKills : Math.round(g.pKills * 10f / g.pDeaths) / 10f)};
            float y = h * 0.42f;
            float cw = w / kv.length;
            for (int i = 0; i < kv.length; i++) {
                txt(c, kv[i], cw * (i + 0.5f), y, h * 0.036f, 0xE6FFFFFF, Paint.Align.CENTER);
            }

            float bw = Math.min(w * 0.22f, h * 0.70f), bh = h * 0.125f;
            rAgain.set(w / 2f - bw - h * 0.02f, h * 0.60f, w / 2f - h * 0.02f, h * 0.60f + bh);
            rMenu.set(w / 2f + h * 0.02f, h * 0.60f, w / 2f + h * 0.02f + bw, h * 0.60f + bh);
            menuBtn(c, rAgain, "再来一局", g.mode == CFGame.TDM ? "团队竞技" : "爆破模式");
            menuBtn(c, rMenu, "返回大厅", "回主菜单");
        }

        /* ---------------- 文字 ---------------- */

        private void txt(Canvas c, String s, float x, float y, float size, int color, Paint.Align al) {
            pTx.setTextSize(size);
            pTx.setTextAlign(al);
            pTx.setColor(0x99000000);
            c.drawText(s, x + size * 0.030f, y + size * 0.040f, pTx);
            pTx.setColor(color);
            c.drawText(s, x, y, pTx);
            pTx.setColor(0);
        }

        private void txtMulti(Canvas c, String s, float x, float y, float size, int color) {
            pTx.setTextSize(size);
            pTx.setTextAlign(Paint.Align.LEFT);
            pTx.setColor(color);
            String[] ls = s.split("\n");
            for (int i = 0; i < ls.length; i++) c.drawText(ls[i], x, y + i * size * 1.5f, pTx);
        }

        /* ---------------- 小工具 ---------------- */

        private static boolean hit(RectF r, float x, float y) {
            return x >= r.left && x <= r.right && y >= r.top && y <= r.bottom;
        }

        private static boolean inBtn(float cx, float cy, float r, float x, float y) {
            return Math.hypot(x - cx, y - cy) <= r * 1.25f;
        }

        private static float clamp(float v, float lo, float hi) {
            return v < lo ? lo : v > hi ? hi : v;
        }
    }
}
