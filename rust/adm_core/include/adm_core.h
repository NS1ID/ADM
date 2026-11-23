/*
 * adm_core.h — stable C ABI for the ADM+ native core.
 *
 * The same shared library (`libadm_core.so`) is reachable three ways:
 *   1. JNI  — Kotlin calls com.adm.core.NativeCore (see jni_bridge.rs).
 *   2. FFI  — Dart/Flutter `dart:ffi`, Node, Python ctypes … use the functions below.
 *   3. C/C++ — include this header and link against the cdylib/staticlib.
 *
 * Threading: every function is thread-safe. String results are heap-allocated and
 * MUST be released with adm_core_string_free(). Functions documented as returning
 * "static" return a pointer to constant data that must NOT be freed.
 */
#ifndef ADM_CORE_H
#define ADM_CORE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ---- lifecycle ---------------------------------------------------------- */

/* Semantic version, e.g. "1.0.0". STATIC: do not free. */
const char *adm_core_version(void);

/* Initialise logging. `log_dir` may be NULL. Returns 0 on success. */
int32_t adm_core_init(const char *log_dir);

/* Release a string returned by any adm_core_* function. NULL is a no-op. */
void adm_core_string_free(char *ptr);

/* ---- downloads ---------------------------------------------------------- */

/* Start a segmented download from a JSON options object:
 *   {"id","url","path","connections","proxy","headers":{},"userAgent"}
 * Returns 0 on success, negative on failure. */
int32_t adm_core_download_start(const char *opts_json);

/* Progress snapshot as JSON (see download::Progress). Caller frees. */
char *adm_core_download_progress(const char *id);

/* cmd = "pause" | "resume" | "cancel" | "remove". */
int32_t adm_core_download_command(const char *id, const char *cmd);

/* Suggest a filesystem-safe filename. Caller frees. */
char *adm_core_suggested_filename(const char *url, const char *content_disposition);

/* ---- ad blocking -------------------------------------------------------- */

/* Load an ABP/hosts list (newline separated). Returns stats JSON. Caller frees. */
char *adm_core_adblock_load(const char *list);

/* 1 = block, 0 = allow. */
int32_t adm_core_adblock_should_block(const char *url,
                                      const char *page_url,
                                      const char *resource_type);

/* Cosmetic CSS for a page. Caller frees. */
char *adm_core_adblock_cosmetic_css(const char *page_url);

/* Blocked/allowed/rule counters as JSON. Caller frees. */
char *adm_core_adblock_stats(void);

void adm_core_adblock_clear(void);

/* ---- rules & userscripts ------------------------------------------------ */

/* Apply an array of UrlRule objects to a URL. Caller frees the result. */
char *adm_core_apply_rules(const char *rules_json, const char *url);

/* Greasemonkey metadata for a userscript source. Caller frees. */
char *adm_core_parse_userscript(const char *source);

/* 1 when the userscript metadata matches the URL. */
int32_t adm_core_userscript_matches(const char *meta_json, const char *url);

/* ---- media sniffer ------------------------------------------------------ */

/* Classification result (stateless). Caller frees. */
char *adm_core_classify(const char *url, const char *mime);

/* ---- proxy / VPN -------------------------------------------------------- */

/* Start the local proxy. Returns {"ok":true,"listen":"..."} or {"ok":false,...}. */
char *adm_core_proxy_start(uint16_t port, const char *cfg_json);
void adm_core_proxy_stop(uint16_t port);
/* Traffic counters as JSON. Caller frees. */
char *adm_core_proxy_stats(uint16_t port);

#ifdef __cplusplus
}
#endif

#endif /* ADM_CORE_H */
