#ifndef NATIVE_CORE_H
#define NATIVE_CORE_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MAX_ACCOUNTS 2048
#define MAX_USERNAME 128
#define MAX_PASSWORD 128
#define MAX_COOKIE   4096
#define MAX_STATUS   32
#define MAX_NOTE     512
#define MAX_TIME     32

typedef struct {
    char username[MAX_USERNAME];
    char password[MAX_PASSWORD];
    char cookie[MAX_COOKIE];
    char created[MAX_TIME];
    char checked[MAX_TIME];
    char status[MAX_STATUS];
    char note[MAX_NOTE];
} Account;

/* Initialize storage path (e.g. "/data/data/.../files/accounts.json" or "accounts.json") */
void core_init(const char *db_path);

/* Load and save accounts.json */
int core_load_db(void);
int core_save_db(void);

/* Get all accounts serialized as a JSON array string (caller must free) */
char *core_get_accounts_json(void);

/* Get single account by index (returns 0 on success, -1 if out of range) */
int core_get_account(int idx, Account *out_acc);
int core_get_count(void);

/* Add single account */
int core_add_account(const char *username, const char *password, const char *cookie);

/* Import multi-line text ("user:pass", "user:pass:cookie", or "_|WARNING..." cookie per line). Returns count added/updated. */
int core_import_text(const char *text);

/* Import CSV / Sheet text (columns: username,password,cookie,status,...). Returns count added/updated. */
int core_import_csv(const char *csv_text);

/* Export accounts to CSV string with UTF-8 BOM for Excel/Google Sheets (caller must free).
 * If indices is NULL or count <= 0, exports all accounts. */
char *core_export_csv(const int *indices, int count);

/* Delete accounts by array of indices (sorted or unsorted) */
int core_delete_indices(const int *indices, int count);

/* Update account cookie, status, and note by index (or by username if idx < 0) */
int core_store_cookie(int idx, const char *username, const char *cookie,
                      const char *status, const char *note);

/* Update account status and note */
int core_update_status(int idx, const char *status, const char *note);

/* Set account username (e.g. fill in name after checking a raw cookie) */
int core_set_username(int idx, const char *username);

/* Extract .ROBLOSECURITY value from raw Cookie header string */
int core_extract_roblosecurity(const char *raw_cookies, char *out_cookie, size_t out_size);

/* Build JavaScript auto-login snippet for WebView (caller must free) */
char *core_build_login_js(const char *username, const char *password);

/* Classify Roblox page state (WORK, BAN, VERIFY, CAPTCHA, BADPASS, 2FA, RATE, UNKNOWN)
 * Updates account idx in DB and returns JSON string {"status":"...","note":"...","hasCookie":true/false} (caller must free) */
char *core_classify_page(int idx, const char *url, const char *page_text,
                         int has_arkose, const char *raw_cookies);

#ifdef __cplusplus
}
#endif

#endif /* NATIVE_CORE_H */
