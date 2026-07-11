// Cargo compiles this shared support module once per integration-test crate; each
// crate intentionally uses a different subset of the fakes below.
#![allow(dead_code)]

use std::collections::HashMap;
use std::sync::Arc;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Mutex;

#[derive(Clone)]
pub struct FakeRedis {
    pub url: String,
    state: Arc<Mutex<RedisState>>,
    _task: Arc<tokio::task::JoinHandle<()>>,
}

#[derive(Default)]
struct RedisState {
    values: HashMap<String, Vec<u8>>,
    commands: Vec<Vec<String>>,
}

impl FakeRedis {
    pub async fn spawn(password: Option<&str>) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let state = Arc::new(Mutex::new(RedisState::default()));
        let server_state = state.clone();
        let task = tokio::spawn(async move {
            loop {
                let Ok((stream, _)) = listener.accept().await else {
                    break;
                };
                let connection_state = server_state.clone();
                tokio::spawn(async move {
                    serve_redis_connection(stream, connection_state).await;
                });
            }
        });
        let credentials = password.map_or_else(String::new, |value| format!(":{value}@"));
        Self {
            url: format!("redis://{credentials}{addr}/0"),
            state,
            _task: Arc::new(task),
        }
    }

    pub async fn commands(&self) -> Vec<Vec<String>> {
        self.state.lock().await.commands.clone()
    }

    pub async fn set_raw(&self, key: &str, value: impl Into<Vec<u8>>) {
        self.state
            .lock()
            .await
            .values
            .insert(key.to_string(), value.into());
    }

    pub async fn contains_key(&self, key: &str) -> bool {
        self.state.lock().await.values.contains_key(key)
    }
}

async fn serve_redis_connection(mut stream: TcpStream, state: Arc<Mutex<RedisState>>) {
    let mut buffered = Vec::new();
    let mut chunk = [0_u8; 4096];
    loop {
        let read = match stream.read(&mut chunk).await {
            Ok(0) | Err(_) => return,
            Ok(read) => read,
        };
        buffered.extend_from_slice(&chunk[..read]);

        while let Some((command, consumed)) = parse_resp_command(&buffered) {
            buffered.drain(..consumed);
            let response = redis_response(&command, &state).await;
            if stream.write_all(&response).await.is_err() {
                return;
            }
        }
    }
}

async fn redis_response(command: &[Vec<u8>], state: &Arc<Mutex<RedisState>>) -> Vec<u8> {
    let printable = command
        .iter()
        .map(|part| String::from_utf8_lossy(part).into_owned())
        .collect::<Vec<_>>();
    let name = printable
        .first()
        .map(|part| part.to_ascii_uppercase())
        .unwrap_or_default();
    let mut state = state.lock().await;
    state.commands.push(printable);

    match name.as_str() {
        "AUTH" | "CLIENT" | "SELECT" => simple("OK"),
        "PING" => simple("PONG"),
        "SETEX" if command.len() >= 4 => {
            state.values.insert(
                String::from_utf8_lossy(&command[1]).into_owned(),
                command[3].clone(),
            );
            simple("OK")
        }
        "SET" if command.len() >= 3 => {
            state.values.insert(
                String::from_utf8_lossy(&command[1]).into_owned(),
                command[2].clone(),
            );
            simple("OK")
        }
        "GET" if command.len() >= 2 => bulk(
            state
                .values
                .get(String::from_utf8_lossy(&command[1]).as_ref()),
        ),
        "GETDEL" if command.len() >= 2 => {
            let value = state
                .values
                .remove(String::from_utf8_lossy(&command[1]).as_ref());
            bulk(value.as_ref())
        }
        "DEL" if command.len() >= 2 => {
            let removed = state
                .values
                .remove(String::from_utf8_lossy(&command[1]).as_ref())
                .is_some();
            format!(":{}\r\n", usize::from(removed)).into_bytes()
        }
        _ => b"-ERR unsupported test command\r\n".to_vec(),
    }
}

fn parse_resp_command(input: &[u8]) -> Option<(Vec<Vec<u8>>, usize)> {
    if input.first() != Some(&b'*') {
        return None;
    }
    let (count, mut cursor) = decimal_line(input, 1)?;
    let mut command = Vec::with_capacity(count);
    for _ in 0..count {
        if input.get(cursor) != Some(&b'$') {
            return None;
        }
        let (length, data_start) = decimal_line(input, cursor + 1)?;
        let data_end = data_start.checked_add(length)?;
        if input.get(data_end..data_end + 2)? != b"\r\n" {
            return None;
        }
        command.push(input[data_start..data_end].to_vec());
        cursor = data_end + 2;
    }
    Some((command, cursor))
}

fn decimal_line(input: &[u8], start: usize) -> Option<(usize, usize)> {
    let end = input[start..]
        .windows(2)
        .position(|window| window == b"\r\n")?
        + start;
    let value = std::str::from_utf8(&input[start..end]).ok()?.parse().ok()?;
    Some((value, end + 2))
}

fn simple(value: &str) -> Vec<u8> {
    format!("+{value}\r\n").into_bytes()
}

fn bulk(value: Option<&Vec<u8>>) -> Vec<u8> {
    match value {
        Some(value) => {
            let mut encoded = format!("${}\r\n", value.len()).into_bytes();
            encoded.extend_from_slice(value);
            encoded.extend_from_slice(b"\r\n");
            encoded
        }
        None => b"$-1\r\n".to_vec(),
    }
}

#[derive(Clone)]
pub struct RecordingHttpServer {
    pub endpoint: String,
    paths: Arc<Mutex<Vec<String>>>,
    _task: Arc<tokio::task::JoinHandle<()>>,
}

impl RecordingHttpServer {
    pub async fn spawn() -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let paths = Arc::new(Mutex::new(Vec::new()));
        let recorded_paths = paths.clone();
        let task = tokio::spawn(async move {
            loop {
                let Ok((mut stream, _)) = listener.accept().await else {
                    break;
                };
                let paths = recorded_paths.clone();
                tokio::spawn(async move {
                    let mut bytes = Vec::new();
                    let mut chunk = [0_u8; 4096];
                    loop {
                        let Ok(read) = stream.read(&mut chunk).await else {
                            return;
                        };
                        if read == 0 {
                            return;
                        }
                        bytes.extend_from_slice(&chunk[..read]);
                        let Some(header_end) = find_bytes(&bytes, b"\r\n\r\n") else {
                            continue;
                        };
                        let headers = String::from_utf8_lossy(&bytes[..header_end]);
                        let content_length = headers
                            .lines()
                            .find_map(|line| {
                                let (name, value) = line.split_once(':')?;
                                name.eq_ignore_ascii_case("content-length")
                                    .then(|| value.trim().parse::<usize>().ok())
                                    .flatten()
                            })
                            .unwrap_or_default();
                        if bytes.len() < header_end + 4 + content_length {
                            continue;
                        }
                        let path = headers
                            .lines()
                            .next()
                            .and_then(|line| line.split_whitespace().nth(1))
                            .unwrap_or_default()
                            .to_string();
                        paths.lock().await.push(path);
                        let _ = stream
                            .write_all(
                                b"HTTP/1.1 200 OK\r\ncontent-length: 0\r\nconnection: close\r\n\r\n",
                            )
                            .await;
                        return;
                    }
                });
            }
        });
        Self {
            endpoint: format!("http://{addr}"),
            paths,
            _task: Arc::new(task),
        }
    }

    pub async fn paths(&self) -> Vec<String> {
        self.paths.lock().await.clone()
    }
}

fn find_bytes(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}
