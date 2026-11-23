//! Local proxy engine.
//!
//! `AdmVpnService` (Kotlin) creates a `VpnService` TUN interface, configures routes
//! so *all* device traffic is funnelled into it, and then bridges that TUN to this
//! proxy. The proxy speaks HTTP `CONNECT` and SOCKS5, so the routing decisions,
//! per-app split tunnelling and upstream chaining (VPN → corporate proxy) all live
//! in one place. The core performs the socket work; Kotlin only owns the TUN fd.
//!
//! Note: a full user-space TCP/IP stack is a large amount of code. This module
//! implements the *proxy* half (listener + SOCKS5/CONNECT handshake + upstream
//! chaining) plus UDP-relay helpers, which is the part that can be shared and
//! tested on-device without a kernel; the TUN packet pump lives in Kotlin next to
//! the `VpnService` file descriptor.

use serde::{Deserialize, Serialize};
use std::io::{Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream, ToSocketAddrs};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

/// Proxy modes exposed in Settings → Proxy.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ProxyMode {
    /// No proxying.
    Off,
    /// HTTP(S) upstream proxy (`CONNECT` for TLS).
    Http,
    /// SOCKS5 upstream proxy.
    Socks5,
    /// On-device VPN only (route through the TUN, no upstream proxy).
    Vpn,
}

/// Proxy / VPN configuration mirrored from Kotlin.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProxyConfig {
    pub mode: ProxyMode,
    pub host: String,
    pub port: u16,
    pub username: String,
    pub password: String,
    /// Resolve DNS at the proxy (remote DNS) — required to avoid leaks over SOCKS5.
    pub remote_dns: bool,
    /// Comma-separated package names excluded from the VPN tunnel (split tunnel).
    pub bypass_packages: Vec<String>,
}

impl Default for ProxyConfig {
    fn default() -> Self {
        ProxyConfig {
            mode: ProxyMode::Off,
            host: String::new(),
            port: 0,
            username: String::new(),
            password: String::new(),
            remote_dns: true,
            bypass_packages: Vec::new(),
        }
    }
}

/// Live byte counters surfaced in the VPN screen.
#[derive(Debug, Default, Serialize)]
pub struct TrafficStats {
    pub bytes_up: u64,
    pub bytes_down: u64,
    pub active_connections: u64,
    pub blocked: u64,
}

/// A running local proxy listener.
pub struct Server {
    listen_addr: String,
    upstream: ProxyConfig,
    running: Arc<AtomicBool>,
    up: Arc<AtomicU64>,
    down: Arc<AtomicU64>,
    active: Arc<AtomicU64>,
    blocked: Arc<AtomicU64>,
    handle: Option<std::thread::JoinHandle<()>>,
}

impl Server {
    /// Start listening on `127.0.0.1:<port>` and chain to `upstream`.
    pub fn start(port: u16, upstream: ProxyConfig) -> Result<Server, String> {
        let listener = TcpListener::bind(("127.0.0.1", port)).map_err(|e| e.to_string())?;
        let running = Arc::new(AtomicBool::new(true));
        let up = Arc::new(AtomicU64::new(0));
        let down = Arc::new(AtomicU64::new(0));
        let active = Arc::new(AtomicU64::new(0));
        let blocked = Arc::new(AtomicU64::new(0));

        let r = Arc::clone(&running);
        let u = Arc::clone(&up);
        let d = Arc::clone(&down);
        let a = Arc::clone(&active);
        let b = Arc::clone(&blocked);
        let cfg = upstream.clone();
        let addr = listener.local_addr().map(|a| a.to_string()).unwrap_or_default();

        let handle = std::thread::spawn(move || {
            for conn in listener.incoming() {
                if !r.load(Ordering::SeqCst) {
                    break;
                }
                let Ok(client) = conn else { continue };
                let cfg = cfg.clone();
                let u = Arc::clone(&u);
                let d = Arc::clone(&d);
                let a = Arc::clone(&a);
                let b = Arc::clone(&b);
                std::thread::spawn(move || {
                    a.fetch_add(1, Ordering::SeqCst);
                    let _ = handle_client(client, &cfg, &u, &d, &b);
                    a.fetch_sub(1, Ordering::SeqCst);
                });
            }
        });

        Ok(Server {
            listen_addr: addr,
            upstream,
            running,
            up,
            down,
            active,
            blocked,
            handle: Some(handle),
        })
    }

    pub fn listen_addr(&self) -> &str {
        &self.listen_addr
    }

    pub fn stats(&self) -> TrafficStats {
        TrafficStats {
            bytes_up: self.up.load(Ordering::SeqCst),
            bytes_down: self.down.load(Ordering::SeqCst),
            active_connections: self.active.load(Ordering::SeqCst),
            blocked: self.blocked.load(Ordering::SeqCst),
        }
    }

    /// Ask the accept loop to stop and join it.
    pub fn stop(mut self) {
        self.running.store(false, Ordering::SeqCst);
        // Unblock accept() with a throwaway connect.
        let _ = TcpStream::connect(&self.listen_addr);
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }

    pub fn upstream(&self) -> &ProxyConfig {
        &self.upstream
    }
}

/// Read the first line from a proxied client ("CONNECT host:port HTTP/1.1" or a
/// SOCKS5 greeting) and dispatch to the right handshake.
fn handle_client(
    mut client: TcpStream,
    cfg: &ProxyConfig,
    up: &Arc<AtomicU64>,
    down: &Arc<AtomicU64>,
    blocked: &Arc<AtomicU64>,
) -> Result<(), String> {
    client
        .set_read_timeout(Some(Duration::from_secs(30)))
        .ok();
    let mut first = [0u8; 1];
    let n = client.read(&mut first).map_err(|e| e.to_string())?;
    if n == 0 {
        return Ok(());
    }
    if first[0] == 0x05 {
        return handle_socks5(client, cfg, up, down, blocked, first[0]);
    }
    // HTTP proxy: push the sniffed byte back by prepending it.
    let mut buf = vec![first[0]];
    read_until_line(&mut client, &mut buf)?;
    let line = String::from_utf8_lossy(&buf).to_string();
    let mut parts = line.split_whitespace();
    let method = parts.next().unwrap_or("");
    let target = parts.next().unwrap_or("");
    if !method.eq_ignore_ascii_case("CONNECT") {
        // Plain HTTP absolute-form request: rewrite the request line to origin-form
        // and forward the *whole* head (method, path, headers) to the origin.
        return forward_http(client, target, &buf, cfg, up, down);
    }
    let (host, port) = split_host_port(target, 443);
    connect_upstream_and_tunnel(client, &host, port, cfg, up, down)
}

/// Copy the CONNECT request line terminator out of the way.
fn read_until_line(client: &mut TcpStream, buf: &mut Vec<u8>) -> Result<(), String> {
    let mut b = [0u8; 1];
    loop {
        if buf.ends_with(b"\r\n\r\n") {
            break;
        }
        let n = client.read(&mut b).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        buf.push(b[0]);
        if buf.len() > 8192 {
            break;
        }
    }
    Ok(())
}

/// SOCKS5: greeting, optional user/pass auth, then CONNECT to the requested host.
fn handle_socks5(
    mut client: TcpStream,
    cfg: &ProxyConfig,
    up: &Arc<AtomicU64>,
    down: &Arc<AtomicU64>,
    blocked: &Arc<AtomicU64>,
    _first: u8,
) -> Result<(), String> {
    let mut hdr = [0u8; 1];
    client.read_exact(&mut hdr).map_err(|e| e.to_string())?; // nmethods
    let mut methods = vec![0u8; hdr[0] as usize];
    client.read_exact(&mut methods).map_err(|e| e.to_string())?;
    let need_auth = !cfg.username.is_empty();
    let choice = if need_auth { 0x02u8 } else { 0x00u8 };
    client.write_all(&[0x05, choice]).map_err(|e| e.to_string())?;
    if choice == 0x02 {
        // RFC 1929 user/pass sub-negotiation.
        let mut v = [0u8; 1];
        client.read_exact(&mut v).map_err(|e| e.to_string())?;
        let mut user = vec![0u8; v[0] as usize];
        client.read_exact(&mut user).map_err(|e| e.to_string())?;
        let mut pl = [0u8; 1];
        client.read_exact(&mut pl).map_err(|e| e.to_string())?;
        let mut pass = vec![0u8; pl[0] as usize];
        client.read_exact(&mut pass).map_err(|e| e.to_string())?;
        let ok = String::from_utf8_lossy(&user) == cfg.username
            && String::from_utf8_lossy(&pass) == cfg.password;
        client
            .write_all(&[0x01, if ok { 0x00 } else { 0x01 }])
            .map_err(|e| e.to_string())?;
        if !ok {
            blocked.fetch_add(1, Ordering::SeqCst);
            return Err("socks auth failed".into());
        }
    }
    // Request: VER CMD RSV ATYP DST.ADDR DST.PORT
    let mut req = [0u8; 4];
    client.read_exact(&mut req).map_err(|e| e.to_string())?;
    let host = match req[3] {
        0x01 => {
            let mut a = [0u8; 4];
            client.read_exact(&mut a).map_err(|e| e.to_string())?;
            std::net::Ipv4Addr::from(a).to_string()
        }
        0x03 => {
            let mut l = [0u8; 1];
            client.read_exact(&mut l).map_err(|e| e.to_string())?;
            let mut d = vec![0u8; l[0] as usize];
            client.read_exact(&mut d).map_err(|e| e.to_string())?;
            String::from_utf8_lossy(&d).to_string()
        }
        0x04 => {
            let mut a = [0u8; 16];
            client.read_exact(&mut a).map_err(|e| e.to_string())?;
            std::net::Ipv6Addr::from(a).to_string()
        }
        _ => return Err("bad atyp".into()),
    };
    let mut port = [0u8; 2];
    client.read_exact(&mut port).map_err(|e| e.to_string())?;
    let port = u16::from_be_bytes(port);
    connect_upstream_and_tunnel(client, &host, port, cfg, up, down)
}

/// Open an upstream socket (direct, or chained through cfg) and splice both ways.
fn connect_upstream_and_tunnel(
    mut client: TcpStream,
    host: &str,
    port: u16,
    cfg: &ProxyConfig,
    up: &Arc<AtomicU64>,
    down: &Arc<AtomicU64>,
) -> Result<(), String> {
    let target = format!("{host}:{port}");
    let upstream = match cfg.mode {
        ProxyMode::Http | ProxyMode::Socks5 => chain_to_upstream(&target, cfg)?,
        _ => {
            let mut addrs = target.to_socket_addrs().map_err(|e| e.to_string())?;
            let addr = addrs.next().ok_or("no address")?;
            TcpStream::connect_timeout(&addr, Duration::from_secs(20))
                .map_err(|e| e.to_string())?
        }
    };
    // Signal success to the client before splicing.
    let _ = client.write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]);
    splice(client, upstream, up, down)
}

/// Chain to an upstream HTTP CONNECT or SOCKS5 proxy.
fn chain_to_upstream(target: &str, cfg: &ProxyConfig) -> Result<TcpStream, String> {
    let proxy_addr = format!("{}:{}", cfg.host, cfg.port);
    let mut s = TcpStream::connect_timeout(
        &proxy_addr.to_socket_addrs().map_err(|e| e.to_string())?.next().unwrap(),
        Duration::from_secs(20),
    )
    .map_err(|e| e.to_string())?;
    match cfg.mode {
        ProxyMode::Http => {
            let req = format!("CONNECT {target} HTTP/1.1\r\nHost: {target}\r\n\r\n");
            s.write_all(req.as_bytes()).map_err(|e| e.to_string())?;
            let mut resp = Vec::new();
            let mut b = [0u8; 1];
            while !resp.ends_with(b"\r\n\r\n") {
                let n = s.read(&mut b).map_err(|e| e.to_string())?;
                if n == 0 {
                    break;
                }
                resp.push(b[0]);
            }
            if !String::from_utf8_lossy(&resp).contains(" 200") {
                return Err("upstream proxy refused CONNECT".into());
            }
        }
        ProxyMode::Socks5 => {
            let auth = !cfg.username.is_empty();
            let greeting: &[u8] = if auth {
                &[0x05, 0x01, 0x02]
            } else {
                &[0x05, 0x01, 0x00]
            };
            s.write_all(greeting).map_err(|e| e.to_string())?;
            let mut rep = [0u8; 2];
            s.read_exact(&mut rep).map_err(|e| e.to_string())?;
            if rep[1] == 0x02 {
                let mut msg = vec![0x01, cfg.username.len() as u8];
                msg.extend_from_slice(cfg.username.as_bytes());
                msg.push(cfg.password.len() as u8);
                msg.extend_from_slice(cfg.password.as_bytes());
                s.write_all(&msg).map_err(|e| e.to_string())?;
                let mut ok = [0u8; 2];
                s.read_exact(&mut ok).map_err(|e| e.to_string())?;
                if ok[1] != 0 {
                    return Err("upstream socks auth failed".into());
                }
            }
            let (host, port) = split_host_port(target, 80);
            let mut req = vec![0x05, 0x01, 0x00, 0x03, host.len() as u8];
            req.extend_from_slice(host.as_bytes());
            req.extend_from_slice(&port.to_be_bytes());
            s.write_all(&req).map_err(|e| e.to_string())?;
            let mut head = [0u8; 4];
            s.read_exact(&mut head).map_err(|e| e.to_string())?;
            if head[1] != 0 {
                return Err("upstream socks connect failed".into());
            }
            // Drain the bound address.
            match head[3] {
                0x01 => {
                    let mut skip = [0u8; 6];
                    s.read_exact(&mut skip).map_err(|e| e.to_string())?;
                }
                0x03 => {
                    let mut l = [0u8; 1];
                    s.read_exact(&mut l).map_err(|e| e.to_string())?;
                    let mut skip = vec![0u8; l[0] as usize + 2];
                    s.read_exact(&mut skip).map_err(|e| e.to_string())?;
                }
                0x04 => {
                    let mut skip = [0u8; 18];
                    s.read_exact(&mut skip).map_err(|e| e.to_string())?;
                }
                _ => {}
            }
        }
        _ => {}
    }
    Ok(s)
}

/// Forward a plain absolute-form HTTP request to its origin.
///
/// The proxy has already read the entire request head into `head` (request line plus
/// headers, terminated by a blank line). Absolute-form request targets
/// (`GET http://host/path?q=1 HTTP/1.1`) are not valid towards an origin server, so
/// the request line is rewritten to origin-form (`GET /path?q=1 HTTP/1.1`) while the
/// method, path, query and every header are preserved verbatim.
fn forward_http(
    mut client: TcpStream,
    target: &str,
    head: &[u8],
    cfg: &ProxyConfig,
    up: &Arc<AtomicU64>,
    down: &Arc<AtomicU64>,
) -> Result<(), String> {
    let (host, port) = split_host_port(target, 80);
    let mut upstream = match cfg.mode {
        ProxyMode::Http | ProxyMode::Socks5 => chain_to_upstream(target, cfg)?,
        _ => {
            let addr = format!("{host}:{port}")
                .to_socket_addrs()
                .map_err(|e| e.to_string())?
                .next()
                .ok_or("no address")?;
            TcpStream::connect_timeout(&addr, Duration::from_secs(20)).map_err(|e| e.to_string())?
        }
    };

    let text = String::from_utf8_lossy(head);
    let mut lines = text.split("\r\n");
    let request_line = lines.next().unwrap_or("");
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or("GET");
    let _absolute = parts.next().unwrap_or("/");
    let version = parts.next().unwrap_or("HTTP/1.1");
    // Keep only the path and query of the absolute target.
    let origin_form = match target.find("://") {
        Some(i) => {
            let after_scheme = &target[i + 3..];
            match after_scheme.find('/') {
                Some(j) => &after_scheme[j..],
                None => "/",
            }
        }
        None => target,
    };

    let mut out = String::with_capacity(text.len() + 32);
    out.push_str(method);
    out.push(' ');
    out.push_str(origin_form);
    out.push(' ');
    out.push_str(version);
    out.push_str("\r\n");
    // Re-emit the remaining header lines, ensuring a Host header exists.
    let mut saw_host = false;
    for line in lines {
        if line.is_empty() {
            continue;
        }
        if line.len() >= 5 && line[..5].eq_ignore_ascii_case("host:") {
            saw_host = true;
        }
        // Drop hop-by-hop headers that would break the origin connection.
        let lower = line.to_ascii_lowercase();
        if lower.starts_with("proxy-connection:") || lower.starts_with("connection:") {
            continue;
        }
        out.push_str(line);
        out.push_str("\r\n");
    }
    if !saw_host {
        out.push_str(&format!("Host: {host}\r\n"));
    }
    out.push_str("\r\n");

    upstream.write_all(out.as_bytes()).map_err(|e| e.to_string())?;
    splice(client, upstream, up, down)
}

/// Bidirectional copy between two sockets with byte accounting.
fn splice(
    client: TcpStream,
    upstream: TcpStream,
    up: &Arc<AtomicU64>,
    down: &Arc<AtomicU64>,
) -> Result<(), String> {
    let c2 = client.try_clone().map_err(|e| e.to_string())?;
    let u2 = upstream.try_clone().map_err(|e| e.to_string())?;
    let up_tx = Arc::clone(up);
    let down_tx = Arc::clone(down);
    let t = std::thread::spawn(move || {
        let mut src = c2;
        let mut dst = upstream;
        copy_counting(&mut src, &mut dst, &up_tx);
        let _ = dst.shutdown(Shutdown::Write);
    });
    let mut src = client;
    let mut dst = u2;
    copy_counting(&mut src, &mut dst, &down_tx);
    let _ = dst.shutdown(Shutdown::Write);
    let _ = t.join();
    let _ = (up, down);
    Ok(())
}

fn copy_counting(src: &mut TcpStream, dst: &mut TcpStream, counter: &AtomicU64) {
    let mut buf = [0u8; 32 * 1024];
    loop {
        match src.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                if dst.write_all(&buf[..n]).is_err() {
                    break;
                }
                counter.fetch_add(n as u64, Ordering::Relaxed);
            }
            Err(_) => break,
        }
    }
}

/// Split `host:port` (also accepts a bare host or an IPv6 literal).
pub fn split_host_port(target: &str, default_port: u16) -> (String, u16) {
    let t = target.trim();
    if t.starts_with('[') {
        if let Some(end) = t.find(']') {
            let host = t[1..end].to_string();
            let port = t[end + 1..]
                .strip_prefix(':')
                .and_then(|p| p.parse().ok())
                .unwrap_or(default_port);
            return (host, port);
        }
    }
    match t.rsplit_once(':') {
        Some((h, p)) => match p.parse::<u16>() {
            Ok(port) => (h.to_string(), port),
            Err(_) => (t.to_string(), default_port),
        },
        None => (t.to_string(), default_port),
    }
}

/// Very small DNS-over-UDP relay used when `remote_dns` is enabled: the TUN side
/// sends raw DNS payloads and we forward them to the configured resolver, keeping
/// lookups off the local network.
pub fn relay_dns(payload: &[u8], resolver: &str) -> Result<Vec<u8>, String> {
    let sock = std::net::UdpSocket::bind("0.0.0.0:0").map_err(|e| e.to_string())?;
    sock.set_read_timeout(Some(Duration::from_secs(5))).ok();
    sock.send_to(payload, resolver).map_err(|e| e.to_string())?;
    let mut buf = vec![0u8; 4096];
    let (n, _) = sock.recv_from(&mut buf).map_err(|e| e.to_string())?;
    buf.truncate(n);
    Ok(buf)
}

/// Validate a proxy/VPN configuration before use.
pub fn validate(cfg: &ProxyConfig) -> Result<(), String> {
    match cfg.mode {
        ProxyMode::Off | ProxyMode::Vpn => Ok(()),
        ProxyMode::Http | ProxyMode::Socks5 => {
            if cfg.host.trim().is_empty() {
                return Err("proxy host is empty".into());
            }
            if cfg.port == 0 {
                return Err("proxy port is 0".into());
            }
            Ok(())
        }
    }
}
