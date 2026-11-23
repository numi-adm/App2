#include "bot_core.h"

#include <stdlib.h>
#include <string.h>
#include <math.h>

/* RGBA -> luminance (เช่นเดียวกับ cv2.cvtColor(BGR2GRAY) แบบเร่ง) */
static unsigned char lum_of(const unsigned char *px) {
    return (unsigned char)((77u * px[0] + 150u * px[1] + 29u * px[2]) >> 8);
}

static void to_gray(const unsigned char *rgba, int w, int h, unsigned char *g) {
    const int n = w * h;
    for (int i = 0; i < n; i++) g[i] = lum_of(rgba + (size_t)i * 4);
}

/* เฉลี่ยบล็อก d x d -> ภาพย่อ (ใช้หาตำแหน่งคร่าว ๆ ก่อนละเอียด) */
static void downsample_gray(const unsigned char *g, int w, int h, int d,
                            unsigned char *out, int ow, int oh) {
    for (int y = 0; y < oh; y++) {
        int sy0 = y * d, sy1 = sy0 + d;
        if (sy1 > h) sy1 = h;
        for (int x = 0; x < ow; x++) {
            int sx0 = x * d, sx1 = sx0 + d;
            if (sx1 > w) sx1 = w;
            unsigned int sum = 0;
            int n = 0;
            for (int yy = sy0; yy < sy1; yy++) {
                for (int xx = sx0; xx < sx1; xx++) {
                    sum += g[(size_t)yy * w + xx];
                    n++;
                }
            }
            out[(size_t)y * ow + x] = n ? (unsigned char)(sum / (unsigned int)n) : 0;
        }
    }
}

/* NCC แบบ TM_CCOEFF_NORMED ในกรอบที่กำหนด (x0..x1, y0..y1) */
static double ncc_scan(const unsigned char *g, int gw, int gh,
                       const unsigned char *t, int tw, int th,
                       int x0, int x1, int y0, int y1,
                       int *bestx, int *besty) {
    double best = 0.0;
    *bestx = x0;
    *besty = y0;
    if (tw <= 0 || th <= 0 || tw > gw || th > gh) return 0.0;
    if (x0 < 0) x0 = 0;
    if (y0 < 0) y0 = 0;
    if (x1 > gw - tw) x1 = gw - tw;
    if (y1 > gh - th) y1 = gh - th;
    if (x0 > x1 || y0 > y1) return 0.0;

    const int tsize = tw * th;
    double tsum = 0.0, tsum2 = 0.0;
    for (int i = 0; i < tsize; i++) {
        double v = t[i];
        tsum += v;
        tsum2 += v * v;
    }
    const double tmean = tsum / tsize;
    const double tvar = tsum2 - tsize * tmean * tmean;
    if (tvar <= 1e-6) return 0.0;

    for (int y = y0; y <= y1; y++) {
        for (int x = x0; x <= x1; x++) {
            double sum = 0.0, sum2 = 0.0, cross = 0.0;
            for (int j = 0; j < th; j++) {
                const unsigned char *row = g + (size_t)(y + j) * gw + x;
                for (int i = 0; i < tw; i++) {
                    double v = row[i];
                    sum += v;
                    sum2 += v * v;
                    cross += v * t[(size_t)j * tw + i];
                }
            }
            const double mean = sum / tsize;
            const double var = sum2 - tsize * mean * mean;
            if (var <= 1e-6) continue;
            double score = (cross - tsize * mean * tmean) / sqrt(var * tvar);
            if (score > best) {
                best = score;
                *bestx = x;
                *besty = y;
            }
        }
    }
    return best;
}

/*
 * สแกนหา template แบบเร็ว 2 ขั้น:
 *   1) ย่อภาพ/ต้นแบบลง d เท่า แล้วหาตำแหน่งคร่าว ๆ (ถูกกว่า d^4 เท่า)
 *   2) ค้นหาละเอียดรอบตำแหน่งนั้นในภาพเต็ม
 * `step` คือระยะย่อขั้นต่ำ (ยิ่งใหญ่ยิ่งเร็ว แต่ความแม่นยำลดลงเล็กน้อย)
 */
double bot_match_template(const unsigned char *img, int w, int h,
                          const unsigned char *tpl, int tw, int th,
                          int step, BotRect *out_rect) {
    if (!img || !tpl || w <= 0 || h <= 0 || tw <= 0 || th <= 0 ||
        tw > w || th > h || !out_rect) {
        return 0.0;
    }
    if (step < 1) step = 1;

    const size_t n = (size_t)w * h;
    const size_t tn = (size_t)tw * th;
    unsigned char *g = (unsigned char *)malloc(n);
    unsigned char *gt = (unsigned char *)malloc(tn);
    if (!g || !gt) {
        free(g);
        free(gt);
        return 0.0;
    }
    to_gray(img, w, h, g);
    to_gray(tpl, tw, th, gt);

    /* เลือกตัวย่อ: ไม่ให้ต้นแบบย่อเล็กเกินไป (ยังต้องมีความคมพอแยกแยะ) */
    int d = step;
    int by_width = tw / 8, by_height = th / 4;
    if (by_width > d) d = by_width;
    if (by_height > d) d = by_height;
    if (d > 16) d = 16;
    if (d < 1) d = 1;

    int dw = (w + d - 1) / d, dh = (h + d - 1) / d;
    int dtw = tw / d, dth = th / d;
    if (dtw < 1) dtw = 1;
    if (dth < 1) dth = 1;
    if (dtw > dw) dtw = dw;
    if (dth > dh) dth = dh;

    double score = 0.0;
    int bx = 0, by = 0;

    if (d == 1 || dtw < 4 || dth < 3) {
        /* ต้นแบบเล็กอยู่แล้ว — สแกนเต็มความละเอียดตรง ๆ */
        score = ncc_scan(g, w, h, gt, tw, th, 0, w - tw, 0, h - th, &bx, &by);
    } else {
        unsigned char *dg = (unsigned char *)malloc((size_t)dw * dh);
        unsigned char *dt = (unsigned char *)malloc((size_t)dtw * dth);
        if (dg && dt) {
            downsample_gray(g, w, h, d, dg, dw, dh);
            downsample_gray(gt, tw, th, d, dt, dtw, dth);
            int cx = 0, cy = 0;
            double coarse = ncc_scan(dg, dw, dh, dt, dtw, dth,
                                     0, dw - dtw, 0, dh - dth, &cx, &cy);
            if (coarse > 0.0) {
                int rx0 = cx * d - d, rx1 = cx * d + d;
                int ry0 = cy * d - d, ry1 = cy * d + d;
                score = ncc_scan(g, w, h, gt, tw, th,
                                 rx0, rx1, ry0, ry1, &bx, &by);
            }
            free(dg);
            free(dt);
        } else {
            free(dg);
            free(dt);
            score = ncc_scan(g, w, h, gt, tw, th, 0, w - tw, 0, h - th, &bx, &by);
        }
    }

    free(g);
    free(gt);
    out_rect->x1 = bx;
    out_rect->y1 = by;
    out_rect->x2 = bx + tw;
    out_rect->y2 = by + th;
    return score;
}

int bot_find_color(const unsigned char *img, int w, int h,
                   unsigned int rgb, int tol, int min_area,
                   int *out_x, int *out_y) {
    if (!img || w <= 0 || h <= 0 || !out_x || !out_y) return 0;
    if (tol < 0) tol = 0;
    if (min_area < 1) min_area = 1;

    const size_t n = (size_t)w * h;
    unsigned char *seen = (unsigned char *)calloc(n, 1);
    int *queue = (int *)malloc(n * sizeof(int));
    if (!seen || !queue) {
        free(seen);
        free(queue);
        return 0;
    }

    const int tr = (int)((rgb >> 16) & 0xFF);
    const int tg = (int)((rgb >> 8) & 0xFF);
    const int tb = (int)(rgb & 0xFF);

    int best_area = 0, best_cx = 0, best_cy = 0;

    for (int sy = 0; sy < h; sy++) {
        for (int sx = 0; sx < w; sx++) {
            const size_t start = (size_t)sy * w + sx;
            if (seen[start]) continue;
            const unsigned char *p = img + start * 4;
            int dr = (int)p[0] - tr, dg = (int)p[1] - tg, db = (int)p[2] - tb;
            if (dr < -tol || dr > tol || dg < -tol || dg > tol || db < -tol || db > tol) {
                seen[start] = 1;
                continue;
            }

            int head = 0, tail = 0;
            queue[tail++] = (int)start;
            seen[start] = 1;
            int left = sx, right = sx, top = sy, bottom = sy;
            int area = 0;

            while (head < tail) {
                int cur = queue[head++];
                int cy = cur / w, cx = cur - cy * w;
                area++;
                if (cx < left) left = cx;
                if (cx > right) right = cx;
                if (cy < top) top = cy;
                if (cy > bottom) bottom = cy;

                for (int dy = -1; dy <= 1; dy++) {
                    int ny = cy + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = cx + dx;
                        if (nx < 0 || nx >= w) continue;
                        size_t ni = (size_t)ny * w + nx;
                        if (seen[ni]) continue;
                        const unsigned char *q = img + ni * 4;
                        int qr = (int)q[0] - tr, qg = (int)q[1] - tg, qb = (int)q[2] - tb;
                        if (qr < -tol || qr > tol || qg < -tol || qg > tol ||
                            qb < -tol || qb > tol) {
                            seen[ni] = 1;
                            continue;
                        }
                        seen[ni] = 1;
                        if (tail < (int)n) queue[tail++] = (int)ni;
                    }
                }
            }

            if (area > best_area) {
                best_area = area;
                best_cx = (left + right) / 2;
                best_cy = (top + bottom) / 2;
            }
        }
    }

    free(seen);
    free(queue);
    if (best_area < min_area) return 0;
    *out_x = best_cx;
    *out_y = best_cy;
    return best_area;
}

int bot_apply_roi(int w, int h, const int roi[4],
                  int *ox, int *oy, int *cw, int *ch) {
    if (!roi || !ox || !oy || !cw || !ch) return 0;
    int x1 = roi[0] < 0 ? 0 : roi[0];
    int y1 = roi[1] < 0 ? 0 : roi[1];
    int x2 = roi[2] > w ? w : roi[2];
    int y2 = roi[3] > h ? h : roi[3];
    if (x1 < 0) x1 = 0;
    if (y1 < 0) y1 = 0;
    if (x2 < 0) x2 = 0;
    if (y2 < 0) y2 = 0;
    if (x2 <= x1 || y2 <= y1 || x1 >= w || y1 >= h) return 0;
    *ox = x1;
    *oy = y1;
    *cw = x2 - x1;
    *ch = y2 - y1;
    return 1;
}
