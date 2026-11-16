#include "native_core.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <ctype.h>

#ifdef __ANDROID__
#include <jni.h>
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "RoooNativeC", __VA_ARGS__)
#else
#define LOGI(...) do { fprintf(stdout, __VA_ARGS__); fprintf(stdout, "\n"); } while (0)
#endif

static char g_db_path[1024] = "accounts.json";
static Account g_accounts[MAX_ACCOUNTS];
static int g_account_count = 0;

static void now_str(char *buf, size_t sz) {
    time_t t = time(NULL);
    struct tm *tm_info = localtime(&t);
    if (tm_info) {
        strftime(buf, sz, "%Y-%m-%d %H:%M:%S", tm_info);
    } else {
        snprintf(buf, sz, "2026-01-01 00:00:00");
    }
}

static void safe_copy(char *dst, size_t dst_sz, const char *src) {
    if (!dst || dst_sz == 0) return;
    if (!src) {
        dst[0] = '\0';
        return;
    }
    strncpy(dst, src, dst_sz - 1);
    dst[dst_sz - 1] = '\0';
}

/* Minimal JSON string unescape & extraction helper */
static const char *skip_ws(const char *p) {
    while (*p && (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n')) p++;
    return p;
}

static const char *parse_json_string(const char *p, char *out, size_t out_sz) {
    if (*p != '"') return NULL;
    p++;
    size_t i = 0;
    while (*p && *p != '"') {
        if (*p == '\\' && *(p + 1)) {
            p++;
            char c = *p;
            if (c == 'n') c = '\n';
            else if (c == 'r') c = '\r';
            else if (c == 't') c = '\t';
            if (i + 1 < out_sz) out[i++] = c;
            p++;
        } else {
            if (i + 1 < out_sz) out[i++] = *p;
            p++;
        }
    }
    if (out_sz > 0) out[i] = '\0';
    if (*p == '"') p++;
    return p;
}

static void json_extract_field(const char *obj_start, const char *obj_end,
                               const char *key, char *out, size_t out_sz) {
    out[0] = '\0';
    char pat[128];
    snprintf(pat, sizeof(pat), "\"%s\"", key);
    size_t pat_len = strlen(pat);

    const char *p = obj_start;
    while (p && p + pat_len < obj_end) {
        const char *hit = strstr(p, pat);
        if (!hit || hit >= obj_end) break;
        const char *q = skip_ws(hit + pat_len);
        if (*q == ':') {
            q = skip_ws(q + 1);
            if (*q == '"') {
                parse_json_string(q, out, out_sz);
                return;
            }
        }
        p = hit + pat_len;
    }
}

void core_init(const char *db_path) {
    if (db_path && db_path[0]) {
        safe_copy(g_db_path, sizeof(g_db_path), db_path);
    }
    core_load_db();
}

int core_load_db(void) {
    FILE *fp = fopen(g_db_path, "rb");
    if (!fp) {
        g_account_count = 0;
        return 0;
    }
    fseek(fp, 0, SEEK_END);
    long fsize = ftell(fp);
    fseek(fp, 0, SEEK_SET);
    if (fsize <= 0 || fsize > 32 * 1024 * 1024) {
        fclose(fp);
        g_account_count = 0;
        return 0;
    }
    char *buf = (char *)malloc((size_t)fsize + 1);
    if (!buf) {
        fclose(fp);
        return -1;
    }
    size_t rd = fread(buf, 1, (size_t)fsize, fp);
    buf[rd] = '\0';
    fclose(fp);

    g_account_count = 0;
    const char *p = buf;
    while (*p && g_account_count < MAX_ACCOUNTS) {
        const char *obj_start = strchr(p, '{');
        if (!obj_start) break;
        /* find matching '}' outside quotes */
        const char *q = obj_start + 1;
        int in_str = 0;
        while (*q) {
            if (*q == '\\' && *(q + 1)) {
                q += 2;
                continue;
            }
            if (*q == '"') in_str = !in_str;
            else if (!in_str && *q == '}') break;
            q++;
        }
        if (*q != '}') break;
        const char *obj_end = q;

        Account *acc = &g_accounts[g_account_count];
        memset(acc, 0, sizeof(Account));
        json_extract_field(obj_start, obj_end, "username", acc->username, sizeof(acc->username));
        json_extract_field(obj_start, obj_end, "password", acc->password, sizeof(acc->password));
        json_extract_field(obj_start, obj_end, "cookie",   acc->cookie,   sizeof(acc->cookie));
        json_extract_field(obj_start, obj_end, "created",  acc->created,  sizeof(acc->created));
        json_extract_field(obj_start, obj_end, "checked",  acc->checked,  sizeof(acc->checked));
        json_extract_field(obj_start, obj_end, "status",   acc->status,   sizeof(acc->status));
        json_extract_field(obj_start, obj_end, "note",     acc->note,     sizeof(acc->note));
        if (!acc->status[0]) safe_copy(acc->status, sizeof(acc->status), "UNKNOWN");

        g_account_count++;
        p = obj_end + 1;
    }

    free(buf);
    return g_account_count;
}

static void append_escaped_json_str(FILE *fp, const char *s) {
    fputc('"', fp);
    if (s) {
        for (; *s; s++) {
            unsigned char c = (unsigned char)*s;
            if (c == '"') fputs("\\\"", fp);
            else if (c == '\\') fputs("\\\\", fp);
            else if (c == '\n') fputs("\\n", fp);
            else if (c == '\r') fputs("\\r", fp);
            else if (c == '\t') fputs("\\t", fp);
            else if (c >= 0x20) fputc(c, fp);
        }
    }
    fputc('"', fp);
}

int core_save_db(void) {
    char tmp_path[1060];
    snprintf(tmp_path, sizeof(tmp_path), "%s.tmp", g_db_path);
    FILE *fp = fopen(tmp_path, "wb");
    if (!fp) return -1;

    fputs("[\n", fp);
    for (int i = 0; i < g_account_count; i++) {
        Account *a = &g_accounts[i];
        fputs("  {\n    \"username\": ", fp);
        append_escaped_json_str(fp, a->username);
        fputs(",\n    \"password\": ", fp);
        append_escaped_json_str(fp, a->password);
        fputs(",\n    \"cookie\": ", fp);
        append_escaped_json_str(fp, a->cookie);
        fputs(",\n    \"created\": ", fp);
        append_escaped_json_str(fp, a->created);
        fputs(",\n    \"status\": ", fp);
        append_escaped_json_str(fp, a->status[0] ? a->status : "UNKNOWN");
        fputs(",\n    \"note\": ", fp);
        append_escaped_json_str(fp, a->note);
        fputs(",\n    \"checked\": ", fp);
        append_escaped_json_str(fp, a->checked);
        fprintf(fp, "\n  }%s\n", (i + 1 < g_account_count) ? "," : "");
    }
    fputs("]\n", fp);
    fclose(fp);

    remove(g_db_path);
    rename(tmp_path, g_db_path);
    return 0;
}

static size_t escaped_len(const char *s) {
    if (!s) return 0;
    size_t n = 0;
    for (; *s; s++) {
        if (*s == '"' || *s == '\\' || *s == '\n' || *s == '\r' || *s == '\t') n += 2;
        else n += 1;
    }
    return n;
}

static char *append_json_kv(char *p, const char *key, const char *val, int comma) {
    p += sprintf(p, "\"%s\":\"", key);
    if (val) {
        for (; *val; val++) {
            unsigned char c = (unsigned char)*val;
            if (c == '"') { *p++ = '\\'; *p++ = '"'; }
            else if (c == '\\') { *p++ = '\\'; *p++ = '\\'; }
            else if (c == '\n') { *p++ = '\\'; *p++ = 'n'; }
            else if (c == '\r') { *p++ = '\\'; *p++ = 'r'; }
            else if (c == '\t') { *p++ = '\\'; *p++ = 't'; }
            else if (c >= 0x20) { *p++ = (char)c; }
        }
    }
    *p++ = '"';
    if (comma) *p++ = ',';
    return p;
}

char *core_get_accounts_json(void) {
    size_t cap = 64;
    for (int i = 0; i < g_account_count; i++) {
        Account *a = &g_accounts[i];
        cap += 256 + escaped_len(a->username) + escaped_len(a->password)
                   + escaped_len(a->cookie) + escaped_len(a->created)
                   + escaped_len(a->checked) + escaped_len(a->status)
                   + escaped_len(a->note);
    }
    char *out = (char *)malloc(cap);
    if (!out) return NULL;
    char *p = out;
    *p++ = '[';
    for (int i = 0; i < g_account_count; i++) {
        if (i > 0) *p++ = ',';
        *p++ = '{';
        Account *a = &g_accounts[i];
        p = append_json_kv(p, "username", a->username, 1);
        p = append_json_kv(p, "password", a->password, 1);
        p = append_json_kv(p, "cookie",   a->cookie,   1);
        p = append_json_kv(p, "created",  a->created,  1);
        p = append_json_kv(p, "checked",  a->checked,  1);
        p = append_json_kv(p, "status",   a->status[0] ? a->status : "UNKNOWN", 1);
        p = append_json_kv(p, "note",     a->note,     0);
        *p++ = '}';
    }
    *p++ = ']';
    *p = '\0';
    return out;
}

int core_get_account(int idx, Account *out_acc) {
    if (idx < 0 || idx >= g_account_count || !out_acc) return -1;
    *out_acc = g_accounts[idx];
    return 0;
}

int core_get_count(void) {
    return g_account_count;
}

int core_add_account(const char *username, const char *password, const char *cookie) {
    if (g_account_count >= MAX_ACCOUNTS) return -1;
    if ((!username || !username[0]) && (!cookie || !cookie[0])) return -1;

    Account *a = &g_accounts[g_account_count++];
    memset(a, 0, sizeof(Account));
    safe_copy(a->username, sizeof(a->username), username);
    safe_copy(a->password, sizeof(a->password), password);
    safe_copy(a->cookie,   sizeof(a->cookie),   cookie);
    now_str(a->created, sizeof(a->created));
    safe_copy(a->status, sizeof(a->status), "UNKNOWN");
    core_save_db();
    return g_account_count - 1;
}

static int has_username(const char *u) {
    if (!u || !u[0]) return 0;
    for (int i = 0; i < g_account_count; i++) {
        if (strcmp(g_accounts[i].username, u) == 0) return 1;
    }
    return 0;
}

static int has_cookie(const char *ck) {
    if (!ck || !ck[0]) return 0;
    for (int i = 0; i < g_account_count; i++) {
        if (strcmp(g_accounts[i].cookie, ck) == 0) return 1;
    }
    return 0;
}

int core_import_text(const char *text) {
    if (!text) return 0;
    int added = 0;
    const char *p = text;

    while (*p && g_account_count < MAX_ACCOUNTS) {
        const char *eol = strpbrk(p, "\r\n");
        size_t len = eol ? (size_t)(eol - p) : strlen(p);

        char line[MAX_COOKIE + 256];
        if (len >= sizeof(line)) len = sizeof(line) - 1;
        memcpy(line, p, len);
        line[len] = '\0';

        /* Trim whitespace and quotes */
        char *s = line;
        while (*s && (*s == ' ' || *s == '\t' || *s == '"' || *s == '\'')) s++;
        size_t slen = strlen(s);
        while (slen > 0 && (s[slen - 1] == ' ' || s[slen - 1] == '\t' ||
                            s[slen - 1] == '"' || s[slen - 1] == '\'')) {
            s[--slen] = '\0';
        }

        if (slen > 0) {
            if (slen > 300 && strstr(s, "_|WARNING") != NULL) {
                /* Raw .ROBLOSECURITY cookie */
                if (!has_cookie(s)) {
                    Account *a = &g_accounts[g_account_count++];
                    memset(a, 0, sizeof(Account));
                    safe_copy(a->cookie, sizeof(a->cookie), s);
                    now_str(a->created, sizeof(a->created));
                    safe_copy(a->status, sizeof(a->status), "UNKNOWN");
                    added++;
                }
            } else {
                char *colon = strchr(s, ':');
                if (colon) {
                    *colon = '\0';
                    char *u = s;
                    char *pw = colon + 1;
                    while (*u == ' ') u++;
                    while (*pw == ' ') pw++;
                    if (*u && !has_username(u)) {
                        Account *a = &g_accounts[g_account_count++];
                        memset(a, 0, sizeof(Account));
                        safe_copy(a->username, sizeof(a->username), u);
                        safe_copy(a->password, sizeof(a->password), pw);
                        now_str(a->created, sizeof(a->created));
                        safe_copy(a->status, sizeof(a->status), "UNKNOWN");
                        added++;
                    }
                } else if (!has_username(s)) {
                    Account *a = &g_accounts[g_account_count++];
                    memset(a, 0, sizeof(Account));
                    safe_copy(a->username, sizeof(a->username), s);
                    now_str(a->created, sizeof(a->created));
                    safe_copy(a->status, sizeof(a->status), "UNKNOWN");
                    added++;
                }
            }
        }

        if (!eol) break;
        p = eol;
        while (*p == '\r' || *p == '\n') p++;
    }

    if (added > 0) {
        core_save_db();
    }
    return added;
}

int core_delete_indices(const int *indices, int count) {
    if (!indices || count <= 0) return 0;
    int removed = 0;
    int mark[MAX_ACCOUNTS];
    memset(mark, 0, sizeof(mark));
    for (int i = 0; i < count; i++) {
        int idx = indices[i];
        if (idx >= 0 && idx < g_account_count) {
            mark[idx] = 1;
        }
    }
    int dst = 0;
    for (int src = 0; src < g_account_count; src++) {
        if (!mark[src]) {
            if (dst != src) g_accounts[dst] = g_accounts[src];
            dst++;
        } else {
            removed++;
        }
    }
    g_account_count = dst;
    if (removed > 0) core_save_db();
    return removed;
}

static int str_icmp(const char *a, const char *b) {
    if (!a || !b) return -1;
    while (*a && *b) {
        int ca = tolower((unsigned char)*a);
        int cb = tolower((unsigned char)*b);
        if (ca != cb) return ca - cb;
        a++;
        b++;
    }
    return tolower((unsigned char)*a) - tolower((unsigned char)*b);
}

int core_store_cookie(int idx, const char *username, const char *cookie,
                      const char *status, const char *note) {
    if (!cookie || strlen(cookie) < 50) return -1;

    int target = (idx >= 0 && idx < g_account_count) ? idx : -1;
    if (target < 0 && username && username[0]) {
        for (int i = 0; i < g_account_count; i++) {
            if (str_icmp(g_accounts[i].username, username) == 0) {
                target = i;
                break;
            }
        }
    }

    if (target < 0) {
        if (!username || !username[0] || g_account_count >= MAX_ACCOUNTS) return -1;
        target = g_account_count++;
        memset(&g_accounts[target], 0, sizeof(Account));
        safe_copy(g_accounts[target].username, sizeof(g_accounts[target].username), username);
        now_str(g_accounts[target].created, sizeof(g_accounts[target].created));
    }

    Account *a = &g_accounts[target];
    safe_copy(a->cookie, sizeof(a->cookie), cookie);
    safe_copy(a->status, sizeof(a->status), (status && status[0]) ? status : "WORK");
    safe_copy(a->note,   sizeof(a->note),   note ? note : "ดึง cookie สำเร็จ");
    now_str(a->checked, sizeof(a->checked));
    core_save_db();
    return target;
}

int core_update_status(int idx, const char *status, const char *note) {
    if (idx < 0 || idx >= g_account_count) return -1;
    Account *a = &g_accounts[idx];
    if (status && status[0]) safe_copy(a->status, sizeof(a->status), status);
    if (note) safe_copy(a->note, sizeof(a->note), note);
    now_str(a->checked, sizeof(a->checked));
    core_save_db();
    return 0;
}

int core_extract_roblosecurity(const char *raw_cookies, char *out_cookie, size_t out_size) {
    if (!out_cookie || out_size == 0) return 0;
    out_cookie[0] = '\0';
    if (!raw_cookies) return 0;

    const char *key = ".ROBLOSECURITY=";
    const char *hit = strstr(raw_cookies, key);
    if (!hit) return 0;
    const char *start = hit + strlen(key);
    if (*start == '"') start++;
    const char *end = start;
    while (*end && *end != ';' && *end != '"' && *end != '\r' && *end != '\n') {
        end++;
    }
    size_t len = (size_t)(end - start);
    if (len < 100) return 0;
    if (len >= out_size) len = out_size - 1;
    memcpy(out_cookie, start, len);
    out_cookie[len] = '\0';
    return (int)len;
}

char *core_build_login_js(const char *username, const char *password) {
    char esc_u[MAX_USERNAME * 2 + 16];
    char esc_p[MAX_PASSWORD * 2 + 16];
    size_t ui = 0, pi = 0;

    if (username) {
        for (const char *s = username; *s && ui + 2 < sizeof(esc_u); s++) {
            if (*s == '\\' || *s == '\'' || *s == '"') esc_u[ui++] = '\\';
            esc_u[ui++] = *s;
        }
    }
    esc_u[ui] = '\0';

    if (password) {
        for (const char *s = password; *s && pi + 2 < sizeof(esc_p); s++) {
            if (*s == '\\' || *s == '\'' || *s == '"') esc_p[pi++] = '\\';
            esc_p[pi++] = *s;
        }
    }
    esc_p[pi] = '\0';

    const char *tmpl =
        "(function(){\n"
        "  function setVal(el, v){\n"
        "    if(!el) return false;\n"
        "    try{ el.focus(); }catch(e){}\n"
        "    try{\n"
        "      var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;\n"
        "      setter.call(el, v);\n"
        "    }catch(e){ el.value = v; }\n"
        "    el.dispatchEvent(new Event('input', {bubbles:true}));\n"
        "    el.dispatchEvent(new Event('change', {bubbles:true}));\n"
        "    return true;\n"
        "  }\n"
        "  var tries = 0;\n"
        "  var timer = setInterval(function(){\n"
        "    tries++;\n"
        "    var u = document.querySelector('#login-username, input[name=\"username\"], input[autocomplete=\"username\"]');\n"
        "    var p = document.querySelector('#login-password, input[name=\"password\"], input[type=\"password\"]');\n"
        "    if(u && p){\n"
        "      clearInterval(timer);\n"
        "      setVal(u, '%s');\n"
        "      setTimeout(function(){\n"
        "        setVal(p, '%s');\n"
        "        setTimeout(function(){\n"
        "          var btn = document.querySelector('#login-button, button[data-testid=\"login-button\"], button[type=\"submit\"]');\n"
        "          if(btn && !btn.disabled) btn.click();\n"
        "        }, 450);\n"
        "      }, 350);\n"
        "    } else if(tries > 40){\n"
        "      clearInterval(timer);\n"
        "    }\n"
        "  }, 400);\n"
        "})();";

    size_t total = strlen(tmpl) + strlen(esc_u) + strlen(esc_p) + 64;
    char *js = (char *)malloc(total);
    if (!js) return NULL;
    snprintf(js, total, tmpl, esc_u, esc_p);
    return js;
}

/* =========================================================================
 * Android JNI Bridge (called by com.rooo.dashboard.NativeBridge)
 * ========================================================================= */
#ifdef __ANDROID__

JNIEXPORT void JNICALL
Java_com_rooo_dashboard_NativeBridge_initDb(JNIEnv *env, jclass clazz, jstring jpath) {
    (void)clazz;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    core_init(path);
    LOGI("Initialized C DB at %s (%d accounts)", path, core_get_count());
    (*env)->ReleaseStringUTFChars(env, jpath, path);
}

JNIEXPORT jstring JNICALL
Java_com_rooo_dashboard_NativeBridge_getAccountsJson(JNIEnv *env, jclass clazz) {
    (void)clazz;
    char *json = core_get_accounts_json();
    if (!json) return (*env)->NewStringUTF(env, "[]");
    jstring res = (*env)->NewStringUTF(env, json);
    free(json);
    return res;
}

JNIEXPORT jint JNICALL
Java_com_rooo_dashboard_NativeBridge_addAccount(JNIEnv *env, jclass clazz,
                                                jstring juser, jstring jpass, jstring jcookie) {
    (void)clazz;
    const char *u  = juser   ? (*env)->GetStringUTFChars(env, juser, NULL)   : "";
    const char *p  = jpass   ? (*env)->GetStringUTFChars(env, jpass, NULL)   : "";
    const char *ck = jcookie ? (*env)->GetStringUTFChars(env, jcookie, NULL) : "";
    int idx = core_add_account(u, p, ck);
    if (juser)   (*env)->ReleaseStringUTFChars(env, juser, u);
    if (jpass)   (*env)->ReleaseStringUTFChars(env, jpass, p);
    if (jcookie) (*env)->ReleaseStringUTFChars(env, jcookie, ck);
    return idx;
}

JNIEXPORT jint JNICALL
Java_com_rooo_dashboard_NativeBridge_importText(JNIEnv *env, jclass clazz, jstring jtext) {
    (void)clazz;
    if (!jtext) return 0;
    const char *txt = (*env)->GetStringUTFChars(env, jtext, NULL);
    int added = core_import_text(txt);
    (*env)->ReleaseStringUTFChars(env, jtext, txt);
    return added;
}

JNIEXPORT jint JNICALL
Java_com_rooo_dashboard_NativeBridge_deleteIndices(JNIEnv *env, jclass clazz, jintArray jindices) {
    (void)clazz;
    if (!jindices) return 0;
    jsize len = (*env)->GetArrayLength(env, jindices);
    jint *elems = (*env)->GetIntArrayElements(env, jindices, NULL);
    int removed = core_delete_indices((const int *)elems, (int)len);
    (*env)->ReleaseIntArrayElements(env, jindices, elems, JNI_ABORT);
    return removed;
}

JNIEXPORT jint JNICALL
Java_com_rooo_dashboard_NativeBridge_storeCookieFromHeader(JNIEnv *env, jclass clazz,
                                                           jint idx, jstring juser,
                                                           jstring jrawCookieHeader,
                                                           jstring jnote) {
    (void)clazz;
    if (!jrawCookieHeader) return -1;
    const char *raw  = (*env)->GetStringUTFChars(env, jrawCookieHeader, NULL);
    const char *user = juser ? (*env)->GetStringUTFChars(env, juser, NULL) : "";
    const char *note = jnote ? (*env)->GetStringUTFChars(env, jnote, NULL) : "ดึงจากหน้าต่างที่เปิดค้างไว้";

    char ck[MAX_COOKIE];
    int len = core_extract_roblosecurity(raw, ck, sizeof(ck));
    int saved_idx = -1;
    if (len > 0) {
        saved_idx = core_store_cookie((int)idx, user, ck, "WORK", note);
    }

    (*env)->ReleaseStringUTFChars(env, jrawCookieHeader, raw);
    if (juser) (*env)->ReleaseStringUTFChars(env, juser, user);
    if (jnote) (*env)->ReleaseStringUTFChars(env, jnote, note);
    return saved_idx;
}

JNIEXPORT void JNICALL
Java_com_rooo_dashboard_NativeBridge_updateStatus(JNIEnv *env, jclass clazz,
                                                  jint idx, jstring jstatus, jstring jnote) {
    (void)clazz;
    const char *st = jstatus ? (*env)->GetStringUTFChars(env, jstatus, NULL) : "UNKNOWN";
    const char *nt = jnote   ? (*env)->GetStringUTFChars(env, jnote, NULL)   : "";
    core_update_status((int)idx, st, nt);
    if (jstatus) (*env)->ReleaseStringUTFChars(env, jstatus, st);
    if (jnote)   (*env)->ReleaseStringUTFChars(env, jnote, nt);
}

JNIEXPORT jstring JNICALL
Java_com_rooo_dashboard_NativeBridge_getAccountField(JNIEnv *env, jclass clazz,
                                                     jint idx, jstring jfield) {
    (void)clazz;
    Account acc;
    if (core_get_account((int)idx, &acc) != 0 || !jfield) {
        return (*env)->NewStringUTF(env, "");
    }
    const char *f = (*env)->GetStringUTFChars(env, jfield, NULL);
    const char *val = "";
    if (strcmp(f, "username") == 0) val = acc.username;
    else if (strcmp(f, "password") == 0) val = acc.password;
    else if (strcmp(f, "cookie") == 0) val = acc.cookie;
    else if (strcmp(f, "status") == 0) val = acc.status;
    (*env)->ReleaseStringUTFChars(env, jfield, f);
    return (*env)->NewStringUTF(env, val);
}

JNIEXPORT jstring JNICALL
Java_com_rooo_dashboard_NativeBridge_buildLoginJs(JNIEnv *env, jclass clazz, jint idx) {
    (void)clazz;
    Account acc;
    if (core_get_account((int)idx, &acc) != 0) {
        return (*env)->NewStringUTF(env, "");
    }
    char *js = core_build_login_js(acc.username, acc.password);
    if (!js) return (*env)->NewStringUTF(env, "");
    jstring res = (*env)->NewStringUTF(env, js);
    free(js);
    return res;
}

#endif /* __ANDROID__ */
