/*
 * server_main.c - Standalone C HTTP Server using native_core.c
 * Compiles on Android Termux (Linux/POSIX) and Windows (Winsock2):
 *   Termux/Linux: gcc -O2 server_main.c app/src/main/cpp/native_core.c -o rooo_mobile
 *   Windows:      gcc -O2 server_main.c app/src/main/cpp/native_core.c -lws2_32 -o rooo_mobile.exe
 */
#include "app/src/main/cpp/native_core.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
typedef int socklen_t;
#define CLOSESOCK closesocket
#else
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#define CLOSESOCK close
typedef int SOCKET;
#define INVALID_SOCKET (-1)
#endif

#define PORT 8765
#define HTML_PATH "app/src/main/assets/dashboard.html"

static void send_response(SOCKET client, int status, const char *ctype, const char *body, size_t len) {
    char header[512];
    const char *st_txt = (status == 200) ? "OK" : "Not Found";
    int hlen = snprintf(header, sizeof(header),
        "HTTP/1.1 %d %s\r\n"
        "Content-Type: %s\r\n"
        "Content-Length: %zu\r\n"
        "Connection: close\r\n\r\n",
        status, st_txt, ctype, len);
    send(client, header, hlen, 0);
    if (body && len > 0) {
        send(client, body, (int)len, 0);
    }
}

static void send_json(SOCKET client, const char *json) {
    send_response(client, 200, "application/json; charset=utf-8", json, strlen(json));
}

static void extract_json_str(const char *body, const char *key, char *out, size_t out_sz) {
    out[0] = '\0';
    if (!body) return;
    char pat[64];
    snprintf(pat, sizeof(pat), "\"%s\"", key);
    const char *p = strstr(body, pat);
    if (!p) return;
    p = strchr(p + strlen(pat), ':');
    if (!p) return;
    p = strchr(p, '"');
    if (!p) return;
    p++;
    size_t i = 0;
    while (*p && *p != '"' && i + 1 < out_sz) {
        if (*p == '\\' && *(p + 1)) {
            p++;
            if (*p == 'n') out[i++] = '\n';
            else if (*p == 'r') out[i++] = '\r';
            else if (*p == 't') out[i++] = '\t';
            else out[i++] = *p;
            p++;
        } else {
            out[i++] = *p++;
        }
    }
    out[i] = '\0';
}

static int extract_json_int_array(const char *body, const char *key, int *out, int max_n) {
    if (!body) return 0;
    char pat[64];
    snprintf(pat, sizeof(pat), "\"%s\"", key);
    const char *p = strstr(body, pat);
    if (!p) return 0;
    p = strchr(p, '[');
    if (!p) return 0;
    p++;
    int n = 0;
    while (*p && *p != ']' && n < max_n) {
        while (*p && (*p == ' ' || *p == ',')) p++;
        if (*p == ']' || !*p) break;
        out[n++] = atoi(p);
        while (*p && *p != ',' && *p != ']') p++;
    }
    return n;
}

static void handle_client(SOCKET client) {
    char *req = (char *)malloc(262144);
    if (!req) return;
    int rlen = recv(client, req, 262143, 0);
    if (rlen <= 0) {
        free(req);
        return;
    }
    req[rlen] = '\0';

    char method[16] = {0}, path[256] = {0};
    sscanf(req, "%15s %255s", method, path);

    const char *body = strstr(req, "\r\n\r\n");
    if (body) body += 4;
    else body = "";

    if (strcmp(method, "GET") == 0) {
        if (strcmp(path, "/") == 0 || strcmp(path, "/index.html") == 0) {
            FILE *fp = fopen(HTML_PATH, "rb");
            if (!fp) {
                send_response(client, 404, "text/plain", "Not Found", 9);
                free(req);
                return;
            }
            fseek(fp, 0, SEEK_END);
            long sz = ftell(fp);
            fseek(fp, 0, SEEK_SET);
            char *html = (char *)malloc((size_t)sz + 1);
            if (html) {
                size_t rd = fread(html, 1, (size_t)sz, fp);
                send_response(client, 200, "text/html; charset=utf-8", html, rd);
                free(html);
            }
            fclose(fp);
            free(req);
            return;
        }
        if (strcmp(path, "/api/accounts") == 0) {
            char *j = core_get_accounts_json();
            send_json(client, j ? j : "[]");
            free(j);
            free(req);
            return;
        }
        if (strcmp(path, "/api/live") == 0) {
            send_json(client, "{\"open\":[],\"manual\":[]}");
            free(req);
            return;
        }
        if (strncmp(path, "/api/bot_", 9) == 0) {
            if (strcmp(path, "/api/bot_status") == 0) {
                send_json(client, "{\"ok\":false,\"running\":false,\"connected\":false,"
                                  "\"target\":\"android-app\",\"templates\":0,"
                                  "\"releaseTemplates\":0,\"frames\":0,"
                                  "\"last\":\"Auto-Hold Bot รันในแอป Android (NativeApp) เท่านั้น\"}");
            } else if (strcmp(path, "/api/bot_saved_list") == 0) {
                send_json(client, "{\"ok\":true,\"items\":[]}");
            } else if (strcmp(path, "/api/bot_folder") == 0) {
                send_json(client, "{\"ok\":false,\"path\":\"\",\"templatePath\":\"\"}");
            } else if (strcmp(path, "/api/bot_load_config") == 0) {
                send_json(client, "{\"ok\":true,\"config\":{}}");
            } else if (strcmp(path, "/api/bot_log") == 0) {
                send_json(client, "{\"ok\":true,\"text\":\"\"}");
            } else {
                send_json(client, "{\"ok\":false,\"error\":\"Auto-Hold Bot ใช้ได้ในแอป Android (NativeApp) เท่านั้น\"}");
            }
            free(req);
            return;
        }
    } else if (strcmp(method, "POST") == 0) {
        if (strcmp(path, "/api/add") == 0) {
            char u[MAX_USERNAME], p[MAX_PASSWORD];
            extract_json_str(body, "username", u, sizeof(u));
            extract_json_str(body, "password", p, sizeof(p));
            core_add_account(u, p, "");
            send_json(client, "{\"ok\":true}");
            free(req);
            return;
        }
        if (strcmp(path, "/api/import") == 0) {
            char *txt = (char *)malloc(131072);
            int added = 0;
            if (txt) {
                extract_json_str(body, "text", txt, 131072);
                added = core_import_text(txt);
                free(txt);
            }
            char resp[128];
            snprintf(resp, sizeof(resp), "{\"ok\":true,\"added\":%d}", added);
            send_json(client, resp);
            free(req);
            return;
        }
        if (strcmp(path, "/api/import_csv") == 0) {
            char *csv = (char *)malloc(131072);
            int added = 0;
            if (csv) {
                extract_json_str(body, "csv", csv, 131072);
                added = core_import_csv(csv);
                free(csv);
            }
            char resp[128];
            snprintf(resp, sizeof(resp), "{\"ok\":true,\"added\":%d}", added);
            send_json(client, resp);
            free(req);
            return;
        }
        if (strcmp(path, "/api/export_csv") == 0) {
            int idxs[MAX_ACCOUNTS];
            int cnt = extract_json_int_array(body, "indices", idxs, MAX_ACCOUNTS);
            char *csv = core_export_csv(cnt > 0 ? idxs : NULL, cnt);
            if (!csv) {
                send_json(client, "{\"ok\":false}");
            } else {
                size_t clen = strlen(csv);
                char *resp = (char *)malloc(clen * 2 + 64);
                if (resp) {
                    char *q = resp + sprintf(resp, "{\"ok\":true,\"csv\":\"");
                    for (const char *s = csv; *s; s++) {
                        if (*s == '"') { *q++ = '\\'; *q++ = '"'; }
                        else if (*s == '\\') { *q++ = '\\'; *q++ = '\\'; }
                        else if (*s == '\r') { *q++ = '\\'; *q++ = 'r'; }
                        else if (*s == '\n') { *q++ = '\\'; *q++ = 'n'; }
                        else *q++ = *s;
                    }
                    strcpy(q, "\"}");
                    send_json(client, resp);
                    free(resp);
                }
                free(csv);
            }
            free(req);
            return;
        }
        if (strcmp(path, "/api/delete") == 0) {
            int idxs[MAX_ACCOUNTS];
            int cnt = extract_json_int_array(body, "indices", idxs, MAX_ACCOUNTS);
            core_delete_indices(idxs, cnt);
            send_json(client, "{\"ok\":true}");
            free(req);
            return;
        }
        if (strcmp(path, "/api/open") == 0 || strcmp(path, "/api/manual_open") == 0 ||
            strcmp(path, "/api/dump") == 0 || strcmp(path, "/api/close") == 0 ||
            strcmp(path, "/api/grab") == 0) {
            send_json(client, "{\"ok\":true,\"opened\":[],\"failed\":[],\"got\":0,\"port\":\"WebView\"}");
            free(req);
            return;
        }
        /* Auto-Hold Bot: ตรรกะอยู่ในแอป Android (Java + C core) — เซิร์ฟเวอร์ C ล้วน
         * ไม่มี ADB/WebView จึงคืนข้อความอธิบายแทน (UI ใน dashboard.html ยังทำงานได้) */
        if (strncmp(path, "/api/bot_", 9) == 0) {
            send_json(client, "{\"ok\":false,\"error\":\"Auto-Hold Bot ใช้ได้ในแอป Android (NativeApp) — เปิดแอปแล้วเลือกเมนู 🤖 Auto-Hold Bot\"}");
            free(req);
            return;
        }
    }

    send_response(client, 404, "text/plain", "Not Found", 9);
    free(req);
}

int main(void) {
#ifdef _WIN32
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
#endif
    core_init("accounts.json");

    SOCKET srv = socket(AF_INET, SOCK_STREAM, 0);
    if (srv == INVALID_SOCKET) {
        perror("socket");
        return 1;
    }
    int opt = 1;
    setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, (const char *)&opt, sizeof(opt));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(PORT);

    if (bind(srv, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("bind");
        CLOSESOCK(srv);
        return 1;
    }
    listen(srv, 16);
    printf("Roblox Mobile C Server running at http://127.0.0.1:%d (%d accounts)\n",
           PORT, core_get_count());

    while (1) {
        struct sockaddr_in cli_addr;
        socklen_t cli_len = sizeof(cli_addr);
        SOCKET cli = accept(srv, (struct sockaddr *)&cli_addr, &cli_len);
        if (cli == INVALID_SOCKET) continue;
        handle_client(cli);
        CLOSESOCK(cli);
    }
    return 0;
}
