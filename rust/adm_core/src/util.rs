//! Small helpers shared by every FFI module: panic isolation, C-string marshalling
//! and a few numeric utilities. Keeping these in one place means the exported
//! functions in `lib.rs` / `jni_bridge.rs` stay free of `unsafe` details.

use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::OnceLock;

/// Borrow a NUL-terminated C string as an owned Rust `String`.
/// Returns `None` for NULL; invalid UTF-8 is repaired lossily.
///
/// # Safety
/// `p` must be NULL or point to a valid NUL-terminated byte string.
pub unsafe fn cstr_to_string(p: *const c_char) -> Option<String> {
    if p.is_null() {
        return None;
    }
    Some(CStr::from_ptr(p).to_string_lossy().into_owned())
}

/// Move an owned `String` out to the C ABI as a heap `*mut c_char` that the caller
/// releases with [`crate::adm_core_string_free`]. An interior NUL is replaced.
pub fn string_to_c(s: String) -> *mut c_char {
    let cleaned = s.replace('\0', " ");
    match CString::new(cleaned) {
        Ok(c) => c.into_raw(),
        // Unreachable because NULs are stripped above, but never panic here.
        Err(_) => 0 as *mut c_char,
    }
}

/// Convert a `&'static str` into a `*const c_char` without leaking on each call.
/// Only valid for string literals / const data with no interior NUL.
pub fn static_cstr(s: &'static str) -> *const c_char {
    static CACHE: OnceLock<std::sync::Mutex<Vec<(&'static str, CString)>>> = OnceLock::new();
    let cache = CACHE.get_or_init(|| std::sync::Mutex::new(Vec::new()));
    let mut guard = match cache.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    if let Some((_, found)) = guard.iter().find(|(k, _)| *k == s) {
        return found.as_ptr();
    }
    let c = CString::new(s).unwrap_or_default();
    let ptr = c.as_ptr();
    guard.push((s, c));
    ptr
}

/// Run `f`, returning its value; convert any panic into `-1` so that a Rust panic
/// can never cross the FFI boundary and abort the host process.
pub fn guard<F: FnOnce() -> i32>(f: F) -> i32 {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(v) => v,
        Err(_) => {
            log::error!("adm_core: caught panic at FFI boundary");
            -1
        }
    }
}

/// Same as [`guard`] but for functions whose success value is a heap string.
/// Returns NULL on panic.
pub fn guard_json<F: FnOnce() -> String>(f: F) -> *mut c_char {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(s) => string_to_c(s),
        Err(_) => {
            log::error!("adm_core: caught panic at FFI boundary (json)");
            0 as *mut c_char
        }
    }
}

/// Format a byte count the way download managers do: `1.23 GiB`.
pub fn human_bytes(n: u64) -> String {
    const U: [&str; 6] = ["B", "KiB", "MiB", "GiB", "TiB", "PiB"];
    let mut v = n as f64;
    let mut i = 0usize;
    while v >= 1024.0 && i < U.len() - 1 {
        v /= 1024.0;
        i += 1;
    }
    if i == 0 {
        format!("{n} {}", U[i])
    } else {
        format!("{v:.2} {}", U[i])
    }
}
