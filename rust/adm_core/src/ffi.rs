//! Plain C ABI for the native core (`libadm_core.so`).
//!
//! This is the *second* way into the same engines the Kotlin layer uses through JNI
//! (see `jni_bridge.rs`): Dart/Flutter `dart:ffi`, a C/C++ host, Python `ctypes`, a
//! Node FFI binding, and so on. The declarations here are the implementations of
//! `include/adm_core.h`; keep the two in step.
//!
//! Conventions of the header apply: every function is thread-safe, string results are
//! heap-allocated and must be released with [`crate::adm_core_string_free`], and every
//! entry point is panic-safe (a panic is caught and reported as `-1` / NULL rather
//! than unwinding across the FFI boundary).

use crate::{adblock, download, proxy, rules, sniffer, util};
use std::collections::HashMap;
use std::os::raw::c_char;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};

/// Registry of live downloads started through the C ABI, keyed by the caller's id.
/// (The JNI bridge keeps its own registry; the two are independent by design, so a
/// Dart/Flutter front-end and the Android app never contend for the same jobs.)
fn jobs() -> &'static Mutex<HashMap<String, Arc<download::Job>>> {
    static JOBS: OnceLock<Mutex<HashMap<String, Arc<download::Job>>>> = OnceLock::new();
    JOBS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Registry of running local proxies started through the C ABI, keyed by port.
fn servers() -> &'static Mutex<HashMap<u16, proxy::Server>> {
    static SERVERS: OnceLock<Mutex<HashMap<u16, proxy::Server>>> = OnceLock::new();
    SERVERS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Lock a global mutex, recovering from poisoning rather than panicking.
fn lock<T>(m: &'static Mutex<T>) -> MutexGuard<'static, T> {
    match m.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    }
}

/// Borrow a C string as an owned `String`; NULL and invalid UTF-8 become "".
fn read_str(p: *const c_char) -> String {
    // SAFETY: the C ABI contract requires `p` to be NULL or a valid NUL-terminated
    // string; `cstr_to_string` handles NULL and repairs invalid UTF-8 lossily.
    unsafe { util::cstr_to_string(p) }.unwrap_or_default()
}

/// Options for [`adm_core_download_start`], matching the JSON the Kotlin layer sends.
#[derive(serde::Deserialize)]
struct StartOpts {
    id: String,
    url: String,
    path: String,
    #[serde(default)]
    connections: usize,
    #[serde(default)]
    proxy: Option<String>,
    #[serde(default)]
    headers: HashMap<String, String>,
    #[serde(default, rename = "userAgent")]
    user_agent: Option<String>,
}

fn start_job(raw: &str) -> Result<(), String> {
    let o: StartOpts = serde_json::from_str(raw).map_err(|e| e.to_string())?;
    let mut headers: Vec<(String, String)> = o.headers.into_iter().collect();
    if let Some(ua) = &o.user_agent {
        headers.push(("User-Agent".into(), ua.clone()));
    }
    let connections = if o.connections == 0 { 8 } else { o.connections };
    let opts = download::StartOptions {
        connections,
        proxy: o.proxy,
        headers,
        user_agent: o.user_agent,
    };
    let job = Arc::new(download::Job::new(o.id.clone(), o.url, PathBuf::from(o.path), opts));
    lock(jobs()).insert(o.id.clone(), Arc::clone(&job));
    std::thread::spawn(move || {
        if let Err(e) = job.run(connections) {
            log::warn!("ffi download {} ended: {e}", job.id);
        }
    });
    Ok(())
}

/// Start a segmented download. Returns 0 on success, negative on failure.
///
/// `opts_json` = `{"id","url","path","connections","proxy","headers":{},"userAgent"}`.
#[no_mangle]
pub extern "C" fn adm_core_download_start(opts_json: *const c_char) -> i32 {
    let raw = read_str(opts_json);
    util::guard(|| match start_job(&raw) {
        Ok(()) => 0,
        Err(e) => {
            log::error!("ffi download start failed: {e}");
            -1
        }
    })
}

/// Progress snapshot as JSON (see `download::Progress`); `{}` when unknown. Caller frees.
#[no_mangle]
pub extern "C" fn adm_core_download_progress(id: *const c_char) -> *mut c_char {
    let id = read_str(id);
    util::guard_json(|| {
        let map = lock(jobs());
        match map.get(&id) {
            Some(job) => serde_json::to_string(&job.progress(0)).unwrap_or_else(|_| "{}".into()),
            None => "{}".into(),
        }
    })
}

/// Control a download: `cmd` = `"pause" | "resume" | "cancel" | "remove"`.
#[no_mangle]
pub extern "C" fn adm_core_download_command(id: *const c_char, cmd: *const c_char) -> i32 {
    let id = read_str(id);
    let cmd = read_str(cmd);
    util::guard(|| match cmd.as_str() {
        "pause" => match lock(jobs()).get(&id).cloned() {
            Some(job) => {
                job.pause();
                0
            }
            None => -1,
        },
        "resume" => match lock(jobs()).get(&id).cloned() {
            Some(job) => {
                job.resume();
                0
            }
            None => -1,
        },
        "cancel" => {
            if let Some(job) = lock(jobs()).get(&id).cloned() {
                job.cancel();
            }
            lock(jobs()).remove(&id);
            0
        }
        "remove" => {
            lock(jobs()).remove(&id);
            0
        }
        _ => -2,
    })
}

/// Suggest a filesystem-safe filename from a URL and optional Content-Disposition.
/// Caller frees the result.
#[no_mangle]
pub extern "C" fn adm_core_suggested_filename(
    url: *const c_char,
    content_disposition: *const c_char,
) -> *mut c_char {
    let url = read_str(url);
    let cd = read_str(content_disposition);
    let cd = if cd.is_empty() { None } else { Some(cd) };
    util::guard_json(|| download::suggested_filename(&url, cd.as_deref()))
}

// ---------------------------------------------------------------------------
// Ad blocking
// ---------------------------------------------------------------------------

/// Load an ABP / hosts list (newline separated). Returns stats JSON. Caller frees.
#[no_mangle]
pub extern "C" fn adm_core_adblock_load(list: *const c_char) -> *mut c_char {
    let text = read_str(list);
    util::guard_json(|| {
        let stats = adblock::load_list(&text);
        serde_json::to_string(&stats).unwrap_or_else(|_| "{}".into())
    })
}

/// 1 = block, 0 = allow.
#[no_mangle]
pub extern "C" fn adm_core_adblock_should_block(
    url: *const c_char,
    page_url: *const c_char,
    resource_type: *const c_char,
) -> i32 {
    let url = read_str(url);
    let page_url = read_str(page_url);
    let resource_type = read_str(resource_type);
    util::guard(|| {
        if adblock::should_block(&url, &page_url, &resource_type) {
            1
        } else {
            0
        }
    })
}

/// Cosmetic CSS for a page. Caller frees.
#[no_mangle]
pub extern "C" fn adm_core_adblock_cosmetic_css(page_url: *const c_char) -> *mut c_char {
    let page_url = read_str(page_url);
    util::guard_json(|| adblock::cosmetic_css(&page_url))
}

/// Blocked / allowed / rule counters as JSON. Caller frees.
#[no_mangle]
pub extern "C" fn adm_core_adblock_stats() -> *mut c_char {
    util::guard_json(|| {
        let stats = adblock::current_stats();
        serde_json::to_string(&stats).unwrap_or_else(|_| "{}".into())
    })
}

/// Drop every loaded filter and reset the counters.
#[no_mangle]
pub extern "C" fn adm_core_adblock_clear() {
    util::guard(|| {
        adblock::clear();
        0
    });
}

// ---------------------------------------------------------------------------
// Rules & userscripts
// ---------------------------------------------------------------------------

/// Apply an array of `UrlRule` objects to a URL. Caller frees the result.
#[no_mangle]
pub extern "C" fn adm_core_apply_rules(rules_json: *const c_char, url: *const c_char) -> *mut c_char {
    let raw = read_str(rules_json);
    let url = read_str(url);
    util::guard_json(|| {
        let parsed: Vec<rules::UrlRule> = serde_json::from_str(&raw).unwrap_or_default();
        let compiled = rules::compile(&parsed);
        let outcome = rules::apply(&compiled, &url);
        serde_json::to_string(&outcome).unwrap_or_else(|_| "{}".into())
    })
}

/// Greasemonkey metadata for a userscript source. Caller frees.
#[no_mangle]
pub extern "C" fn adm_core_parse_userscript(source: *const c_char) -> *mut c_char {
    let src = read_str(source);
    util::guard_json(|| {
        let meta = rules::parse_userscript(&src);
        serde_json::to_string(&meta).unwrap_or_else(|_| "{}".into())
    })
}

/// 1 when the userscript metadata matches the URL.
#[no_mangle]
pub extern "C" fn adm_core_userscript_matches(meta_json: *const c_char, url: *const c_char) -> i32 {
    let raw = read_str(meta_json);
    let url = read_str(url);
    util::guard(|| {
        let meta: rules::UserscriptMeta = serde_json::from_str(&raw).unwrap_or_default();
        if rules::userscript_matches(&meta, &url) {
            1
        } else {
            0
        }
    })
}

// ---------------------------------------------------------------------------
// Media sniffer
// ---------------------------------------------------------------------------

/// Classify a URL (stateless). Caller frees the result.
#[no_mangle]
pub extern "C" fn adm_core_classify(url: *const c_char, mime: *const c_char) -> *mut c_char {
    let url = read_str(url);
    let mime = read_str(mime);
    util::guard_json(|| {
        let item = sniffer::classify(&url, &mime);
        serde_json::to_string(&item).unwrap_or_else(|_| "{}".into())
    })
}

// ---------------------------------------------------------------------------
// Proxy / VPN
// ---------------------------------------------------------------------------

/// Start the local proxy on `port`. Returns `{"ok":true,"listen":"..."}` or
/// `{"ok":false,"error":"..."}` as JSON. Caller frees the result.
#[no_mangle]
pub extern "C" fn adm_core_proxy_start(port: u16, cfg_json: *const c_char) -> *mut c_char {
    let raw = read_str(cfg_json);
    util::guard_json(|| {
        let cfg: proxy::ProxyConfig = serde_json::from_str(&raw).unwrap_or_default();
        if let Err(e) = proxy::validate(&cfg) {
            return serde_json::json!({ "ok": false, "error": e }).to_string();
        }
        match proxy::Server::start(port, cfg) {
            Ok(server) => {
                let addr = server.listen_addr().to_string();
                // Replacing a port stops the previous server first.
                if let Some(old) = lock(servers()).remove(&port) {
                    old.stop();
                }
                lock(servers()).insert(port, server);
                serde_json::json!({ "ok": true, "listen": addr }).to_string()
            }
            Err(e) => serde_json::json!({ "ok": false, "error": e }).to_string(),
        }
    })
}

/// Stop the proxy on `port`, if running.
#[no_mangle]
pub extern "C" fn adm_core_proxy_stop(port: u16) {
    util::guard(|| {
        if let Some(server) = lock(servers()).remove(&port) {
            server.stop();
        }
        0
    });
}

/// Traffic counters as JSON; `{}` when nothing is listening on `port`. Caller frees.
#[no_mangle]
pub extern "C" fn adm_core_proxy_stats(port: u16) -> *mut c_char {
    util::guard_json(|| {
        let map = lock(servers());
        match map.get(&port) {
            Some(server) => serde_json::to_string(&server.stats()).unwrap_or_else(|_| "{}".into()),
            None => "{}".into(),
        }
    })
}
