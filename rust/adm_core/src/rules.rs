//! Rule-based request rewriting and userscript management.
//!
//! Two related systems live here:
//! * **URL rules** — ordered `match → action` rewrites (redirect, block, add
//!   headers, swap User-Agent) applied by the WebView's request interceptor. This
//!   is the hook third-party "extension" bundles use to alter page behaviour.
//! * **Userscripts** — Greasemonkey/Tampermonkey-style metadata parsing (`@match`,
//!   `@include`, `@run-at`, `@require`) so the browser can inject them at the right
//!   document stage.

use regex::Regex;
use serde::{Deserialize, Serialize};

/// One URL rewrite rule.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UrlRule {
    pub id: String,
    pub name: String,
    /// Regex that the request URL must match.
    pub pattern: String,
    /// `redirect` | `block` | `header` | `useragent`
    pub action: String,
    /// Replacement URL template for `redirect` (`$1`, `$2` group refs supported).
    pub replacement: String,
    pub enabled: bool,
}

/// Compiled form of [`UrlRule`].
pub struct CompiledRule {
    pub rule: UrlRule,
    pub re: Regex,
}

/// Replace `$1..$9` back-references with regex capture groups.
pub fn expand_replacement(template: &str, caps: &regex::Captures) -> String {
    let mut out = String::with_capacity(template.len());
    let mut chars = template.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '$' {
            if let Some(d) = chars.peek().and_then(|c| c.to_digit(10)) {
                chars.next();
                if let Some(g) = caps.get(d as usize) {
                    out.push_str(g.as_str());
                }
                continue;
            }
        }
        out.push(c);
    }
    out
}

/// Compile rules, dropping the ones with invalid regexes.
pub fn compile(rules: &[UrlRule]) -> Vec<CompiledRule> {
    rules
        .iter()
        .filter(|r| r.enabled)
        .filter_map(|r| {
            Regex::new(&r.pattern).ok().map(|re| CompiledRule {
                rule: r.clone(),
                re,
            })
        })
        .collect()
}

/// The outcome of applying rules to a URL.
#[derive(Debug, Clone, Serialize)]
pub struct RuleOutcome {
    pub blocked: bool,
    pub redirect: Option<String>,
    pub headers: Vec<(String, String)>,
    pub user_agent: Option<String>,
}

/// Apply every rule in order; last redirect wins, headers accumulate.
pub fn apply(rules: &[CompiledRule], url: &str) -> RuleOutcome {
    let mut outcome = RuleOutcome {
        blocked: false,
        redirect: None,
        headers: Vec::new(),
        user_agent: None,
    };
    let mut effective = url.to_string();
    for cr in rules {
        let Some(caps) = cr.re.captures(&effective) else {
            continue;
        };
        match cr.rule.action.as_str() {
            "block" => {
                outcome.blocked = true;
                return outcome;
            }
            "redirect" => {
                let target = expand_replacement(&cr.rule.replacement, &caps);
                if !target.is_empty() {
                    outcome.redirect = Some(target.clone());
                    effective = target;
                }
            }
            "useragent" => {
                outcome.user_agent = Some(cr.rule.replacement.clone());
            }
            "header" => {
                if let Some((k, v)) = cr.rule.replacement.split_once(':') {
                    outcome.headers.push((k.trim().to_string(), v.trim().to_string()));
                }
            }
            _ => {}
        }
    }
    outcome
}

// ---------------------------------------------------------------------------
// Userscripts
// ---------------------------------------------------------------------------

/// Greasemonkey metadata block.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct UserscriptMeta {
    pub name: String,
    pub namespace: String,
    pub version: String,
    pub description: String,
    pub author: String,
    pub matches: Vec<String>,
    pub includes: Vec<String>,
    pub excludes: Vec<String>,
    pub run_at: String,
    pub grants: Vec<String>,
    pub requires: Vec<String>,
    pub icon: String,
}

/// A userscript = metadata + JavaScript source.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Userscript {
    pub id: String,
    pub meta: UserscriptMeta,
    pub source: String,
    pub enabled: bool,
}

/// Parse the `// ==UserScript== ... // ==/UserScript==` header.
pub fn parse_userscript(source: &str) -> UserscriptMeta {
    let mut meta = UserscriptMeta {
        run_at: "document-idle".into(),
        ..Default::default()
    };
    let mut inside = false;
    for line in source.lines() {
        let l = line.trim();
        if l.starts_with("// ==UserScript==") {
            inside = true;
            continue;
        }
        if l.starts_with("// ==/UserScript==") {
            break;
        }
        if !inside {
            continue;
        }
        let body = l.trim_start_matches("//").trim();
        let Some((key, value)) = body.split_once(char::is_whitespace) else {
            continue;
        };
        let key = key.trim_start_matches('@').to_ascii_lowercase();
        let value = value.trim().to_string();
        match key.as_str() {
            "name" => meta.name = value,
            "namespace" => meta.namespace = value,
            "version" => meta.version = value,
            "description" => meta.description = value,
            "author" => meta.author = value,
            "match" => meta.matches.push(value),
            "include" => meta.includes.push(value),
            "exclude" => meta.excludes.push(value),
            "run-at" => meta.run_at = value,
            "grant" => meta.grants.push(value),
            "require" => meta.requires.push(value),
            "icon" => meta.icon = value,
            _ => {}
        }
    }
    if meta.name.is_empty() {
        meta.name = "Unnamed script".into();
    }
    meta
}

/// Does this userscript run on `url`? Supports the common `*` glob in `@match`
/// (the full Chrome match-pattern grammar is intentionally not implemented).
pub fn userscript_matches(meta: &UserscriptMeta, url: &str) -> bool {
    let patterns: Vec<&String> = meta.matches.iter().chain(meta.includes.iter()).collect();
    if patterns.is_empty() {
        return false;
    }
    let hit = patterns.iter().any(|p| glob_match(p, url));
    let excluded = meta.excludes.iter().any(|p| glob_match(p, url));
    hit && !excluded
}

/// Minimal `*` glob matcher (no `?`, no character classes).
pub fn glob_match(pattern: &str, text: &str) -> bool {
    let parts: Vec<&str> = pattern.split('*').collect();
    if parts.len() == 1 {
        return pattern == text;
    }
    let mut pos = 0usize;
    for (i, part) in parts.iter().enumerate() {
        if part.is_empty() {
            continue;
        }
        if i == 0 {
            if !text.starts_with(part) {
                return false;
            }
            pos = part.len();
        } else if i == parts.len() - 1 {
            return text[pos..].ends_with(part);
        } else if let Some(found) = text[pos..].find(part) {
            pos += found + part.len();
        } else {
            return false;
        }
    }
    true
}

/// Wrap a userscript body so it can be injected with the metadata preserved.
pub fn wrap_for_injection(script: &Userscript) -> String {
    let grants = script
        .meta
        .grants
        .iter()
        .map(|g| format!("/* @grant {g} */"))
        .collect::<Vec<_>>()
        .join("\n");
    format!(
        "(function(){{\n/* ADM+ userscript: {} v{} */\n{}\n{}\n}})();",
        script.meta.name, script.meta.version, grants, script.source
    )
}
