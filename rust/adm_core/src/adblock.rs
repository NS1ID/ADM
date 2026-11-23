//! Ad-block / content-block rule engine.
//!
//! Implements the subset of Adblock Plus and hosts-file syntax that a mobile
//! browser actually needs: domain anchors, wildcards, `||` domain anchoring, `^`
//! separators, `@@` exception rules, `$` resource-type and third-party options,
//! plain host blocklists (`0.0.0.0 ads.example.com`) and cosmetic `##`/`#@#` rules.
//!
//! Matching is done by the Rust core; the Kotlin browser layer calls
//! [`should_block`] for each request the Chromium interceptor hands back and
//! injects the cosmetic results.

use regex::Regex;
use serde::Serialize;
use std::collections::HashSet;
use std::sync::OnceLock;

/// Options that follow the `$` in a filter.
#[derive(Debug, Clone, Default, Serialize)]
pub struct Options {
    pub third_party: Option<bool>,
    pub resource_types: Vec<String>,
    /// `@@` rules whitelist rather than block.
    pub exception: bool,
}

/// A compiled network filter.
#[derive(Debug, Clone)]
pub struct Filter {
    pub raw: String,
    pub re: Option<Regex>,
    pub domain_anchor: bool,
    pub opts: Options,
}

/// A cosmetic filter: `example.com##.ad-banner`.
#[derive(Debug, Clone, Serialize)]
pub struct Cosmetic {
    pub domains: Vec<String>,
    pub selector: String,
    pub exception: bool,
}

/// The whole compiled ruleset held by the core.
#[derive(Default)]
pub struct RuleSet {
    pub filters: Vec<Filter>,
    pub cosmetics: Vec<Cosmetic>,
}

static GLOBAL: OnceLock<std::sync::Mutex<RuleSet>> = OnceLock::new();

fn global() -> &'static std::sync::Mutex<RuleSet> {
    GLOBAL.get_or_init(|| std::sync::Mutex::new(RuleSet::default()))
}

/// Counters shown in the "blocked" badge.
#[derive(Debug, Default, Clone, Serialize)]
pub struct Stats {
    pub rules: usize,
    pub blocked: u64,
    pub allowed: u64,
}

static STATS: OnceLock<std::sync::Mutex<Stats>> = OnceLock::new();

fn stats() -> &'static std::sync::Mutex<Stats> {
    STATS.get_or_init(|| std::sync::Mutex::new(Stats::default()))
}

/// Detect a hosts-file line (`0.0.0.0 example.com`) and return the host.
pub fn hosts_entry(line: &str) -> Option<String> {
    let t = line.trim();
    if t.is_empty() || t.starts_with('#') || t.starts_with('!') {
        return None;
    }
    let mut parts = t.split_whitespace();
    let first = parts.next()?;
    let host = if first == "0.0.0.0" || first == "127.0.0.1" || first == "::1" {
        parts.next()?
    } else if looks_like_host(first) {
        first
    } else {
        return None;
    };
    Some(host.to_ascii_lowercase())
}

fn looks_like_host(s: &str) -> bool {
    s.contains('.') && !s.contains('/') && !s.contains('*') && !s.starts_with('-')
}

/// Parse one ABP-style network filter. Returns `None` for comments/cosmetics.
pub fn compile_network_filter(line: &str) -> Option<Filter> {
    let raw = line.trim();
    if raw.is_empty()
        || raw.starts_with('!')
        || raw.starts_with('[')
        || raw.starts_with('#')
        || raw.contains("##")
        || raw.contains("#@#")
    {
        return None;
    }
    // Hosts-file form.
    if let Some(host) = hosts_entry(raw) {
        let re = Regex::new(&format!(r"(?i)^https?://([^/]*\.)?{}", regex::escape(&host))).ok()?;
        return Some(Filter {
            raw: raw.to_string(),
            re: Some(re),
            domain_anchor: true,
            opts: Options::default(),
        });
    }

    let mut pattern = raw.to_string();
    let mut opts = Options::default();
    if let Some(idx) = find_options_separator(&pattern) {
        let opt_str = pattern[idx + 1..].to_string();
        pattern = pattern[..idx].to_string();
        opts = parse_options(&opt_str);
    }
    if let Some(stripped) = pattern.strip_prefix("@@") {
        opts.exception = true;
        pattern = stripped.to_string();
    }

    let domain_anchor = pattern.starts_with("||");
    let regex_src = abp_to_regex(&pattern, domain_anchor)?;
    let re = Regex::new(&regex_src).ok()?;
    Some(Filter {
        raw: raw.to_string(),
        re: Some(re),
        domain_anchor,
        opts,
    })
}

/// Find the `$` that introduces options, ignoring escaped `\$`.
fn find_options_separator(p: &str) -> Option<usize> {
    let bytes: Vec<char> = p.chars().collect();
    for (i, c) in bytes.iter().enumerate() {
        if *c == '$' && (i == 0 || bytes[i - 1] != '\\') {
            // `$` inside a regex-ish pattern with only one option list is fine.
            return Some(p.char_indices().nth(i).map(|(b, _)| b).unwrap_or(i));
        }
    }
    None
}

fn parse_options(s: &str) -> Options {
    let mut o = Options::default();
    for part in s.split(',') {
        let p = part.trim().to_ascii_lowercase();
        if p == "third-party" {
            o.third_party = Some(true);
        } else if p == "~third-party" || p == "first-party" {
            o.third_party = Some(false);
        } else if p.is_empty() {
            continue;
        } else if p == "document" || p == "subdocument" || p == "image" || p == "script"
            || p == "stylesheet" || p == "xmlhttprequest" || p == "media" || p == "font"
            || p == "object" || p == "ping" || p == "websocket" || p == "popup"
            || p == "other"
        {
            o.resource_types.push(p);
        }
    }
    o
}

/// Translate a subset of ABP pattern syntax into a Rust regex.
///
/// Supported syntax:
///   * `||` — domain anchor: match at the start of the host, subdomains allowed
///   * leading `|` — anchor to the very start of the URL
///   * trailing `|` — anchor to the end of the URL
///   * `*` — any run of characters
///   * `^` — an ABP separator (anything that is not a word char, or end of URL)
///   * `\x` — literal `x`
///
/// An unanchored pattern (e.g. `/ads/banner.`) matches anywhere in the URL, as
/// ABP specifies; anchoring everything would silently drop most of a real list.
fn abp_to_regex(pattern: &str, domain_anchor: bool) -> Option<String> {
    let mut body = pattern;
    let mut out = String::from("(?i)");

    if domain_anchor {
        if let Some(rest) = body.strip_prefix("||") {
            body = rest;
        }
        out.push_str(r"^https?://([a-z0-9_.-]+\.)*");
        // `||*foo` is the same as `||foo`; the wildcard is implied by the anchor.
        body = body.strip_prefix('*').unwrap_or(body);
    } else if let Some(rest) = body.strip_prefix('|') {
        out.push('^');
        body = rest;
    }

    let mut chars = body.chars().peekable();
    let mut literal = String::new();
    let mut flush = |literal: &mut String, out: &mut String| {
        if !literal.is_empty() {
            out.push_str(&regex::escape(literal));
            literal.clear();
        }
    };
    while let Some(c) = chars.next() {
        match c {
            '*' => {
                flush(&mut literal, &mut out);
                out.push_str(".*");
            }
            '^' => {
                flush(&mut literal, &mut out);
                out.push_str(r"(?:[^a-z0-9._%-]|$)");
            }
            '|' => {
                flush(&mut literal, &mut out);
                // Only a trailing `|` is meaningful; any internal `|` is ignored.
                if chars.peek().is_none() {
                    out.push('$');
                }
            }
            '\\' => match chars.next() {
                Some(escaped) => literal.push(escaped),
                None => literal.push('\\'),
            },
            _ => literal.push(c),
        }
    }
    flush(&mut literal, &mut out);
    Some(out)
}

/// Parse a cosmetic rule (`domain##selector`).
pub fn compile_cosmetic(line: &str) -> Option<Cosmetic> {
    let (sep, exception) = if line.contains("#@#") {
        ("#@#", true)
    } else if line.contains("##") {
        ("##", false)
    } else if line.contains("#?#") {
        ("#?#", false)
    } else {
        return None;
    };
    let idx = line.find(sep)?;
    let domains: Vec<String> = line[..idx]
        .split(',')
        .map(|s| s.trim().to_ascii_lowercase())
        .filter(|s| !s.is_empty())
        .collect();
    let selector = line[idx + sep.len()..].trim().to_string();
    if selector.is_empty() {
        return None;
    }
    Some(Cosmetic {
        domains,
        selector,
        exception,
    })
}

/// Add a whole list (one rule per line) to the global ruleset.
///
/// Compiles first, then swaps the new rules in under one short lock: compiling a
/// real EasyList takes far longer than a request interception, and holding the
/// ruleset lock for that whole time would stall every in-flight request.
pub fn load_list(text: &str) -> Stats {
    let mut filters = Vec::new();
    let mut cosmetics = Vec::new();
    for line in text.lines() {
        if let Some(c) = compile_cosmetic(line) {
            cosmetics.push(c);
        } else if let Some(f) = compile_network_filter(line) {
            filters.push(f);
        }
    }

    let rules = {
        let mut set = match global().lock() {
            Ok(g) => g,
            Err(p) => p.into_inner(),
        };
        set.filters.extend(filters);
        set.cosmetics.extend(cosmetics);
        set.filters.len()
    };

    // The global lock is released before touching stats, so the two locks are
    // never held at the same time here (no lock-order deadlock with should_block).
    let mut st = match stats().lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    st.rules = rules;
    st.clone()
}

/// Should a request to `url` (loaded from `page_url`) be blocked?
///
/// Returns `true` to block. Exceptions (`@@`) win over blocking rules.
pub fn should_block(url: &str, page_url: &str, resource_type: &str) -> bool {
    let set = match global().lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    let third_party = is_third_party(url, page_url);
    let mut blocked = false;
    for f in &set.filters {
        if !f.opts.resource_types.is_empty()
            && !f.opts.resource_types.iter().any(|t| t == resource_type)
        {
            continue;
        }
        if let Some(tp) = f.opts.third_party {
            if tp != third_party {
                continue;
            }
        }
        let Some(re) = &f.re else { continue };
        if re.is_match(url) {
            if f.opts.exception {
                if let Ok(mut st) = stats().lock() {
                    st.allowed += 1;
                }
                return false;
            }
            blocked = true;
        }
    }
    if blocked {
        if let Ok(mut st) = stats().lock() {
            st.blocked += 1;
        }
    }
    blocked
}

fn is_third_party(url: &str, page_url: &str) -> bool {
    let host = |u: &str| -> Option<String> {
        url::Url::parse(u)
            .ok()
            .and_then(|p| p.host_str().map(|h| h.to_ascii_lowercase()))
    };
    match (host(url), host(page_url)) {
        (Some(a), Some(b)) => !same_site(&a, &b),
        _ => false,
    }
}

fn same_site(a: &str, b: &str) -> bool {
    if a == b {
        return true;
    }
    let reg = |h: &str| -> String {
        let parts: Vec<&str> = h.split('.').collect();
        if parts.len() > 2 {
            parts[parts.len() - 2..].join(".")
        } else {
            h.to_string()
        }
    };
    reg(a) == reg(b)
}

/// Cosmetic CSS for `page_url`: all selectors whose domain matches.
pub fn cosmetic_css(page_url: &str) -> String {
    let set = match global().lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    let host = url::Url::parse(page_url)
        .ok()
        .and_then(|p| p.host_str().map(|h| h.to_ascii_lowercase()))
        .unwrap_or_default();
    let mut blocks: HashSet<String> = HashSet::new();
    let mut excluded: HashSet<String> = HashSet::new();
    for c in &set.cosmetics {
        let applies = c.domains.is_empty()
            || c.domains.iter().any(|d| domain_matches(&host, d));
        if !applies {
            continue;
        }
        if c.exception {
            excluded.insert(c.selector.clone());
        } else {
            blocks.insert(c.selector.clone());
        }
    }
    let selectors: Vec<String> = blocks.difference(&excluded).cloned().collect();
    if selectors.is_empty() {
        return String::new();
    }
    format!("{} {{ display: none !important; }}", selectors.join(","))
}

fn domain_matches(host: &str, rule_domain: &str) -> bool {
    let d = rule_domain.trim_start_matches("~");
    if host == d {
        return true;
    }
    host.ends_with(&format!(".{d}"))
}

/// Current stats for the UI badge.
pub fn current_stats() -> Stats {
    let rules = global().lock().map(|g| g.filters.len()).unwrap_or(0);
    let s = stats().lock().map(|g| g.clone()).unwrap_or_default();
    Stats { rules, ..s }
}

pub fn clear() {
    if let Ok(mut g) = global().lock() {
        g.filters.clear();
        g.cosmetics.clear();
    }
    if let Ok(mut s) = stats().lock() {
        s.rules = 0;
        s.blocked = 0;
        s.allowed = 0;
    }
}
