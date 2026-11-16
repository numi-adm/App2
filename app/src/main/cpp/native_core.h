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

/* Import multi-line text ("user:pass" or "_|WARNING..." cookie per line). Returns count added. */
int core_import_text(const char *text);

/* Delete accounts by array of indices (sorted or unsorted) */
int core_delete_indices(const int *indices, int count);

/* Update account cookie, status, and note by index (or by username if idx < 0) */
int core_store_cookie(int idx, const char *username, const char *cookie,
                      const char *status, const char *note);

/* Update account status and note */
int core_update_status(int idx, const char *status, const char *note);

/* Extract .ROBLOSECURITY value from raw Cookie header string */
int core_extract_roblosecurity(const char *raw_cookies, char *out_cookie, size_t out_size);

/* Build JavaScript auto-login snippet for WebView (caller must free) */
char *core_build_login_js(const char *username, const char *password);

#ifdef __cplusplus
}
#endif

#endif /* NATIVE_CORE_H */
