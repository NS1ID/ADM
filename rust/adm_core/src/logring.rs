//! A tiny in-process ring buffer logger.
//!
//! The Kotlin side installs the logger during `NativeCore.load()` and can drain
//! recent lines to show a "Native log" viewer in Settings → Advanced. Keeping the
//! implementation self-contained avoids pulling a logging backend into the release
//! binary for the sake of a handful of lines.

use std::collections::VecDeque;
use std::io::Write;
use std::sync::{Mutex, OnceLock};

const CAPACITY: usize = 512;

struct Sink {
    lines: VecDeque<String>,
    file: Option<std::fs::File>,
}

static SINK: OnceLock<Mutex<Sink>> = OnceLock::new();

struct Logger;

impl log::Log for Logger {
    fn enabled(&self, _m: &log::Metadata) -> bool {
        true
    }
    fn log(&self, record: &log::Record) {
        if !self.enabled(record.metadata()) {
            return;
        }
        let line = format!(
            "[{}] {} {}",
            record.level(),
            record.target(),
            record.args()
        );
        if let Some(sink) = SINK.get() {
            if let Ok(mut s) = sink.lock() {
                if s.lines.len() == CAPACITY {
                    s.lines.pop_front();
                }
                if let Some(f) = s.file.as_mut() {
                    let _ = writeln!(f, "{line}");
                }
                s.lines.push_back(line);
            }
        }
    }
    fn flush(&self) {
        if let Some(sink) = SINK.get() {
            if let Ok(mut s) = sink.lock() {
                if let Some(f) = s.file.as_mut() {
                    let _ = f.flush();
                }
            }
        }
    }
}

/// Initialise the global logger once. `log_dir` (from `adm_core_init`) selects an
/// optional on-disk log file named `adm_core.log`.
pub fn init(log_dir: Option<&str>) {
    let file = log_dir.and_then(|d| {
        std::fs::create_dir_all(d).ok()?;
        std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(format!("{d}/adm_core.log"))
            .ok()
    });
    SINK.get_or_init(|| {
        Mutex::new(Sink {
            lines: VecDeque::with_capacity(CAPACITY),
            file,
        })
    });
    // Ignore the error when a logger is already installed.
    let _ = log::set_boxed_logger(Box::new(Logger));
    log::set_max_level(log::LevelFilter::Debug);
    log::info!("adm_core logger ready");
}

/// Return the buffered lines oldest-first, joined by `\n`.
pub fn drain() -> String {
    let Some(sink) = SINK.get() else {
        return String::new();
    };
    match sink.lock() {
        Ok(s) => s.lines.iter().cloned().collect::<Vec<_>>().join("\n"),
        Err(_) => String::new(),
    }
}
