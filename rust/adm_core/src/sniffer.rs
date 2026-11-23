//! Media / link sniffer.
//!
//! The browser hands every observed URL here (Chromium's resource interceptor and
//! the content sniffer). We classify it into a *download candidate*
//! with a media kind, guessed extension and a relevance score, and we de-duplicate
//! noisy tracking endpoints. This is deliberately heuristic: real pages fire dozens
//! of `.js`/`.css`/beacon requests that must not reach the download UI.

use serde::Serialize;
use std::collections::HashSet;
use std::sync::Mutex;
use std::sync::OnceLock;

/// Media category for a sniffed URL.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum MediaKind {
    Video,
    Audio,
    Image,
    Document,
    Archive,
    App,
    Hls,
    Dash,
    Streaming,
    Unknown,
}

/// A single sniffed item, serialised to JSON for the Kotlin layer.
#[derive(Debug, Clone, Serialize)]
pub struct SniffedMedia {
    pub url: String,
    pub kind: MediaKind,
    pub ext: String,
    pub mime: String,
    pub score: i32,
    pub size_hint: u64,
    pub title: String,
}

impl SniffedMedia {
    fn base(url: &str) -> Self {
        SniffedMedia {
            url: url.to_string(),
            kind: MediaKind::Unknown,
            ext: String::new(),
            mime: String::new(),
            score: 0,
            size_hint: 0,
            title: String::new(),
        }
    }
}

/// Candidate extensions grouped by category, best-first so `ext_for` is cheap.
const VIDEO_EXT: &[&str] = &[
    "mp4", "mkv", "webm", "avi", "mov", "flv", "wmv", "m4v", "mpg", "mpeg", "3gp", "ts", "m2ts",
];
const AUDIO_EXT: &[&str] = &["mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr"];
const IMAGE_EXT: &[&str] = &["jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "avif", "heic"];
const DOC_EXT: &[&str] = &["pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub", "txt", "csv"];
const ARCHIVE_EXT: &[&str] = &["zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "tgz"];
const APP_EXT: &[&str] = &["apk", "apks", "xapk", "obb", "ipa", "dmg", "exe", "msi", "deb", "rpm"];

/// Endpoints that never represent user intent and only add noise.
const NOISE_PATTERNS: &[&str] = &[
    "google-analytics",
    "googletagmanager",
    "doubleclick",
    "scorecardresearch",
    "facebook.com/tr",
    "analytics",
    "beacon",
    "pixel.gif",
    "/collect?",
    "adservice",
    "adsystem",
    "adserver",
    "/pagead/",
    "favicon.ico",
    "sentry.io",
    "crashlytics",
];

static SEEN: OnceLock<Mutex<HashSet<String>>> = OnceLock::new();

fn seen() -> &'static Mutex<HashSet<String>> {
    SEEN.get_or_init(|| Mutex::new(HashSet::new()))
}

/// Build the media kind/extension/score for one URL.
pub fn classify(url: &str, mime_hint: &str) -> SniffedMedia {
    let mut m = SniffedMedia::base(url);
    let lower = url.to_ascii_lowercase();
    let path = lower.split(['?', '#']).next().unwrap_or(&lower);

    let ext = path
        .rsplit('/')
        .next()
        .and_then(|seg| seg.rsplit_once('.'))
        .map(|(_, e)| e.to_string())
        .unwrap_or_default();

    // Streaming manifests get their own kinds because they need a different
    // download strategy (segment fetching) rather than a single GET.
    if ext == "m3u8" || mime_hint.contains("mpegurl") {
        m.kind = MediaKind::Hls;
        m.ext = "m3u8".into();
        m.mime = "application/vnd.apple.mpegurl".into();
        m.score = 90;
        return m;
    }
    if ext == "mpd" || mime_hint.contains("dash+xml") {
        m.kind = MediaKind::Dash;
        m.ext = "mpd".into();
        m.mime = "application/dash+xml".into();
        m.score = 90;
        return m;
    }
    if lower.contains(".ism/manifest") || lower.contains("manifest(format=m3u8") {
        m.kind = MediaKind::Streaming;
        m.ext = "m3u8".into();
        m.score = 80;
        return m;
    }

    m.kind = if VIDEO_EXT.contains(&ext.as_str()) {
        MediaKind::Video
    } else if AUDIO_EXT.contains(&ext.as_str()) {
        MediaKind::Audio
    } else if IMAGE_EXT.contains(&ext.as_str()) {
        MediaKind::Image
    } else if DOC_EXT.contains(&ext.as_str()) {
        MediaKind::Document
    } else if ARCHIVE_EXT.contains(&ext.as_str()) {
        MediaKind::Archive
    } else if APP_EXT.contains(&ext.as_str()) {
        MediaKind::App
    } else {
        MediaKind::Unknown
    };
    m.ext = ext;

    // MIME fallback when the URL has no usable extension.
    if m.kind == MediaKind::Unknown && !mime_hint.is_empty() {
        m.kind = kind_from_mime(mime_hint);
        m.mime = mime_hint.to_string();
        if m.ext.is_empty() {
            m.ext = ext_from_mime(mime_hint).to_string();
        }
    }

    m.score = score_for(&m, &lower);
    if m.title.is_empty() {
        m.title = guess_title(url);
    }
    m
}

fn kind_from_mime(mime: &str) -> MediaKind {
    if mime.starts_with("video/") {
        MediaKind::Video
    } else if mime.starts_with("audio/") {
        MediaKind::Audio
    } else if mime.starts_with("image/") {
        MediaKind::Image
    } else if mime.contains("pdf") || mime.contains("msword") || mime.contains("officedocument") {
        MediaKind::Document
    } else if mime.contains("zip") || mime.contains("compressed") || mime.contains("tar") {
        MediaKind::Archive
    } else if mime.contains("android.package") || mime.contains("octet-stream") {
        MediaKind::App
    } else {
        MediaKind::Unknown
    }
}

fn ext_from_mime(mime: &str) -> &'static str {
    match mime {
        "video/mp4" => "mp4",
        "video/webm" => "webm",
        "video/x-matroska" => "mkv",
        "audio/mpeg" => "mp3",
        "audio/mp4" => "m4a",
        "image/jpeg" => "jpg",
        "image/png" => "png",
        "image/webp" => "webp",
        "application/pdf" => "pdf",
        "application/zip" => "zip",
        "application/vnd.android.package-archive" => "apk",
        _ => "",
    }
}

/// Higher is a stronger download candidate. Size/type beat marketing noise.
fn score_for(m: &SniffedMedia, lower: &str) -> i32 {
    let mut s = match m.kind {
        MediaKind::Hls | MediaKind::Dash => 90,
        MediaKind::Video => 85,
        MediaKind::Audio => 70,
        MediaKind::App => 80,
        MediaKind::Archive => 60,
        MediaKind::Document => 55,
        MediaKind::Image => 25,
        MediaKind::Streaming => 75,
        MediaKind::Unknown => 5,
    };
    // Query strings that signal a real file behind an endpoint.
    for hint in ["filename=", "file=", ".mp4", "download", "/video/", "/media/"] {
        if lower.contains(hint) {
            s += 5;
        }
    }
    // Penalise obvious trackers even if the extension looked valid.
    for noise in NOISE_PATTERNS {
        if lower.contains(noise) {
            s -= 100;
        }
    }
    s
}

fn guess_title(url: &str) -> String {
    let path = url.split(['?', '#']).next().unwrap_or(url);
    let last = path.rsplit('/').next().unwrap_or("");
    percent_encoding::percent_decode_str(last)
        .decode_utf8_lossy()
        .to_string()
}

/// Is this URL worth surfacing in the download list?
pub fn is_candidate(m: &SniffedMedia) -> bool {
    m.score >= 40 && !m.url.is_empty()
}

/// Global de-duplicating sniffer used by the live "media" tab.
#[derive(Default)]
pub struct Sniffer {
    items: Vec<SniffedMedia>,
}

impl Sniffer {
    /// Push a URL; returns true when it was new and considered a candidate.
    pub fn push(&mut self, url: &str, mime: &str) -> bool {
        let lower = url.to_ascii_lowercase();
        if NOISE_PATTERNS.iter().any(|n| lower.contains(n)) {
            return false;
        }
        let m = classify(url, mime);
        if !is_candidate(&m) {
            return false;
        }
        if self.items.iter().any(|i| i.url == m.url) {
            return false;
        }
        self.items.push(m);
        true
    }

    pub fn items(&self) -> &[SniffedMedia] {
        &self.items
    }

    pub fn clear(&mut self) {
        self.items.clear();
    }

    pub fn json(&self) -> String {
        serde_json::to_string(&self.items).unwrap_or_else(|_| "[]".into())
    }
}

/// Free function used by the FFI one-shot API: classify and return JSON, while
/// remembering the URL so repeated calls from the same page don't duplicate.
pub fn sniff_and_dedup(url: &str, mime: &str) -> Option<SniffedMedia> {
    let mut set = match seen().lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    if !set.insert(url.to_string()) {
        return None;
    }
    let m = classify(url, mime);
    // Bound memory: the sniffer only needs a recent window.
    if set.len() > 5000 {
        set.clear();
    }
    if is_candidate(&m) {
        Some(m)
    } else {
        None
    }
}

/// Forget every previously seen URL (called when the browser clears its session).
pub fn clear_dedup() {
    if let Ok(mut set) = seen().lock() {
        set.clear();
    }
}
