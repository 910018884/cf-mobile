package com.dsh.cf;

// 沙漠灰式地图：44x36 网格，中门 + B 洞 + A/B 包点，两侧基地
public final class CFMap {
    public static final int W = 44, H = 36;
    public static final byte SAND = 1, BRICK = 2, CRATE = 3, METAL = 4, NET = 5, CONC = 6;

    static final byte[] G = new byte[W * H];

    // 包点范围 x0,y0,x1,y1（含）
    public static final int[] SITE_A = {28, 24, 38, 31};
    public static final int[] SITE_B = {4, 4, 14, 12};
    // 基地范围
    static final int[] BASE_R = {35, 1, 42, 6};   // 潜伏者（红）东北
    static final int[] BASE_B = {1, 29, 8, 34};   // 保卫者（蓝）西南

    static int[] blueSp, redSp;   // 出生格 {x,y,...}

    static {
        build();
    }

    static void build() {
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) G[y * W + x] = 0;
        // 外墙
        for (int x = 0; x < W; x++) { set(x, 0, SAND); set(x, H - 1, SAND); }
        for (int y = 0; y < H; y++) { set(0, y, SAND); set(W - 1, y, SAND); }

        // 中场纵墙（留中门通道）
        vWall(21, 1, 13, SAND);
        vWall(21, 22, 34, SAND);
        box(20, 16, 21, 17, METAL);        // 中门铁门
        vWall(20, 24, 30, BRICK);

        // B 侧（西北）：洞内暗墙 + 掩体
        hWall(1, 16, 13, BRICK);
        box(10, 8, 11, 8, CRATE);
        box(5, 6, 6, 7, CRATE);
        box(13, 3, 14, 4, NET);
        box(2, 10, 3, 11, NET);
        box(15, 10, 16, 12, CONC);

        // A 侧（东南）：包点建筑群
        hWall(27, 42, 23, BRICK);
        vWall(27, 24, 31, CONC);
        box(33, 26, 34, 27, CRATE);
        box(37, 24, 38, 25, CRATE);
        box(30, 30, 31, 31, CRATE);
        box(40, 28, 41, 29, NET);
        box(29, 19, 30, 20, CONC);

        // 中场横墙（连通两翼）
        hWall(8, 19, 20, SAND);
        hWall(24, 34, 13, SAND);

        // 散落木箱做枪线掩体
        box(17, 16, 18, 17, CRATE);
        box(24, 19, 25, 20, CRATE);
        box(23, 27, 24, 28, CRATE);
        box(12, 22, 13, 23, CRATE);
        box(7, 26, 8, 26, CRATE);
        box(35, 15, 36, 16, CRATE);
        box(31, 8, 32, 9, CRATE);
        box(4, 18, 5, 19, CRATE);
        box(19, 31, 20, 32, CRATE);

        // 基地围墙
        box(9, 29, 9, 29, CONC); box(34, 6, 34, 6, CONC);
        vWall(34, 1, 4, SAND);
        hWall(1, 9, 28, SAND);

        collectSpawns();
    }

    static void set(int x, int y, byte t) {
        if (x >= 0 && y >= 0 && x < W && y < H) G[y * W + x] = t;
    }
    static void box(int x0, int y0, int x1, int y1, byte t) {
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) set(x, y, t);
    }
    static void hWall(int x0, int x1, int y, byte t) { box(x0, y, x1, y, t); }
    static void vWall(int x, int y0, int y1, byte t) { box(x, y0, x, y1, t); }

    static void collectSpawns() {
        blueSp = cells(BASE_B);
        redSp = cells(BASE_R);
    }
    static int[] cells(int[] base) {
        int n = 0;
        int[] tmp = new int[(base[2] - base[0] + 1) * (base[3] - base[1] + 1) * 2];
        for (int y = base[1]; y <= base[3]; y++)
            for (int x = base[0]; x <= base[2]; x++)
                if (free(x, y)) { tmp[n * 2] = x; tmp[n * 2 + 1] = y; n++; }
        int[] r = new int[n * 2];
        System.arraycopy(tmp, 0, r, 0, n * 2);
        return r;
    }

    public static byte at(int x, int y) {
        if (x < 0 || y < 0 || x >= W || y >= H) return SAND;
        return G[y * W + x];
    }
    public static boolean solid(int x, int y) { return at(x, y) != 0; }
    public static boolean free(int x, int y) { return at(x, y) == 0; }

    public static int tex(byte t) {
        switch (t) {
            case SAND: return 0;
            case BRICK: return 1;
            case CRATE: return 2;
            case METAL: return 3;
            case NET: return 4;
            case CONC: return 5;
            default: return 0;
        }
    }

    public static boolean inSite(int[] s, double x, double y) {
        return x >= s[0] - .5 && y >= s[1] - .5 && x <= s[2] + 1.5 && y <= s[3] + 1.5;
    }

    // 单位碰撞：圆形 AABB 采样
    public static boolean passable(double x, double y, double r) {
        int x0 = (int) Math.floor(x - r), x1 = (int) Math.floor(x + r);
        int y0 = (int) Math.floor(y - r), y1 = (int) Math.floor(y + r);
        for (int yy = y0; yy <= y1; yy++)
            for (int xx = x0; xx <= x1; xx++)
                if (solid(xx, yy)) {
                    double dx = Math.max(Math.abs(x - xx) - .5, 0), dy = Math.max(Math.abs(y - yy) - .5, 0);
                    if (dx * dx + dy * dy < r * r) return false;
                }
        return true;
    }

    // 视线：步进采样
    public static boolean los(double x0, double y0, double x1, double y1) {
        double dx = x1 - x0, dy = y1 - y0;
        double d = Math.hypot(dx, dy);
        if (d < 1e-6) return true;
        double st = 0.12;
        for (double t = st; t < d; t += st) {
            int cx = (int) (x0 + dx * t / d), cy = (int) (y0 + dy * t / d);
            if (solid(cx, cy)) return false;
        }
        return true;
    }

    // BFS 流场：bot 走向目标格
    public static int[] flow(int tx, int ty) {
        int[] dist = new int[W * H];
        java.util.Arrays.fill(dist, -1);
        int[] q = new int[W * H];
        int head = 0, tail = 0;
        if (!free(tx, ty)) { // 目标在墙里就近找
            int[] f = nearestFree(tx, ty);
            if (f == null) return dist;
            tx = f[0]; ty = f[1];
        }
        q[tail++] = ty * W + tx; dist[ty * W + tx] = 0;
        while (head < tail) {
            int c = q[head++], cx = c % W, cy = c / W, nd = dist[c] + 1;
            int[][] d4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int i = 0; i < 4; i++) {
                int nx = cx + d4[i][0], ny = cy + d4[i][1];
                if (!free(nx, ny)) continue;
                int ni = ny * W + nx;
                if (dist[ni] < 0) { dist[ni] = nd; q[tail++] = ni; }
            }
        }
        return dist;
    }

    static int[] nearestFree(int x, int y) {
        for (int r = 0; r < 6; r++)
            for (int dy = -r; dy <= r; dy++)
                for (int dx = -r; dx <= r; dx++)
                    if (free(x + dx, y + dy)) return new int[]{x + dx, y + dy};
        return null;
    }

    public static int[] spawn(int team, java.util.Random rnd) {
        int[] s = team == 0 ? blueSp : redSp;
        if (s.length == 0) return new int[]{W / 2, H / 2};
        int i = rnd.nextInt(s.length / 2) * 2;
        return new int[]{s[i], s[i + 1]};
    }

    // ---- 自检：连通性 + ASCII 预览（JVM 下直接跑）----
    public static void main(String[] a) {
        int[] fl = flow(2, 31);
        int reach = 0, total = 0;
        for (int i = 0; i < G.length; i++) if (G[i] == 0) total++;
        for (int i = 0; i < fl.length; i++) if (fl[i] >= 0) reach++;
        int rc = 0;
        for (int y = BASE_R[1]; y <= BASE_R[3]; y++) for (int x = BASE_R[0]; x <= BASE_R[2]; x++)
            if (free(x, y) && fl[y * W + x] >= 0) rc++;
        System.out.println("空格 " + total + " 可达 " + reach + " 红基地连通格 " + rc + "/" + redSp.length / 2
                + " 蓝出生 " + blueSp.length / 2 + " 红出生 " + redSp.length / 2);
        System.out.println("A 点可达: " + (fl[SITE_A[1] * W + SITE_A[0]] >= 0)
                + " B 点可达: " + (fl[SITE_B[1] * W + SITE_B[0]] >= 0)
                + " 中门两侧: " + (fl[15 * W + 19] >= 0 && fl[15 * W + 22] >= 0));
        for (int y = 0; y < H; y++) {
            StringBuilder sb = new StringBuilder();
            for (int x = 0; x < W; x++) {
                byte t = G[y * W + x];
                char c = t == 0 ? '.' : (t == SAND ? '#' : t == BRICK ? '=' : t == CRATE ? 'C' : t == METAL ? 'M' : t == NET ? 'n' : 'N');
                if (t == 0 && inSite(SITE_A, x, y)) c = 'A';
                if (t == 0 && inSite(SITE_B, x, y)) c = 'B';
                sb.append(c);
            }
            System.out.println(sb);
        }
    }
}
