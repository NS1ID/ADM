//! JNI bridge for the Kotlin `com.adm.core.NativeCore` object.
//!
//! Every method is `@JvmStatic external` on the Kotlin side, so the JNI names are
//! `Java_com_adm_core_NativeCore_<method>`. JSON is used as the interchange format:
//! it keeps the boundary stable, is trivially consumable from Kotlin/Gson and does
//! not require us to hand-manage complex jobject graphs.

use crate::adblock;
use crate::download;
use crate::proxy;
use crate::rules;
use crate::sniffer;
use crate::util;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};

/// Registry of live download jobs, keyed by the id the UI assigned.
static JOBS: OnceLock<Mutex<HashMap<String, std::sync::Arc<download::Job>>>> = OnceLock::new();

fn jobs() -> &'static Mutex<HashMap<String, std::sync::Arc<download::Job>>> {
    JOBS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Registry of running proxy servers (normally at most one).
static SERVERS: OnceLock<Mutex<HashMap<u16, proxy::Server>>> = OnceLock::new();

fn servers() -> &'static Mutex<HashMap<u16, proxy::Server>> {
    SERVERS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(|js| js.into())
}

fn return_string(env: &mut JNIEnv, value: String) -> jstring {
    match env.new_string(value) {
        Ok(s) => s.into_raw(),
        Err(_) => 0 as jstring,
    }
}

// ---------------------------------------------------------------------------
// Core lifecycle
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    log_dir: JString,
) -> jint_ret {
    let dir = jstring_to_string(&mut env, &log_dir);
    util::guard(|| {
        crate::logring::init(dir.as_deref());
        0
    }) as jint_ret
}

#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeVersion(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let v = env!("CARGO_PKG_VERSION").to_string();
    return_string(&mut env, v)
}

#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeLogDrain(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    return_string(&mut env, crate::logring::drain())
}

// ---------------------------------------------------------------------------
// Media sniffer
// ---------------------------------------------------------------------------

/// Classify a single URL; returns `{"candidate":bool,"item":{...}}` as JSON.
#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeSniff(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    mime: JString,
) -> jstring {
    let u = jstring_to_string(&mut env, &url).unwrap_or_default();
    let m = jstring_to_string(&mut env, &mime).unwrap_or_default();
    let json = match sniffer::sniff_and_dedup(&u, &m) {
        Some(item) => serde_json::json!({ "candidate": true, "item": item }).to_string(),
        None => serde_json::json!({ "candidate": false }).to_string(),
    };
    return_string(&mut env, json)
}

/// Classify without de-duplication (stateless preview).
#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeClassify(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    mime: JString,
) -> jstring {
    let u = jstring_to_string(&mut env, &url).unwrap_or_default();
    let m = jstring_to_string(&mut env, &mime).unwrap_or_default();
    let item = sniffer::classify(&u, &m);
    let json = serde_json::to_string(&item).unwrap_or_else(|_| "{}".into());
    return_string(&mut env, json)
}

#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeSnifferReset(
    _env: JNIEnv,
    _class: JClass,
) {
    // The dedup set is global; clear it by re-initialising the OnceLock contents.
    // A dedicated reset lives in sniffer::clear_dedup.
    sniffer::clear_dedup();
}

// ---------------------------------------------------------------------------
// Download engine
// ---------------------------------------------------------------------------

/// Start a download. `opts_json` = `{"id":..,"url":..,"path":..,"connections":N,
/// "proxy":,"headers":{"K":"V"},"userAgent":".."}`.
/// Returns `0` on start; progress is polled through `nativeDownloadProgress`.
#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeDownloadStart(
    mut env: JNIEnv,
    _class: JClass,
    opts_json: JString,
) -> jint_ret {
    let raw = jstring_to_string(&mut env, &opts_json).unwrap_or_default();
    util::guard(|| match start_job(&raw) {
        Ok(()) => 0,
        Err(e) => {
            log::error!("download start failed: {e}");
            -1
        }
    }) as jint_ret
}

fn start_job(raw: &str) -> Result<(), String> {
    #[derive(serde::Deserialize)]
    struct Opts {
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
    let o: Opts = serde_json::from_str(raw).map_err(|e| e.to_string())?;
    let mut headers: Vec<(String, String)> = o.headers.into_iter().collect();
    if let Some(ua) = &o.user_agent {
        headers.push(("User-Agent".into(), ua.clone()));
    }
    let opts = download::StartOptions {
        connections: if o.connections == 0 { 8 } else { o.connections },
        proxy: o.proxy,
        headers,
        user_agent: o.user_agent,
    };
    let job = std::sync::Arc::new(download::Job::new(
        o.id.clone(),
        o.url,
        PathBuf::from(o.path),
        opts,
    ));
    {
        let mut map = jobs().lock().map_err(|_| "lock".to_string())?;
        map.insert(o.id.clone(), std::sync::Arc::clone(&job));
    }
    let connections = opts.connections;
    std::thread::spawn(move || {
        if let Err(e) = job.run(connections) {
            log::warn!("download {} ended: {e}", job.id);
        }
    });
    Ok(())
}

/// Returns the JSON [`download::Progress`] for one id, or `{}` when unknown.
#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeDownloadProgress(
    mut env: JNIEnv,
    _class: JClass,
    id: JString,
) -> jstring {
    let id = jstring_to_string(&mut env, &id).unwrap_or_default();
    let json = {
        let map = match jobs().lock() {
            Ok(g) => g,
            Err(p) => p.into_inner(),
        };
        match map.get(&id) {
            Some(job) => serde_json::to_string(&job.progress(0)).unwrap_or_else(|_| "{}".into()),
            None => "{}".into(),
        }
    };
    return_string(&mut env, json)
}

/// Command: `pause` | `resume` | `cancel` | `remove`.
#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeDownloadCommand(
    mut env: JNIEnv,
    _class: JClass,
    id: JString,
    cmd: JString,
) -> jint_ret {
    let id = jstring_to_string(&mut env, &id).unwrap_or_default();
    let cmd = jstring_to_string(&mut env, &cmd).unwrap_or_default();
    util::guard(|| {
        let mut map = match jobs().lock() {
            Ok(g) => g,
            Err(p) => p.into_inner(),
        };
        let Some(job) = map.get(&id) else { return -1 };
        match cmd.as_str() {
            "pause" => job.pause(),
            "resume" => job.resume(),
            "cancel" => {
                job.cancel();
                map.remove(&id);
            }
            "remove" => {
                map.remove(&id);
            }
            _ => return -2,
        }
        0
    }) as jint_ret
}

/// Suggest a filename from URL + Content-Disposition.
#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeSuggestedFilename(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    disposition: JString,
) -> jstring {
    let u = jstring_to_string(&mut env, &url).unwrap_or_default();
    let cd = jstring_to_string(&mut env, &disposition);
    let name = download::suggested_filename(&u, cd.as_deref());
    return_string(&mut env, name)
}

// ---------------------------------------------------------------------------
// Ad blocking
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeAdblockLoad(
    mut env: JNIEnv,
    _class: JClass,
    list: JString,
) -> jstring {
    let text = jstring_to_string(&mut env, &list).unwrap_or_default();
    let stats = adblock::load_list(&text);
    let json = serde_json::to_string(&stats).unwrap_or_else(|_| "{}".into());
    return_string(&mut env, json)
}

#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeAdblockShouldBlock(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    page_url: JString,
    resource_type: JString,
) -> jboolean_ret {
    let u = jstring_to_string(&mut env, &url).unwrap_or_default();
    let p = jstring_to_string(&mut env, &page_url).unwrap_or_default();
    let t = jstring_to_string(&mut env, &resource_type).unwrap_or_default();
    let blocked = adblock::should_block(&u, &p, &t);
    blocked as jboolean_ret
}

#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeAdblockCosmeticCss(
    mut env: JNIEnv,
    _class: JClass,
    page_url: JString,
) -> jstring {
    let p = jstring_to_string(&mut env, &page_url).unwrap_or_default();
    return_string(&mut env, adblock::cosmetic_css(&p))
}

#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeAdblockStats(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let s = adblock::current_stats();
    let json = serde_json::to_string(&s).unwrap_or_else(|_| "{}".into());
    return_string(&mut env, json)
}

#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeAdblockClear(
    _env: JNIEnv,
    _class: JClass,
) {
    adblock::clear();
}

// ---------------------------------------------------------------------------
// URL rules & userscripts
// ---------------------------------------------------------------------------

/// Apply `rules_json` (array of [`rules::UrlRule`]) to `url`; returns the outcome.
#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeApplyRules(
    mut env: JNIEnv,
    _class: JClass,
    rules_json: JString,
    url: JString,
) -> jstring {
    let raw = jstring_to_string(&mut env, &rules_json).unwrap_or_default();
    let url = jstring_to_string(&mut env, &url).unwrap_or_default();
    let parsed: Vec<rules::UrlRule> = serde_json::from_str(&raw).unwrap_or_default();
    let compiled = rules::compile(&parsed);
    let outcome = rules::apply(&compiled, &url);
    let json = serde_json::to_string(&outcome).unwrap_or_else(|_| "{}".into());
    return_string(&mut env, json)
}

/// Parse a userscript header; returns [`rules::UserscriptMeta`] as JSON.
#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeParseUserscript(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
) -> jstring {
    let src = jstring_to_string(&mut env, &source).unwrap_or_default();
    let meta = rules::parse_userscript(&src);
    let json = serde_json::to_string(&meta).unwrap_or_else(|_| "{}".into());
    return_string(&mut env, json)
}

/// Does `meta_json` run on `url`?
#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeUserscriptMatches(
    mut env: JNIEnv,
    _class: JClass,
    meta_json: JString,
    url: JString,
) -> jboolean_ret {
    let raw = jstring_to_string(&mut env, &meta_json).unwrap_or_default();
    let url = jstring_to_string(&mut env, &url).unwrap_or_default();
    let meta: rules::UserscriptMeta = serde_json::from_str(&raw).unwrap_or_default();
    rules::userscript_matches(&meta, &url) as jboolean_ret
}

// ---------------------------------------------------------------------------
// Proxy / VPN
// ---------------------------------------------------------------------------

/// Start the local proxy on `port`; `cfg_json` = [`proxy::ProxyConfig`].
#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeProxyStart(
    mut env: JNIEnv,
    _class: JClass,
    port: jint_ret,
    cfg_json: JString,
) -> jstring {
    let raw = jstring_to_string(&mut env, &cfg_json).unwrap_or_default();
    let cfg: proxy::ProxyConfig = serde_json::from_str(&raw).unwrap_or_default();
    let json = match proxy::validate(&cfg) {
        Ok(()) => match proxy::Server::start(port as u16, cfg) {
            Ok(server) => {
                let addr = server.listen_addr().to_string();
                let mut map = match servers().lock() {
                    Ok(g) => g,
                    Err(p) => p.into_inner(),
                };
                if let Some(old) = map.remove(&(port as u16)) {
                    old.stop();
                }
                map.insert(port as u16, server);
                serde_json::json!({ "ok": true, "listen": addr }).to_string()
            }
            Err(e) => serde_json::json!({ "ok": false, "error": e }).to_string(),
        },
        Err(e) => serde_json::json!({ "ok": false, "error": e }).to_string(),
    };
    return_string(&mut env, json)
}

#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeProxyStop(
    _env: JNIEnv,
    _class: JClass,
    port: jint_ret,
) {
    let mut map = match servers().lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    if let Some(server) = map.remove(&(port as u16)) {
        server.stop();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_adm_core_NativeCore_nativeProxyStats(
    mut env: JNIEnv,
    _class: JClass,
    port: jint_ret,
) -> jstring {
    let map = match servers().lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    let json = match map.get(&(port as u16)) {
        Some(s) => serde_json::to_string(&s.stats()).unwrap_or_else(|_| "{}".into()),
        None => "{}".into(),
    };
    return_string(&mut env, json)
}

// JNI primitive aliases (kept local so the signatures read clearly).
type jint_ret = jni::sys::jint;
type jboolean_ret = jni::sys::jboolean;
