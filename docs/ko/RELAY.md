# BlitzLink 공식 중계 — blitzlink-relay

> **BlitzLink Lite:** 이 문서는 네이티브 BlitzLink와 같은 규격이다. Lite에는 사이드카 실행 파일이 없고, 같은 인자를 받아 같은 JSON 줄을 내는 `tunnel/`의 `LiteTunnel`(Java, webrtc-java)이 게임 JVM 안에서 그 역할을 한다(stdin/stdout·종료 코드 대신 메서드 호출과 콜백). 중계·P2P 채널·신원·TURN은 바이트 단위로 같아서 네이티브와 Lite가 섞여도 된다(`tunnel/src/test/java/com/hyproj/blitzlink/lite/InteropTest.java`). 아래의 Rust 경로와 사이드카 빌드 이야기는 네이티브 저장소(https://github.com/HY-Project/BlitzLinkMC)의 것이다.

메시지 모양의 정본은 `native/blitzlink-core/src/relay.rs`
한 벌이고, 중계(`blitzlink-relay`)와 사이드카(`blitzlink-tunnel`)가 같이 쓴다. 사이드카 제어는 [PROTOCOL.md](PROTOCOL.md). English: [../en/RELAY.md](../en/RELAY.md)

## 무엇을 하나

집에서 연 MC 서버는 대개 NAT 뒤에 있다. 중계는 그 사이를 **소개만** 한다 — SDP Offer/Answer 한 쌍과 공개 목록.

```
[MC 클라] ─TCP→ 127.0.0.1:임의 ─ [tunnel join] ══ WebRTC DataChannel(직접, UDP) ══ [tunnel host] ─TCP→ 127.0.0.1:MC포트 ─ [MC 서버]
                                       │  └─ (UDP 막힘) TURN-over-TCP → 호스트 MC 포트(모드의 첫 바이트 분기) ┘
                                       └──wss── [blitzlink-relay @hyproj.com/relay/mcje] ──wss── ┘   (SDP만 · 공개 목록 · STUN은 :3478 공유)
```

- **마크 데이터는 중계를 거치지 않는다.** 중계는 SDP 한 쌍을 건네고 클라 소켓을 닫는다. hyproj의 부하는 시그널링 몇 KB뿐이다.
- 양쪽 다 **Full ICE**(str0m 0.25, ICE-lite 아님): UDP 소켓 하나에 host 후보(LAN·루프백)와 srflx 후보(STUN으로 알아낸 공인 주소)를 모아 SDP에
  싣는다. 양쪽이 서로에게 연결 확인을 보내 NAT에 구멍이 뚫린다(홀펀칭). 호스트의 UDP 포트는 MC 포트와 같은 번호(안 되면 임의), `--upnp`면 공유기에도 연다.
- UDP가 막힌 클라는 호스트의 **내장 TURN**(MC TCP 포트, 모드가 첫 바이트로 갈라 사이드카에 넘긴다 — Bukkit은 `--turn-listen`으로 공개 포트에 바로 묶는다)으로 들어온다. 호스트가 답에 자격을 실어 보내고
  중계는 다듬어(`TurnInfo::sanitized`) 건넬 뿐이다. **hyproj에는 TURN이 없다.**
- STUN은 hyproj.com에서 이미 도는 `hyproj.com:3478`을 같이 쓴다(공식 `data/net.json` — 아래). 이 중계도 `--stun`으로 STUN을 열 수 있지만 hyproj에서는 끈다.

| 길 | 하는 일 |
|---|---|
| `GET /ws` | WebSocket. 붙으면 중계가 `hello{relay, nonce}`를 먼저 보낸다. 호스트는 `host`, 클라는 `join`. |
| `GET /servers` | 공개 목록(`ServerList` JSON) — 공개로 연 서버만, 인원 많은 순, 최대 500. 이름·MOTD는 STRICT 검열. `Cache-Control: max-age=5`. |
| `GET /health` | `ok` |
| UDP 3478(선택) | STUN Binding(RFC 5389) — `blitzlink_core::stun`. hyproj에서는 끈다(`--no-stun`). |

## 흐름

1. **호스트 올리기** — `tunnel host`가 `/ws`에 붙어 `hello{nonce}`를 받고 `host{relay, key, sig, listing, public, code?}`를 보낸다. `sig`는 nonce에 한
   Ed25519 서명인데 머리가 클라용 신원 증명과 다르다(`blitzlink/relay-host/v1`). 중계가 확인하고 **참가 코드**를 준다(`hosted{code, fingerprint}`).
   - 코드: `MCJE-` + 4글자(0·1·O·I·L·U를 뺀 30자 → 81만 가지). 대소문자·공백·하이픈 무시, 머리 생략 가능(`7k3q` → `MCJE-7K3Q`).
   - 호스트는 받은 코드를 `--code-file`에 적어 두었다가 다음에 같은 것을 청하고, 중계도 그 키 몫으로 **하루** 남겨 둔다.
   - 같은 키가 다시 붙으면 옛 접속의 자리를 넘겨받는다.
   - 호스트는 5초마다 바뀐 것만 `update{listing, public?}`, 20초마다 ping. 75초 동안 말이 없으면 중계가 내린다. 끊기면 2초~60초 백오프로 다시 붙는다.
   - `listing`은 MC용 요약(`name·motd·mcVersion·protocol·loader·loaderVersion·modCount·modsHash·players·maxPlayers·onlineMode`). **모드 목록 본체는 싣지 않는다** — P2P `meta`로 간다.
2. **클라 들어오기** — `tunnel join --code MCJE-7K3Q`가 후보를 모아 Offer(채널 `meta`)를 `join{relay, code, sdp}`로 보낸다. 중계는 호스트에
   `offer{session, sdp}`, 호스트의 `answer{session, sdp, turn?}`를 클라에 `answer{sdp, fingerprint, listing, turn?}`으로 건넨다. 8초 안에 답이 없으면
   `error{hostTimeout}`.
3. **경쟁** — 첫(UDP) 연결이 2.5초 안에 열리지 않거나 실패하면, 답의 `turn`만 쓰는 두 번째 연결(TURN Allocate →
   relayed 후보 하나 → 새 Offer로 다시 `join`)을 같이 시작한다. 먼저 열리는 쪽을 쓴다. 14초 안에 못 열면 `rtcFailed`.
4. **그 뒤는 호스트와 직접** — `meta`에서 `probe{nonce}` → `info{key, sig, info}`. 클라는 서명(`blitzlink/server-identity/v1`)을 확인하고 지문이 중계가
   건넨 지문과 같은지 본다(다르면 `badIdentity`). 그다음 로컬 TCP 접속마다 `mc-N` 채널.

## 막는 것

- 메시지 16 KB · SDP 12 KB, 첫 메시지 10초.
- `Origin` — 허용 목록(`--allow-origin`, 기본 `https://hyproj.com`·`https://www.hyproj.com`·`http://localhost:*`·`http://127.0.0.1:*`) 밖의 페이지가
  `/ws`를 열면 403. 사이드카는 `Origin`을 보내지 않아 늘 통과한다(남의 웹페이지가 우리 중계를 쓰지 못하게 할 뿐이다).
- IP마다 WebSocket 접속 토큰 통(30개, 2초에 하나 참) — 넘으면 HTTP 429.
- 전체 서버 5000 · IP마다 8(`--max-hosts`, `--max-hosts-per-ip`).
- 호스트는 서명이 맞아야 올라온다. 남의 세션에 Answer를 보내도 무시된다.
- 공개 목록의 글은 중계가 다듬고(길이·제어 문자·로더 이름·해시 꼴) STRICT로 가린다(단어장 `native/blitzlink-relay/data/chat-filter/`).
- 로그에는 코드·지문·IP만 남는다(영어). SDP는 남기지 않는다.

## 로컬에서 돌리기(개발)

```
cd native
cargo run -p blitzlink-relay -- --listen 127.0.0.1:1947 --stun 127.0.0.1:3478
cargo run -p blitzlink-tunnel -- host --target 127.0.0.1:25565 --port 25565 --relay http://127.0.0.1:1947 --stun stun:127.0.0.1:3478 --public
cargo run -p blitzlink-tunnel -- join --code MCJE-XXXX --relay http://127.0.0.1:1947 --stun stun:127.0.0.1:3478
```

`cargo test --workspace`의 `blitzlink-tunnel/tests/e2e.rs`가 이것을 자동으로 한다: 중계 + host + join + 메아리 「MC 서버」(첫 바이트 분기 흉내),
1 MB 왕복과 닫힘 전파를 직접 UDP와 `--turn-only` 두 길로.

모드 개발 빌드는 `-Dblitzlink.relay=http://127.0.0.1:1947`로 중계를 바꾼다(배포물에는 없다).

## 배포 안 — hyproj.com (**사용자 승인 뒤에만**)

hyproj.com 서버는 **바꾸면 곧 배포**이므로 아래는 안이다. 반영(이미지 올리기·compose·nginx·방화벽)은 사용자가 승인한 뒤에.

1. **이미지** — 서버가 ARM이라 linux/arm64. 맥락은 `BlitzLink_26.3` 뿌리(`blitzlink-core`가 `data/net.json`을 품는다).
   ```
   docker buildx build --platform linux/arm64 -f native/blitzlink-relay/Dockerfile -t blitzlink-relay:26.3.0 .
   ```
2. **compose 서비스**(hyproj.com compose에 더한다):
   ```yaml
   blitzlink-relay:
     image: blitzlink-relay:26.3.0
     restart: unless-stopped
     command: ["--listen", "0.0.0.0:1947", "--no-stun", "--trust-proxy"]
     expose: ["1947"]          # nginx만 닿는다 — 밖에 열지 않는다(--trust-proxy가 X-Real-IP를 믿으므로)
   ```
   STUN은 이미 떠 있는 UDP 3478 STUN을 같이 쓰므로 포트를 더 열지 않는다. 따로 두고 싶으면 `--stun 0.0.0.0:3479` +
   `ports: ["3479:3479/udp"]` + `BLITZLINK_NET_JSON`의 `stun`을 `stun:hyproj.com:3479`로.
3. **nginx**(같은 server 블록):
   ```nginx
   location /relay/mcje/ {
       proxy_pass http://blitzlink-relay:1947/;
       proxy_http_version 1.1;
       proxy_set_header Upgrade $http_upgrade;
       proxy_set_header Connection "upgrade";
       proxy_set_header X-Real-IP $remote_addr;
       proxy_read_timeout 120s;   # 호스트는 20초마다 ping
   }
   ```
4. **방화벽** — 새로 열 것 없음(TCP는 nginx 443, STUN 3478/udp는 이미 열려 있다).
5. **확인** — `curl https://hyproj.com/relay/mcje/health` → `ok`, `curl https://hyproj.com/relay/mcje/servers` → `{"relay":1,"servers":[…]}`,
   전용 서버 콘솔에 「참가 코드 MCJE-…」, 다른 PC에서 그 코드로 참가.

## 네트워크 설정(`data/net.json`)

저장소의 `data/net.json`은 **자리표시**다(`https://blitzlink.example.com/relay/mcje`, `stun:blitzlink.example.com:3478`). 사이드카는 빌드 때
이 파일을 품고, 모드도 같은 파일을 읽는다.

- **공식 빌드** — GitHub Actions(`.github/workflows/natives.yml`의 「Network config」 단계)가 빌드 전에 저장소 변수 `BLITZLINK_NET_JSON`으로
  `data/net.json`을 갈아 끼운다. HY-Project/BlitzLinkMC의 값은 `{"relay":"https://hyproj.com/relay/mcje","stun":["stun:hyproj.com:3478"]}`.
  모드 jar 빌드도 같은 변수를 쓴다.
- **포크·로컬 빌드**는 자리표시 그대로다. 그 사이드카는 `--relay` 없이는 시작하지 않고(`error{reason:"notConfigured"}`), 자리표시 STUN에는
  묻지 않는다. 자기 네트워크를 쓰려면 `blitzlink-relay`를 띄우고 그 주소를 `data/net.json`에 넣거나 포크의 `BLITZLINK_NET_JSON`에 둔다.
- **플레이어**는 모드의 고급 설정에서 커스텀 중계를 고를 수 있다 — 모드가 사이드카에 `--relay`·`--stun`을 넘기면 `data/net.json`보다 앞선다.

## 사이드카 바이너리

네 플랫폼 모두 GitHub Actions(`.github/workflows/natives.yml`, 공개 저장소 `HY-Project/BlitzLinkMC`)가 빌드해 artifact 하나(`natives` —
`natives/<플랫폼>/blitzlink-tunnel[.exe]` + `SHA256SUMS`)로 모으고, 모드 jar 하나가 이것을 품는다. Windows x64는 로컬에서도 빌드할 수 있다.

| 플랫폼 | 대상 | 러너 → artifact |
|---|---|---|
| Windows x64 | `x86_64-pc-windows-msvc` | `windows-2025-vs2026` → `natives-windows-x64` (로컬: `cargo build --release -p blitzlink-tunnel --target x86_64-pc-windows-msvc` → `native/target/x86_64-pc-windows-msvc/release/blitzlink-tunnel.exe`) |
| Windows arm64 | `aarch64-pc-windows-msvc` | `windows-11-vs2026-arm` → `natives-windows-arm64` |
| Linux x64 | `x86_64-unknown-linux-gnu` | `ubuntu-26.04` → `natives-linux-x64` |
| Linux arm64 | `aarch64-unknown-linux-gnu` | `ubuntu-26.04-arm` → `natives-linux-arm64` |

Gradle 빌드가 `natives/{windows-x64,windows-arm64,linux-x64,linux-arm64}/`와 SHA-256 목록을 모드 jar 하나에 넣는다.

## 아직 없는 것

- 공식 서명 서버 구분(출시).
- IPv6 후보(지금은 IPv4 소켓 하나).
- 여러 LAN 인터페이스 — 받은 패킷의 목적지를 「바깥으로 나가는 LAN 주소」 하나로 미루어 정한다.
