#ifndef BOT_CORE_H
#define BOT_CORE_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * bot_core.h - ตรรกะตรวจจับที่พอร์ตมาจาก FFFFFF/bot.py
 *   - match_templates()  -> bot_match_template()  (NCC แบบ TM_CCOEFF_NORMED)
 *   - find_color()       -> bot_find_color()      (connected components)
 *
 * ภาพทุกภาพอยู่ในรูป RGBA 4 ไบต์ต่อพิกเซล (เช่นผลจาก `adb exec-out screencap`)
 * สีส่งมาเป็น 0x00RRGGBB
 */

typedef struct {
    int x1, y1, x2, y2;   /* x2/y2 เท่ากับ x1/y1 ได้ ถ้าเป็นจุดเดียว */
} BotRect;

/* คืนคะแนน NCC สูงสุด (0..1) และเขียนตำแหน่งที่เจอลง out_rect
 * step = ตัวย่อภาพขั้นต่ำก่อนสแกน (1 = ละเอียดสุดช้าที่สุด, 4 = เร็วขึ้นมาก)
 * ภายในจะย่อแบบ adaptive ตามขนาดต้นแบบ แล้วค่อย refine รอบตำแหน่งที่เจอ */
double bot_match_template(const unsigned char *img, int w, int h,
                          const unsigned char *tpl, int tw, int th,
                          int step, BotRect *out_rect);

/* หากลุ่มพิกเซลสีที่ใหญ่ที่สุด คืนพื้นที่ (0 = ไม่เจอ) และเขียนจุดศูนย์กลาง */
int bot_find_color(const unsigned char *img, int w, int h,
                   unsigned int rgb, int tol, int min_area,
                   int *out_x, int *out_y);

/* ตัด ROI: คืน 1 ถ้าใช้ได้ พร้อม offset และขนาดใหม่ */
int bot_apply_roi(int w, int h, const int roi[4],
                  int *ox, int *oy, int *cw, int *ch);

#ifdef __cplusplus
}
#endif

#endif /* BOT_CORE_H */
