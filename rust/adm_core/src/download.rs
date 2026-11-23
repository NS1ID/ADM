//! Segmented ("multipart") HTTP download engine.
//!
//! The engine probes the server with a ranged GET to learn the total size and
//! whether byte ranges are supported. When they are, the transfer is split across
//! N workers that each write into a preallocated file at their own offset, which
//! is what makes download managers fast on flaky mobile links. When ranges are not
//! supported the engine falls back to a single stream so downloads still succeed.

use serde::Serialize;
use sha2::{Digest, Sha256};
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use crate::util::human_bytes;

/// Terminal + transient states of one download.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum DownloadState {
    Queued,
    Probing,
    Running,
    Paused,
    Completed,
    Failed,
    Cancelled,
}

/// A single byte range worker.
#[derive(Debug, Clone, Serialize)]
struct Segment {
    start: u64,
    end: u64,
    downloaded: u64,
}

/// Public, serialisable progress snapshot for the UI.
#[derive(Debug, Clone, Serialize)]
pub struct Progress {
    pub id: String,
    pub state: DownloadState,
    pub total: u64,
    pub downloaded: u64,
    pub speed_bps: u64,
    pub eta_secs: u64,
    pub segments: usize,
    pub supports_ranges: bool,
    pub path: String,
    pub error: String,
    pub sha256: String,
}

/// A live, cancellable download job.
pub struct Job {
    pub id: String,
    url: String,
    dest: PathBuf,
    state: Mutex<DownloadState>,
    total: AtomicU64,
    downloaded: AtomicU64,
    cancel: AtomicBool,
    pause: AtomicBool,
    segments: Mutex<Vec<Segment>>,
    supports_ranges: AtomicBool,
    error: Mutex<String>,
    sha256: Mutex<String>,
    /// Optional proxy URL, e.g. `socks5h://127.0.0.1:9050`.
    proxy: Option<String>,
    headers: Vec<(String, String)>,
}

/// Options for starting a job.
#[derive(Debug, Clone, Default)]
pub struct StartOptions {
    pub connections: usize,
    pub proxy: Option<String>,
    pub headers: Vec<(String, String)>,
    pub user_agent: Option<String>,
}

impl Job {
    pub fn new(id: String, url: String, dest: PathBuf, opts: StartOptions) -> Self {
        Job {
            id,
            url,
            dest,
            state: Mutex::new(DownloadState::Queued),
            total: AtomicU64::new(0),
            downloaded: AtomicU64::new(0),
            cancel: AtomicBool::new(false),
            pause: AtomicBool::new(false),
            segments: Mutex::new(Vec::new()),
            supports_ranges: AtomicBool::new(false),
            error: Mutex::new(String::new()),
            sha256: Mutex::new(String::new()),
            proxy: opts.proxy,
            headers: opts.headers,
        }
    }

    fn set_state(&self, s: DownloadState) {
        if let Ok(mut g) = self.state.lock() {
            *g = s;
        }
    }

    pub fn state(&self) -> DownloadState {
        self.state.lock().map(|g| *g).unwrap_or(DownloadState::Failed)
    }

    fn set_error(&self, e: impl Into<String>) {
        if let Ok(mut g) = self.error.lock() {
            *g = e.into();
        }
    }

    /// Cooperative cancellation; workers poll this between chunks.
    pub fn cancel(&self) {
        self.cancel.store(true, Ordering::SeqCst);
    }

    /// Pause: workers stop at the next chunk boundary but keep their offsets, so a
    /// later `resume()` re-probes with `Range: bytes=<already>-` per segment.
    pub fn pause(&self) {
        self.pause.store(true, Ordering::SeqCst);
        self.set_state(DownloadState::Paused);
    }

    pub fn resume(&self) {
        self.pause.store(false, Ordering::SeqCst);
    }

    /// HTTP client honouring the job's proxy and headers.
    fn client(&self) -> Result<reqwest::blocking::Client, String> {
        let mut b = reqwest::blocking::Client::builder()
            .timeout(std::time::Duration::from_secs(60))
            .connect_timeout(std::time::Duration::from_secs(30))
            .danger_accept_invalid_certs(false);
        if let Some(p) = &self.proxy {
            let proxy = reqwest::Proxy::all(p).map_err(|e| e.to_string())?;
            b = b.proxy(proxy);
        }
        if let Some(ua) = self.headers.iter().find(|(k, _)| k.eq_ignore_ascii_case("user-agent")) {
            b = b.user_agent(ua.1.clone());
        }
        b.build().map_err(|e| e.to_string())
    }

    /// Probe the resource: follow a HEAD (falling back to a ranged GET) to obtain
    /// `Content-Length` and `Accept-Ranges`. Populates `total` / `supports_ranges`.
    fn probe(&self) -> Result<(u64, bool), String> {
        self.set_state(DownloadState::Probing);
        let client = self.client()?;
        let mut req = client.head(&self.url);
        for (k, v) in &self.headers {
            req = req.header(k.as_str(), v.as_str());
        }
        match req.send() {
            Ok(resp) if resp.status().is_success() => {
                let total = resp
                    .headers()
                    .get(reqwest::header::CONTENT_LENGTH)
                    .and_then(|v| v.to_str().ok())
                    .and_then(|s| s.parse::<u64>().ok())
                    .unwrap_or(0);
                let ranges = resp
                    .headers()
                    .get(reqwest::header::ACCEPT_RANGES)
                    .and_then(|v| v.to_str().ok())
                    .map(|s| s.to_ascii_lowercase().contains("bytes"))
                    .unwrap_or(false);
                if total > 0 {
                    return Ok((total, ranges));
                }
            }
            _ => {}
        }
        // Fallback: ranged GET that asks for a single byte.
        let mut req = client.get(&self.url).header(reqwest::header::RANGE, "bytes=0-0");
        for (k, v) in &self.headers {
            req = req.header(k.as_str(), v.as_str());
        }
        let resp = req.send().map_err(|e| e.to_string())?;
        let cr = resp
            .headers()
            .get(reqwest::header::CONTENT_RANGE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_string();
        if let Some(total) = parse_content_range_total(&cr) {
            return Ok((total, true));
        }
        let total = resp
            .headers()
            .get(reqwest::header::CONTENT_LENGTH)
            .and_then(|v| v.to_str().ok())
            .and_then(|s| s.parse::<u64>().ok())
            .unwrap_or(0);
        Ok((total, false))
    }

    /// Run to completion (blocking). Intended to be driven from a thread pool by
    /// the Kotlin layer so the UI thread stays responsive.
    pub fn run(&self, connections: usize) -> Result<(), String> {
        if let Some(parent) = self.dest.parent() {
            std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let (total, ranges) = match self.probe() {
            Ok(v) => v,
            Err(e) => {
                self.set_error(&e);
                self.set_state(DownloadState::Failed);
                return Err(e);
            }
        };
        self.total.store(total, Ordering::SeqCst);
        self.supports_ranges.store(ranges, Ordering::SeqCst);
        self.set_state(DownloadState::Running);

        if total == 0 {
            // Unknown length (e.g. streaming response): single stream, no resume.
            return self.download_unknown_length();
        }

        // Preallocate the destination so segment writes can seek freely.
        {
            let f = OpenOptions::new()
                .create(true)
                .write(true)
                .truncate(false)
                .open(&self.dest)
                .map_err(|e| e.to_string())?;
            f.set_len(total).map_err(|e| e.to_string())?;
        }

        let lanes = if ranges { connections.clamp(1, 16) } else { 1 };
        let segs = split_segments(total, lanes);
        let seg_progress: Vec<Arc<AtomicU64>> =
            segs.iter().map(|_| Arc::new(AtomicU64::new(0))).collect();
        {
            let mut g = self.segments.lock().map_err(|_| "lock".to_string())?;
            *g = segs.clone();
        }

        let result: Mutex<Option<String>> = Mutex::new(None);
        std::thread::scope(|scope| {
            for (idx, seg) in segs.iter().enumerate() {
                let counter = Arc::clone(&seg_progress[idx]);
                let err = &result;
                scope.spawn(move || {
                    if let Err(e) = self.run_segment(seg, counter) {
                        if self.cancel.load(Ordering::SeqCst) {
                            return;
                        }
                        let mut g = err.lock().unwrap_or_else(|p| p.into_inner());
                        if g.is_none() {
                            *g = Some(e);
                        }
                    }
                });
            }
        });

        if self.cancel.load(Ordering::SeqCst) {
            self.set_state(DownloadState::Cancelled);
            return Err("cancelled".into());
        }
        if let Some(e) = result.into_inner().unwrap_or_else(|p| p.into_inner()) {
            self.set_error(&e);
            self.set_state(DownloadState::Failed);
            return Err(e);
        }

        // Verify + finalise.
        self.compute_sha256();
        self.set_state(DownloadState::Completed);
        Ok(())
    }

    /// Single-stream fallback for chunked/unknown-length responses.
    fn download_unknown_length(&self) -> Result<(), String> {
        let client = self.client()?;
        let mut req = client.get(&self.url);
        for (k, v) in &self.headers {
            req = req.header(k.as_str(), v.as_str());
        }
        let mut resp = req.send().map_err(|e| e.to_string())?;
        self.set_state(DownloadState::Running);
        let mut file = File::create(&self.dest).map_err(|e| e.to_string())?;
        let mut buf = [0u8; 64 * 1024];
        loop {
            if self.cancel.load(Ordering::SeqCst) {
                self.set_state(DownloadState::Cancelled);
                return Err("cancelled".into());
            }
            while self.pause.load(Ordering::SeqCst) {
                std::thread::sleep(std::time::Duration::from_millis(200));
                if self.cancel.load(Ordering::SeqCst) {
                    return Err("cancelled".into());
                }
            }
            let n = resp.read(&mut buf).map_err(|e| e.to_string())?;
            if n == 0 {
                break;
            }
            file.write_all(&buf[..n]).map_err(|e| e.to_string())?;
            self.downloaded.fetch_add(n as u64, Ordering::SeqCst);
        }
        file.flush().ok();
        self.compute_sha256();
        self.set_state(DownloadState::Completed);
        Ok(())
    }

    /// One worker: GET with a `Range` header, seek to the segment start and stream
    /// until the segment end (or cancellation), updating shared counters.
    fn run_segment(&self, seg: &Segment, counter: Arc<AtomicU64>) -> Result<(), String> {
        let client = self.client()?;
        let mut req = client.get(&self.url);
        let range = format!("bytes={}-{}", seg.start + seg.downloaded, seg.end);
        req = req.header(reqwest::header::RANGE, range);
        for (k, v) in &self.headers {
            req = req.header(k.as_str(), v.as_str());
        }
        let resp = req.send().map_err(|e| e.to_string())?;
        let status = resp.status();
        if !status.is_success() {
            return Err(format!("HTTP {status}"));
        }
        let mut file = OpenOptions::new()
            .write(true)
            .open(&self.dest)
            .map_err(|e| e.to_string())?;
        file.seek(SeekFrom::Start(seg.start + seg.downloaded))
            .map_err(|e| e.to_string())?;

        let mut buf = [0u8; 64 * 1024];
        let mut body = resp;
        let mut local = seg.downloaded;
        loop {
            if self.cancel.load(Ordering::SeqCst) {
                return Err("cancelled".into());
            }
            while self.pause.load(Ordering::SeqCst) {
                std::thread::sleep(std::time::Duration::from_millis(200));
                if self.cancel.load(Ordering::SeqCst) {
                    return Err("cancelled".into());
                }
            }
            let want = ((seg.end - seg.start + 1) - local) as usize;
            if want == 0 {
                break;
            }
            let cap = want.min(buf.len());
            let n = body.read(&mut buf[..cap]).map_err(|e| e.to_string())?;
            if n == 0 {
                break;
            }
            file.write_all(&buf[..n]).map_err(|e| e.to_string())?;
            local += n as u64;
            counter.store(local, Ordering::SeqCst);
            self.downloaded.fetch_add(n as u64, Ordering::SeqCst);
        }
        file.flush().ok();
        Ok(())
    }

    fn compute_sha256(&self) {
        if self.dest.metadata().map(|m| m.len()).unwrap_or(0) == 0 {
            return;
        }
        let mut hasher = Sha256::new();
        if let Ok(mut f) = File::open(&self.dest) {
            let mut buf = [0u8; 128 * 1024];
            while let Ok(n) = f.read(&mut buf) {
                if n == 0 {
                    break;
                }
                hasher.update(&buf[..n]);
            }
            let digest = hasher.finalize();
            let hex: String = digest.iter().map(|b| format!("{b:02x}")).collect();
            if let Ok(mut g) = self.sha256.lock() {
                *g = hex;
            }
        }
    }

    pub fn progress(&self, speed_bps: u64) -> Progress {
        let total = self.total.load(Ordering::SeqCst);
        let downloaded = self.downloaded.load(Ordering::SeqCst);
        let eta = if speed_bps > 0 && total > downloaded {
            (total - downloaded) / speed_bps
        } else {
            0
        };
        let segments = self.segments.lock().map(|g| g.len()).unwrap_or(0);
        Progress {
            id: self.id.clone(),
            state: self.state(),
            total,
            downloaded,
            speed_bps,
            eta_secs: eta,
            segments,
            supports_ranges: self.supports_ranges.load(Ordering::SeqCst),
            path: self.dest.to_string_lossy().into_owned(),
            error: self.error.lock().map(|g| g.clone()).unwrap_or_default(),
            sha256: self.sha256.lock().map(|g| g.clone()).unwrap_or_default(),
        }
    }
}

/// Partition `[0, total)` into `lanes` contiguous ranges. The last lane absorbs the
/// remainder so no byte is missed.
pub fn split_segments(total: u64, lanes: usize) -> Vec<Segment> {
    let lanes = lanes.max(1) as u64;
    if total == 0 {
        return Vec::new();
    }
    let chunk = total.div_ceil(lanes);
    let mut out = Vec::new();
    let mut start = 0u64;
    while start < total {
        let end = (start + chunk - 1).min(total - 1);
        out.push(Segment {
            start,
            end,
            downloaded: 0,
        });
        start = end + 1;
    }
    out
}

/// Parse `bytes 0-99/12345` → `12345`.
fn parse_content_range_total(cr: &str) -> Option<u64> {
    let after_slash = cr.split('/').nth(1)?;
    after_slash.trim().parse::<u64>().ok()
}

/// One-line summary used in notification text.
pub fn summary(p: &Progress) -> String {
    format!(
        "{} / {} ({} B/s, ETA {}s)",
        human_bytes(p.downloaded),
        human_bytes(p.total),
        p.speed_bps,
        p.eta_secs
    )
}

/// Suggest a filesystem-safe filename for a URL.
pub fn suggested_filename(url: &str, content_disposition: Option<&str>) -> String {
    if let Some(cd) = content_disposition {
        if let Some(name) = parse_disposition_filename(cd) {
            return sanitize(&name);
        }
    }
    let path = url.split(['?', '#']).next().unwrap_or(url);
    let last = path.rsplit('/').next().unwrap_or("download");
    let decoded = percent_encoding::percent_decode_str(last)
        .decode_utf8_lossy()
        .to_string();
    if decoded.is_empty() {
        "download".into()
    } else {
        sanitize(&decoded)
    }
}

fn parse_disposition_filename(cd: &str) -> Option<String> {
    // Handles both `filename="x"` and RFC 5987 `filename*=UTF-8''x`.
    for part in cd.split(';') {
        let p = part.trim();
        if let Some(v) = p.strip_prefix("filename*=") {
            let v = v.splitn(3, '\'').nth(2).unwrap_or(v);
            return Some(
                percent_encoding::percent_decode_str(v)
                    .decode_utf8_lossy()
                    .to_string(),
            );
        }
        if let Some(v) = p.strip_prefix("filename=") {
            return Some(v.trim_matches('"').to_string());
        }
    }
    None
}

fn sanitize(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .map(|c| match c {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' | '\0' => '_',
            _ if (c as u32) < 0x20 => '_',
            _ => c,
        })
        .collect();
    let trimmed = cleaned.trim_matches(['.', ' ']).to_string();
    if trimmed.is_empty() {
        "download".into()
    } else {
        trimmed
    }
}

/// Resume helper: how many bytes are already on disk for `path`.
pub fn existing_bytes(path: impl AsRef<Path>) -> u64 {
    path.as_ref().metadata().map(|m| m.len()).unwrap_or(0)
}
