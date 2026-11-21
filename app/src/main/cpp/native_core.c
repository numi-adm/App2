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

/* Case-insensitive substring search in C */
static int str_icontains(const char *haystack, const char *needle) {
    if (!haystack || !needle || !*needle) return 0;
    size_t nlen = strlen(needle);
    for (; *haystack; haystack++) {
        if (tolower((unsigned char)*haystack) == tolower((unsigned char)*needle)) {
            size_t i = 1;
            while (i < nlen && haystack[i] &&
                   tolower((unsigned char)haystack[i]) == tolower((unsigned char)needle[i])) {
                i++;
            }
            if (i == nlen) return 1;
        }
    }
    return 0;
}

/* เหมือน str_icontains แต่ข้ามช่องว่าง/ขึ้นบรรทัดใหม่/อักขระซ่อน
 * (หน้าเว็บ Roblox แบ่งข้อความเป็นหลาย element ทำให้คำไทยขาดกลางบรรทัด) */
static int str_icontains_loose(const char *haystack, const char *needle) {
    if (!haystack || !needle || !*needle) return 0;
    size_t nlen = strlen(needle);
    const char *h = haystack;
    while (*h) {
        const char *hp = h;
        size_t ni = 0;
        while (needle[ni] && *hp) {
            unsigned char nc = (unsigned char)needle[ni];
            /* ช่องว่างในคำค้นหาไม่ต้องไปเทียบกับอะไร (ตัดทิ้งได้) */
            if (nc == ' ' || nc == '\n' || nc == '\r' || nc == '\t' ||
                nc == '\v' || nc == '\f') {
                ni++;
                continue;
            }
            unsigned char hc = (unsigned char)*hp;
            if (hc == ' ' || hc == '\n' || hc == '\r' || hc == '\t' ||
                hc == '\v' || hc == '\f') {
                hp++;
                continue;
            }
            if (hc == 0xC2 && (unsigned char)hp[1] == 0xA0) { hp += 2; continue; } /* NBSP */
            if (hc == 0xE2 && (unsigned char)hp[1] == 0x80 &&
                ((unsigned char)hp[2] == 0x8B || (unsigned char)hp[2] == 0x8C)) {
                hp += 3; /* zero-width space / ZWNJ */
                continue;
            }
            if (tolower(hc) != tolower(nc)) break;
            hp++;
            ni++;
        }
        if (ni == nlen) return 1;
        h++;
    }
    return 0;
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

static int find_username_idx(const char *u) {
    if (!u || !u[0]) return -1;
    for (int i = 0; i < g_account_count; i++) {
        if (str_icmp(g_accounts[i].username, u) == 0) return i;
    }
    return -1;
}

static int find_cookie_idx(const char *ck) {
    if (!ck || !ck[0]) return -1;
    for (int i = 0; i < g_account_count; i++) {
        if (strcmp(g_accounts[i].cookie, ck) == 0) return i;
    }
    return -1;
}

static int upsert_account(const char *username, const char *password, const char *cookie,
                          const char *status, const char *created, const char *checked,
                          const char *note) {
    int idx = find_username_idx(username);
    if (idx < 0 && cookie && cookie[0]) {
        idx = find_cookie_idx(cookie);
    }
    if (idx >= 0) {
        Account *a = &g_accounts[idx];
        if (username && username[0]) safe_copy(a->username, sizeof(a->username), username);
        if (password && password[0]) safe_copy(a->password, sizeof(a->password), password);
        if (cookie && cookie[0])     safe_copy(a->cookie,   sizeof(a->cookie),   cookie);
        if (status && status[0])     safe_copy(a->status,   sizeof(a->status),   status);
        if (checked && checked[0])   safe_copy(a->checked,  sizeof(a->checked),  checked);
        if (note && note[0])         safe_copy(a->note,     sizeof(a->note),     note);
        return 1;
    }
    if (g_account_count >= MAX_ACCOUNTS) return 0;
    if ((!username || !username[0]) && (!cookie || !cookie[0])) return 0;

    Account *a = &g_accounts[g_account_count++];
    memset(a, 0, sizeof(Account));
    safe_copy(a->username, sizeof(a->username), username);
    safe_copy(a->password, sizeof(a->password), password);
    safe_copy(a->cookie,   sizeof(a->cookie),   cookie);
    if (created && created[0]) {
        safe_copy(a->created, sizeof(a->created), created);
    } else {
        now_str(a->created, sizeof(a->created));
    }
    if (checked && checked[0]) safe_copy(a->checked, sizeof(a->checked), checked);
    safe_copy(a->status, sizeof(a->status), (status && status[0]) ? status : "UNKNOWN");
    if (note && note[0]) safe_copy(a->note, sizeof(a->note), note);
    return 1;
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

int core_import_text(const char *text) {
    if (!text) return 0;
    if (strstr(text, "username,password") != NULL || strstr(text, "username\tpassword") != NULL) {
        return core_import_csv(text);
    }
    int added = 0;
    const char *p = text;

    if ((unsigned char)p[0] == 0xEF && (unsigned char)p[1] == 0xBB && (unsigned char)p[2] == 0xBF) {
        p += 3;
    }

    while (*p && g_account_count < MAX_ACCOUNTS) {
        const char *eol = strpbrk(p, "\r\n");
        size_t len = eol ? (size_t)(eol - p) : strlen(p);

        char line[MAX_COOKIE + 512];
        if (len >= sizeof(line)) len = sizeof(line) - 1;
        memcpy(line, p, len);
        line[len] = '\0';

        char *s = line;
        while (*s && (*s == ' ' || *s == '\t' || *s == '"' || *s == '\'')) s++;
        size_t slen = strlen(s);
        while (slen > 0 && (s[slen - 1] == ' ' || s[slen - 1] == '\t' ||
                            s[slen - 1] == '"' || s[slen - 1] == '\'')) {
            s[--slen] = '\0';
        }

        if (slen > 0) {
            if (strncmp(s, "_|WARNING", 9) == 0) {
                added += upsert_account("", "", s, "UNKNOWN", NULL, NULL, NULL);
            } else {
                char *warn = strstr(s, "_|WARNING");
                char ck_part[MAX_COOKIE] = "";
                if (warn && warn > s && (*(warn - 1) == ':' || *(warn - 1) == ',' || *(warn - 1) == '\t')) {
                    safe_copy(ck_part, sizeof(ck_part), warn);
                    *(warn - 1) = '\0';
                }
                char *sep = strchr(s, ':');
                if (!sep) sep = strchr(s, ',');
                if (!sep) sep = strchr(s, '\t');
                if (sep) {
                    *sep = '\0';
                    char *u = s;
                    char *pw = sep + 1;
                    while (*u == ' ') u++;
                    while (*pw == ' ' || *pw == ':' || *pw == ',') pw++;
                    added += upsert_account(u, pw, ck_part, ck_part[0] ? "WORK" : "UNKNOWN", NULL, NULL, NULL);
                } else {
                    added += upsert_account(s, "", ck_part, ck_part[0] ? "WORK" : "UNKNOWN", NULL, NULL, NULL);
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

static int parse_csv_cell(const char **cursor, char *out, size_t out_sz, char delim) {
    const char *p = *cursor;
    if (!p || !*p || *p == '\r' || *p == '\n') {
        if (out_sz > 0) out[0] = '\0';
        return 0;
    }
    size_t i = 0;
    if (*p == '"') {
        p++;
        while (*p) {
            if (*p == '"') {
                if (*(p + 1) == '"') {
                    if (i + 1 < out_sz) out[i++] = '"';
                    p += 2;
                } else {
                    p++;
                    break;
                }
            } else {
                if (i + 1 < out_sz) out[i++] = *p;
                p++;
            }
        }
        while (*p && *p != delim && *p != '\r' && *p != '\n') p++;
        if (*p == delim) p++;
    } else {
        while (*p && *p != delim && *p != '\r' && *p != '\n') {
            if (i + 1 < out_sz) out[i++] = *p;
            p++;
        }
        if (*p == delim) p++;
    }
    if (out_sz > 0) out[i] = '\0';
    *cursor = p;
    return 1;
}

int core_import_csv(const char *csv_text) {
    if (!csv_text) return 0;
    const char *p = csv_text;

    if ((unsigned char)p[0] == 0xEF && (unsigned char)p[1] == 0xBB && (unsigned char)p[2] == 0xBF) {
        p += 3;
    }

    int added = 0;
    int line_no = 0;

    while (*p && g_account_count < MAX_ACCOUNTS) {
        while (*p == '\r' || *p == '\n') p++;
        if (!*p) break;

        const char *line_start = p;
        const char *eol = line_start;
        int in_q = 0;
        int has_tab = 0, has_comma = 0;
        while (*eol && !(!in_q && (*eol == '\r' || *eol == '\n'))) {
            if (*eol == '"') in_q = !in_q;
            else if (!in_q && *eol == '\t') has_tab = 1;
            else if (!in_q && *eol == ',') has_comma = 1;
            eol++;
        }
        line_no++;

        if (!has_comma && !has_tab) {
            size_t llen = (size_t)(eol - line_start);
            char single[MAX_COOKIE + 512];
            if (llen >= sizeof(single)) llen = sizeof(single) - 1;
            memcpy(single, line_start, llen);
            single[llen] = '\0';
            added += core_import_text(single);
            p = eol;
            continue;
        }

        char delim = (has_tab && !has_comma) ? '\t' : ',';
        const char *cur = line_start;
        char c_user[MAX_USERNAME] = "";
        char c_pass[MAX_PASSWORD] = "";
        char c_cookie[MAX_COOKIE] = "";
        char c_status[MAX_STATUS] = "";
        char c_created[MAX_TIME] = "";
        char c_checked[MAX_TIME] = "";
        char c_note[MAX_NOTE] = "";

        parse_csv_cell(&cur, c_user,    sizeof(c_user),    delim);
        parse_csv_cell(&cur, c_pass,    sizeof(c_pass),    delim);
        parse_csv_cell(&cur, c_cookie,  sizeof(c_cookie),  delim);
        parse_csv_cell(&cur, c_status,  sizeof(c_status),  delim);
        parse_csv_cell(&cur, c_created, sizeof(c_created), delim);
        parse_csv_cell(&cur, c_checked, sizeof(c_checked), delim);
        parse_csv_cell(&cur, c_note,    sizeof(c_note),    delim);

        if (line_no == 1 && (str_icmp(c_user, "username") == 0 || str_icmp(c_user, "user") == 0 ||
                             str_icmp(c_user, "ชื่อ") == 0)) {
            p = eol;
            continue;
        }

        if (strncmp(c_pass, "_|WARNING", 9) == 0 && !c_cookie[0]) {
            safe_copy(c_cookie, sizeof(c_cookie), c_pass);
            c_pass[0] = '\0';
        }
        if (!c_pass[0] && strchr(c_user, ':') != NULL && strncmp(c_user, "_|WARNING", 9) != 0) {
            char *cl = strchr(c_user, ':');
            *cl = '\0';
            safe_copy(c_pass, sizeof(c_pass), cl + 1);
        }

        if (c_user[0] || c_cookie[0]) {
            if (!c_status[0]) {
                safe_copy(c_status, sizeof(c_status), c_cookie[0] ? "WORK" : "UNKNOWN");
            }
            added += upsert_account(c_user, c_pass, c_cookie, c_status, c_created, c_checked, c_note);
        }

        p = eol;
    }

    if (added > 0) {
        core_save_db();
    }
    return added;
}

static size_t csv_cell_escaped_len(const char *s) {
    if (!s) return 2;
    size_t n = 2;
    for (; *s; s++) {
        if (*s == '"') n += 2;
        else n += 1;
    }
    return n;
}

static char *append_csv_cell(char *p, const char *val, char tail) {
    *p++ = '"';
    if (val) {
        for (; *val; val++) {
            if (*val == '"') {
                *p++ = '"';
                *p++ = '"';
            } else if (*val == '\r' || *val == '\n') {
                *p++ = ' ';
            } else {
                *p++ = *val;
            }
        }
    }
    *p++ = '"';
    if (tail) *p++ = tail;
    return p;
}

char *core_export_csv(const int *indices, int count) {
    int use_all = (!indices || count <= 0);
    int total = use_all ? g_account_count : count;

    size_t cap = 128;
    for (int k = 0; k < total; k++) {
        int i = use_all ? k : indices[k];
        if (i < 0 || i >= g_account_count) continue;
        Account *a = &g_accounts[i];
        cap += 32
            + csv_cell_escaped_len(a->username)
            + csv_cell_escaped_len(a->password)
            + csv_cell_escaped_len(a->cookie)
            + csv_cell_escaped_len(a->status)
            + csv_cell_escaped_len(a->created)
            + csv_cell_escaped_len(a->checked)
            + csv_cell_escaped_len(a->note);
    }

    char *out = (char *)malloc(cap);
    if (!out) return NULL;
    char *p = out;

    *p++ = (char)0xEF;
    *p++ = (char)0xBB;
    *p++ = (char)0xBF;

    const char *header = "username,password,cookie,status,created,checked,note\r\n";
    memcpy(p, header, strlen(header));
    p += strlen(header);

    for (int k = 0; k < total; k++) {
        int i = use_all ? k : indices[k];
        if (i < 0 || i >= g_account_count) continue;
        Account *a = &g_accounts[i];
        p = append_csv_cell(p, a->username, ',');
        p = append_csv_cell(p, a->password, ',');
        p = append_csv_cell(p, a->cookie,   ',');
        p = append_csv_cell(p, a->status[0] ? a->status : "UNKNOWN", ',');
        p = append_csv_cell(p, a->created,  ',');
        p = append_csv_cell(p, a->checked,  ',');
        p = append_csv_cell(p, a->note,     '\r');
        *p++ = '\n';
    }
    *p = '\0';
    return out;
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

int core_store_cookie(int idx, const char *username, const char *cookie,
                      const char *status, const char *note) {
    if (!cookie || strlen(cookie) < 50) return -1;

    int target = (idx >= 0 && idx < g_account_count) ? idx : -1;
    if (target < 0 && username && username[0]) {
        target = find_username_idx(username);
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

int core_set_username(int idx, const char *username) {
    if (idx < 0 || idx >= g_account_count) return -1;
    if (!username || !username[0]) return -1;
    safe_copy(g_accounts[idx].username, sizeof(g_accounts[idx].username), username);
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

static void escape_js_literal(const char *src, char *dst, size_t dst_size) {
    if (!dst || dst_size < 3) return;
    size_t di = 0;
    dst[di++] = '"';
    if (src) {
        for (const unsigned char *s = (const unsigned char *)src; *s && di + 8 < dst_size; s++) {
            if (*s == '\\') { dst[di++] = '\\'; dst[di++] = '\\'; }
            else if (*s == '"') { dst[di++] = '\\'; dst[di++] = '"'; }
            else if (*s == '\n') { dst[di++] = '\\'; dst[di++] = 'n'; }
            else if (*s == '\r') { dst[di++] = '\\'; dst[di++] = 'r'; }
            else if (*s == '\t') { dst[di++] = '\\'; dst[di++] = 't'; }
            else if (*s < 32) {
                di += snprintf(dst + di, dst_size - di, "\\u%04x", *s);
            } else {
                dst[di++] = (char)*s;
            }
        }
    }
    dst[di++] = '"';
    dst[di] = '\0';
}

char *core_build_login_js(const char *username, const char *password) {
    char esc_u[MAX_USERNAME * 6 + 16];
    char esc_p[MAX_PASSWORD * 6 + 16];
    escape_js_literal(username, esc_u, sizeof(esc_u));
    escape_js_literal(password, esc_p, sizeof(esc_p));

    /*
     * React 17/18 compatible input value setter + multi-strategy Log In button clicker.
     * Verifies that password actually exists in DOM before and after setting it so React
     * re-renders cannot leave the password field blank.
     */
    const char *tmpl =
        "(function(){\n"
        "  var U_VAL = %s;\n"
        "  var P_VAL = %s;\n"
        "  if(window._roooAutoLoginRunning) return;\n"
        "  window._roooAutoLoginRunning = true;\n"
        "  function getU(){\n"
        "    return document.querySelector('#login-username, input[name=\"username\"], input[autocomplete=\"username\"], input#signup-username');\n"
        "  }\n"
        "  function getP(){\n"
        "    return document.querySelector('#login-password, input[name=\"password\"], input[type=\"password\"]');\n"
        "  }\n"
        "  function setReactValue(el, val){\n"
        "    if(!el) return false;\n"
        "    try{ el.focus(); el.click(); }catch(e){}\n"
        "    var last = el.value;\n"
        "    try{\n"
        "      var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;\n"
        "      var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;\n"
        "      setter.call(el, val);\n"
        "    }catch(e){ el.value = val; }\n"
        "    try{\n"
        "      var tracker = el._valueTracker;\n"
        "      if(tracker) tracker.setValue(last);\n"
        "    }catch(e){}\n"
        "    try{ el.dispatchEvent(new InputEvent('input', {bubbles:true, cancelable:true, inputType:'insertText', data:val})); }catch(e){\n"
        "      el.dispatchEvent(new Event('input', {bubbles:true}));\n"
        "    }\n"
        "    el.dispatchEvent(new Event('change', {bubbles:true}));\n"
        "    try{ el.dispatchEvent(new KeyboardEvent('keyup', {bubbles:true})); }catch(e){}\n"
        "    return el.value === val;\n"
        "  }\n"
        "  function clickLoginBtn(){\n"
        "    var pass = getP();\n"
        "    var f = pass ? pass.closest('form') : null;\n"
        "    var scope = f || document;\n"
        "    var re = /^(sign in|signin|log in|login|เข้าสู่ระบบ)$/i;\n"
        "    var reSub = /(sign in|signin|log in|login|เข้าสู่ระบบ)/i;\n"
        "    var btn = document.querySelector('#login-button, button[data-testid=\"login-button\"]');\n"
        "    if(!btn){\n"
        "      var all = Array.from(scope.querySelectorAll('button, input[type=\"submit\"], [role=\"button\"]'));\n"
        "      btn = all.find(function(b){ return (b.offsetWidth||b.offsetHeight) && re.test((b.innerText||b.value||'').trim()); })\n"
        "         || all.find(function(b){ return (b.offsetWidth||b.offsetHeight) && reSub.test((b.innerText||b.value||'').trim()); })\n"
        "         || scope.querySelector('button[type=\"submit\"]');\n"
        "    }\n"
        "    if(btn){\n"
        "      try{ btn.disabled = false; btn.removeAttribute('disabled'); }catch(e){}\n"
        "      ['pointerdown','mousedown','touchstart','pointerup','mouseup','touchend','click'].forEach(function(ev){\n"
        "        try{ btn.dispatchEvent(new MouseEvent(ev, {bubbles:true, cancelable:true, view:window})); }catch(e){}\n"
        "      });\n"
        "      try{ btn.click(); }catch(e){}\n"
        "      return true;\n"
        "    }\n"
        "    if(f){\n"
        "      try{ if(f.requestSubmit) f.requestSubmit(); else f.submit(); return true; }catch(e){}\n"
        "    }\n"
        "    return false;\n"
        "  }\n"
        "  var tries = 0;\n"
        "  var timer = setInterval(function(){\n"
        "    tries++;\n"
        "    var u = getU();\n"
        "    var p = getP();\n"
        "    if(u && !p && tries > 6){\n"
        "      if(u.value !== U_VAL) setReactValue(u, U_VAL);\n"
        "      clickLoginBtn();\n"
        "    }\n"
        "    if(u && p){\n"
        "      clearInterval(timer);\n"
        "      setReactValue(u, U_VAL);\n"
        "      setTimeout(function(){\n"
        "        var freshP = getP() || p;\n"
        "        setReactValue(freshP, P_VAL);\n"
        "        var verifyTries = 0;\n"
        "        var verifyTimer = setInterval(function(){\n"
        "          verifyTries++;\n"
        "          var curU = getU();\n"
        "          var curP = getP();\n"
        "          if(curU && curU.value !== U_VAL) setReactValue(curU, U_VAL);\n"
        "          if(curP && curP.value !== P_VAL) setReactValue(curP, P_VAL);\n"
        "          if((curP && curP.value === P_VAL) || verifyTries > 10){\n"
        "            clearInterval(verifyTimer);\n"
        "            setTimeout(function(){\n"
        "              clickLoginBtn();\n"
        "              var cTries = 0;\n"
        "              var cTimer = setInterval(function(){\n"
        "                cTries++;\n"
        "                var bodyTxt = (document.body ? document.body.innerText : '').toLowerCase();\n"
        "                if(cTries > 6 || !location.pathname.includes('login') || bodyTxt.includes('verify') || bodyTxt.includes('hold') || bodyTxt.includes('bot') || bodyTxt.includes('incorrect') || bodyTxt.includes('unknown') || bodyTxt.includes('error') || bodyTxt.includes('wrong') || bodyTxt.includes('try again') || bodyTxt.includes('step') || bodyTxt.includes('กดค้าง') || bodyTxt.includes('ไม่ถูกต้อง') || bodyTxt.includes('ยืนยัน') || bodyTxt.includes('ข้อผิดพลาด') || bodyTxt.includes('ผิดพลาด')){\n"
        "                  clearInterval(cTimer);\n"
        "                  window._roooAutoLoginRunning = false;\n"
        "                  return;\n"
        "                }\n"
        "                var nowU = getU();\n"
        "                var nowP = getP();\n"
        "                if(nowU && nowU.value !== U_VAL) setReactValue(nowU, U_VAL);\n"
        "                if(nowP && nowP.value !== P_VAL) setReactValue(nowP, P_VAL);\n"
        "                clickLoginBtn();\n"
        "              }, 1200);\n"
        "            }, 300);\n"
        "          }\n"
        "        }, 150);\n"
        "      }, 300);\n"
        "    } else if(tries > 50){\n"
        "      clearInterval(timer);\n"
        "      window._roooAutoLoginRunning = false;\n"
        "    }\n"
        "  }, 300);\n"
        "})();";

    size_t total = strlen(tmpl) + strlen(esc_u) + strlen(esc_p) + 64;
    char *js = (char *)malloc(total);
    if (!js) return NULL;
    snprintf(js, total, tmpl, esc_u, esc_p);
    return js;
}

char *core_classify_page(int idx, const char *url, const char *page_text,
                         int has_arkose, const char *raw_cookies) {
    char ck[MAX_COOKIE] = "";
    int ck_len = core_extract_roblosecurity(raw_cookies, ck, sizeof(ck));

    const char *u = url ? url : "";
    const char *t = page_text ? page_text : "";

    const char *status = "UNKNOWN";
    const char *note = "กำลังตรวจสอบหน้าเว็บ...";

    int on_login = str_icontains(u, "/login") || str_icontains(u, "/signin");
    int on_recovery = str_icontains(u, "recovery") || str_icontains(u, "forgot") ||
                      str_icontains(u, "reset-password") ||
                      str_icontains_loose(t, "recover your account") ||
                      str_icontains_loose(t, "account recovery") ||
                      str_icontains_loose(t, "recovery code") ||
                      str_icontains_loose(t, "use another device") ||
                      str_icontains_loose(t, "verify your identity") ||
                      str_icontains_loose(t, "confirm your identity") ||
                      str_icontains_loose(t, "verify it's you") ||
                      str_icontains_loose(t, "get back into your account") ||
                      str_icontains_loose(t, "กู้คืนบัญชี") ||
                      str_icontains_loose(t, "กู้บัญชี") ||
                      str_icontains_loose(t, "ยืนยันตัวตน") ||
                      str_icontains_loose(t, "ยืนยันว่าเป็นคุณ") ||
                      str_icontains_loose(t, "รหัสกู้คืน") ||
                      str_icontains_loose(t, "ใช้เครื่องอื่น");
    int badpass = str_icontains_loose(t, "incorrect username") ||
                  str_icontains_loose(t, "incorrect password") ||
                  str_icontains_loose(t, "username and/or password") ||
                  str_icontains_loose(t, "you entered is incorrect") ||
                  str_icontains_loose(t, "wrong password") ||
                  str_icontains_loose(t, "invalid password") ||
                  str_icontains_loose(t, "invalid username") ||
                  str_icontains_loose(t, "ชื่อผู้ใช้หรือรหัสผ่าน") ||
                  str_icontains_loose(t, "รหัสผ่านไม่ถูกต้อง") ||
                  str_icontains_loose(t, "ชื่อผู้ใช้ไม่ถูกต้อง") ||
                  (on_login && (str_icontains_loose(t, "we couldn't find") ||
                                str_icontains_loose(t, "doesn't exist") ||
                                str_icontains_loose(t, "does not exist")));
    int locked = str_icontains_loose(t, "account locked") ||
                 str_icontains_loose(t, "account has been locked") ||
                 str_icontains_loose(t, "account was locked") ||
                 str_icontains_loose(t, "unlock your account") ||
                 str_icontains_loose(t, "suspicious activity") ||
                 str_icontains_loose(t, "confirming that you're a human") ||
                 str_icontains_loose(t, "confirm that you're a human") ||
                 str_icontains_loose(t, "บัญชีถูกล็อก") ||
                 str_icontains_loose(t, "บัญชีของคุณถูกล็อก") ||
                 str_icontains_loose(t, "กิจกรรมที่น่าสงสัย") ||
                 str_icontains_loose(t, "ปลดล็อกบัญชี");

    if (str_icontains_loose(t, "banned your account") ||
        str_icontains_loose(t, "account has been banned") ||
        str_icontains_loose(t, "account was banned") ||
        str_icontains_loose(t, "permanently banned") ||
        str_icontains_loose(t, "this account has been banned") ||
        str_icontains_loose(t, "misusing roblox systems") ||
        str_icontains_loose(t, "บัญชีถูกแบน") ||
        str_icontains_loose(t, "ถูกแบน") ||
        str_icontains(u, "not-approved")) {
        status = "BAN";
        note = "ไอดีถูกแบน / ระงับการใช้งาน (BAN)";
    } else if (locked) {
        status = "LOCKED";
        note = "ไอดีถูกล็อก (Account locked / suspicious activity) — กด Continue แล้วยืนยันว่าเป็นคนในหน้าต่าง";
    } else if (badpass) {
        status = "BADPASS";
        note = "รหัสไม่ตรงกับชื่อผู้ใช้ (ชื่อมีอยู่ แต่รหัสผ่านผิด)";
    } else if (on_recovery) {
        status = "RECOVERY";
        note = "ติด Recovery — ต้องกู้บัญชี/ยืนยันตัวตน (อีเมล/รหัสกู้คืน) กดเองในหน้าต่าง";
    } else if (str_icontains_loose(t, "press and hold") ||
               str_icontains_loose(t, "hold the button") ||
               str_icontains_loose(t, "verify you're human") ||
               str_icontains_loose(t, "verify you are human") ||
               str_icontains_loose(t, "verifying you're not a bot") ||
               str_icontains_loose(t, "verifying you are not a bot") ||
               str_icontains_loose(t, "verify you're not a bot") ||
               str_icontains_loose(t, "verify you are not a bot") ||
               str_icontains_loose(t, "you're not a bot") ||
               str_icontains_loose(t, "not a bot") ||
               str_icontains_loose(t, "กำลังตรวจสอบว่าคุณไม่ใช่บอท") ||
               str_icontains_loose(t, "ตรวจสอบว่าคุณไม่ใช่บอท") ||
               str_icontains_loose(t, "ไม่ใช่บอท") ||
               str_icontains_loose(t, "กดค้าง") ||
               str_icontains_loose(t, "ยืนยันว่าคุณเป็นมนุษย์") ||
               str_icontains_loose(t, "ตรวจสอบว่าคุณเป็นมนุษย์") ||
               str_icontains_loose(t, "คุณเป็นมนุษย์")) {
        status = "VERIFY";
        note = "ติดยืนยันตัวตน (Press and hold / Verifying not a bot) — ยืนยันในหน้าต่าง";
    } else if (str_icontains_loose(t, "two-step") ||
               str_icontains_loose(t, "2-step") ||
               str_icontains_loose(t, "two step") ||
               str_icontains_loose(t, "verification code") ||
               str_icontains_loose(t, "enter the code") ||
               str_icontains_loose(t, "enter your code") ||
               str_icontains_loose(t, "security code") ||
               str_icontains_loose(t, "ยืนยันสองขั้นตอน") ||
               str_icontains_loose(t, "การยืนยันสองขั้นตอน") ||
               str_icontains_loose(t, "รหัสยืนยัน") ||
               str_icontains_loose(t, "รหัสความปลอดภัย") ||
               str_icontains_loose(t, "กรอกรหัส")) {
        status = "2FA";
        note = "ติดรหัสยืนยัน 2 ชั้น (2FA / อีเมล)";
    } else if (str_icontains_loose(t, "too many") ||
               str_icontains_loose(t, "throttl") ||
               str_icontains_loose(t, "slow down") ||
               str_icontains_loose(t, "พยายามมากเกินไป") ||
               str_icontains_loose(t, "ทำรายการบ่อยเกินไป")) {
        status = "RATE";
        note = "ติด Rate Limit / Please try again (รอสักครู่หรือเปลี่ยนเน็ต)";
    } else if (str_icontains_loose(t, "an unknown error") ||
               str_icontains_loose(t, "unknown error") ||
               str_icontains_loose(t, "error occurred") ||
               str_icontains_loose(t, "something went wrong") ||
               str_icontains_loose(t, "please try again") ||
               str_icontains_loose(t, "try again") ||
               str_icontains_loose(t, "ข้อผิดพลาดที่ไม่ทราบสาเหตุ") ||
               str_icontains_loose(t, "เกิดข้อผิดพลาดที่ไม่ทราบสาเหตุ") ||
               str_icontains_loose(t, "เกิดข้อผิดพลาดไม่ทราบสาเหตุ") ||
               str_icontains_loose(t, "เกิดข้อผิดพลาด") ||
               str_icontains_loose(t, "ข้อผิดพลาด") ||
               str_icontains_loose(t, "ผิดพลาด") ||
               str_icontains_loose(t, "โปรดลองอีกครั้ง") ||
               str_icontains_loose(t, "ลองอีกครั้งในภายหลัง") ||
               str_icontains_loose(t, "ลองอีกครั้ง") ||
               str_icontains_loose(t, "กรุณาลองใหม่") ||
               str_icontains_loose(t, "ระบบขัดข้อง")) {
        status = "RETRY";
        note = "Roblox ตอบ Something went wrong / เกิดข้อผิดพลาดไม่ทราบสาเหตุ — กำลังลองใหม่อัตโนมัติ";
    } else if (has_arkose ||
               str_icontains_loose(t, "complete the challenge") ||
               str_icontains(u, "challenge")) {
        status = "CAPTCHA";
        note = "ติด Captcha / ตัวต่อปริศนา";
    } else if (ck_len > 100 && !on_login) {
        status = "WORK";
        note = "ใช้งานได้ปกติ — ดึงและบันทึก Cookie สำเร็จแล้ว";
    } else if (str_icontains(u, "/home") || str_icontains(u, "/discover") || str_icontains(u, "/charts")) {
        status = "WORK";
        note = "เข้าหน้า Home สำเร็จ";
    } else if (on_login) {
        status = "UNKNOWN";
        note = "อยู่หน้าล็อกอิน — กำลังกรอก/รอผลจาก Roblox";
    }

    if (idx >= 0 && idx < g_account_count) {
        if (ck_len > 100 && strcmp(status, "WORK") == 0) {
            core_store_cookie(idx, g_accounts[idx].username, ck, status, note);
        } else if (strcmp(status, "UNKNOWN") != 0) {
            core_update_status(idx, status, note);
        }
    }

    /* hasCookie = cookie ที่ล็อกอินจริง (ไม่ใช่ cookie ของ guest ที่ได้มาหลังล็อกอินพลาด) */
    int has_valid_cookie = (ck_len > 100 && strcmp(status, "WORK") == 0) ? 1 : 0;

    char *out = (char *)malloc(1024);
    if (!out) return NULL;
    char *p = out;
    *p++ = '{';
    p = append_json_kv(p, "status", status, 1);
    p = append_json_kv(p, "note", note, 1);
    p += sprintf(p, "\"hasCookie\":%s}", has_valid_cookie ? "true" : "false");
    return out;
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
Java_com_rooo_dashboard_NativeBridge_getAccountCount(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return core_get_count();
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
Java_com_rooo_dashboard_NativeBridge_importCsv(JNIEnv *env, jclass clazz, jstring jcsv) {
    (void)clazz;
    if (!jcsv) return 0;
    const char *csv = (*env)->GetStringUTFChars(env, jcsv, NULL);
    int added = core_import_csv(csv);
    (*env)->ReleaseStringUTFChars(env, jcsv, csv);
    return added;
}

JNIEXPORT jstring JNICALL
Java_com_rooo_dashboard_NativeBridge_exportCsv(JNIEnv *env, jclass clazz, jintArray jindices) {
    (void)clazz;
    char *csv = NULL;
    if (jindices != NULL) {
        jsize len = (*env)->GetArrayLength(env, jindices);
        if (len > 0) {
            jint *elems = (*env)->GetIntArrayElements(env, jindices, NULL);
            csv = core_export_csv((const int *)elems, (int)len);
            (*env)->ReleaseIntArrayElements(env, jindices, elems, JNI_ABORT);
        } else {
            csv = core_export_csv(NULL, 0);
        }
    } else {
        csv = core_export_csv(NULL, 0);
    }
    if (!csv) return (*env)->NewStringUTF(env, "");
    jstring res = (*env)->NewStringUTF(env, csv);
    free(csv);
    return res;
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

JNIEXPORT jint JNICALL
Java_com_rooo_dashboard_NativeBridge_setUsername(JNIEnv *env, jclass clazz,
                                                 jint idx, jstring juser) {
    (void)clazz;
    if (!juser) return -1;
    const char *name = (*env)->GetStringUTFChars(env, juser, NULL);
    int rc = core_set_username((int)idx, name);
    (*env)->ReleaseStringUTFChars(env, juser, name);
    return rc;
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

JNIEXPORT jstring JNICALL
Java_com_rooo_dashboard_NativeBridge_classifyPage(JNIEnv *env, jclass clazz,
                                                  jint idx, jstring jurl,
                                                  jstring jpageText, jboolean hasArkose,
                                                  jstring jrawCookies) {
    (void)clazz;
    const char *url = jurl ? (*env)->GetStringUTFChars(env, jurl, NULL) : "";
    const char *txt = jpageText ? (*env)->GetStringUTFChars(env, jpageText, NULL) : "";
    const char *ck  = jrawCookies ? (*env)->GetStringUTFChars(env, jrawCookies, NULL) : "";

    char *json = core_classify_page((int)idx, url, txt, hasArkose ? 1 : 0, ck);

    if (jurl)        (*env)->ReleaseStringUTFChars(env, jurl, url);
    if (jpageText)   (*env)->ReleaseStringUTFChars(env, jpageText, txt);
    if (jrawCookies) (*env)->ReleaseStringUTFChars(env, jrawCookies, ck);

    if (!json) return (*env)->NewStringUTF(env, "{\"status\":\"UNKNOWN\",\"note\":\"\",\"hasCookie\":false}");
    jstring res = (*env)->NewStringUTF(env, json);
    free(json);
    return res;
}

#endif /* __ANDROID__ */
