//! ADM+ native core entry points.
//!
//! All functions exported with `#[no_mangle] extern "C"` are the **FFI** surface
//! consumed by Kotlin through JNI (see `jni_bridge.rs`) *and* by any other host
//! (Dart/Flutter FFI, JS/WASM, C/C++) through the plain C ABI declared in
//! `include/adm_core.h`. Every exported function is defensive: it never unwinds
//! across the FFI boundary and never dereferences a null pointer.

#![allow(clippy::missing_safety_doc)]

pub mod adblock;
pub mod download;
pub mod ffi;
pub mod jni_bridge;
pub mod logring;
pub mod proxy;
pub mod rules;
pub mod sniffer;
pub mod util;

/// Semantic version of the native core, e.g. `"1.0.0"`. The returned pointer is
/// a `'static` NUL-terminated string: the caller must **not** free it.
#[no_mangle]
pub extern "C" fn adm_core_version() -> *const std::os::raw::c_char {
    util::static_cstr(env!("CARGO_PKG_VERSION"))
}

/// Initialise the core. Safe to call multiple times. `log_dir` may be NULL.
///
/// Returns `0` on success, non-zero on failure.
#[no_mangle]
pub extern "C" fn adm_core_init(log_dir: *const std::os::raw::c_char) -> i32 {
    util::guard(|| {
        let dir = util::cstr_to_string(log_dir);
        logring::init(dir.as_deref());
        0
    })
}

/// Free a C string previously returned by *this* library. Passing NULL is a no-op.
///
/// # Safety
/// `ptr` must have come from a `CString::into_raw` inside this library, or be NULL.
#[no_mangle]
pub unsafe extern "C" fn adm_core_string_free(ptr: *mut std::os::raw::c_char) {
    if !ptr.is_null() {
        drop(std::ffi::CString::from_raw(ptr));
    }
}
