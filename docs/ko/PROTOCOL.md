# BlitzLink 사이드카 제어 프로토콜 (`blitzlink-tunnel`)

> **BlitzLink Lite:** 이 문서는 네이티브 BlitzLink와 같은 규격이다. Lite에는 사이드카 실행 파일이 없고, 같은 인자를 받아 같은 JSON 줄을 내는 `tunnel/`의 `LiteTunnel`(Java, webrtc-java)이 게임 JVM 안에서 그 역할을 한다(stdin/stdout·종료 코드 대신 메서드 호출과 콜백). 중계·P2P 채널·신원·TURN은 바이트 단위로 같아서 네이티브와 Lite가 섞여도 된다(`tunnel/src/test/java/com/hyproj/blitzlink/lite/InteropTest.java`). 아래의 Rust 경로와 사이드카 빌드 이야기는 네이티브 저장소(https://github.com/HY-Project/BlitzLinkMC)의 것이다.

모드·플러그인(Java)이 jar의 `natives/<os>-<arch>/blitzlink-tunnel(.exe)`를 풀어 띄우고 **stdin/stdout JSON 줄**로 제어한다(한 줄에 객체 하나,
태그 `t`). Rust 쪽 정본은 `native/blitzlink-tunnel/src/control.rs`(Java → 사이드카)와 각 `emit(...)` 자리(사이드카 → Java)다.
중계 메시지와 P2P 흐름은 [RELAY.md](RELAY.md). English: [../en/PROTOCOL.md](../en/PROTOCOL.md)

## 띄우기

```
blitzlink-tunnel host --target 127.0.0.1:<mcPort> --port <mcPort> --relay <url> --stun <a,b> --key-file <path> --code-file <path>
                      [--public] [--upnp] [--no-turn] [--turn-listen <addr:port>] [--proxy-protocol] --json
blitzlink-tunnel join --code MCJE-XXXX --relay <url> --stun <a,b> --json [--turn-only]
```

| 인자 | 뜻 |
|---|---|
| `--target` | (host) 터널 접속마다 TCP로 붙을 MC 서버. 보통 `127.0.0.1:<mcPort>`. |
| `--port` | (host) MC 서버의 TCP 포트. WebRTC UDP 소켓도 이 번호를 먼저 시도한다(안 되면 아무 포트 — `status.udpPort`). `--turn-listen`이 없으면 내장 TURN도 이 포트로 알린다(`turn:<공인IP>:<port>?transport=tcp`). |
| `--relay` | 중계 주소(공식 `https://hyproj.com/relay/mcje`, 개발은 `http://127.0.0.1:1947`, 또는 모드 고급 설정의 커스텀 중계). 없으면 빌드 때 품은 `data/net.json`의 `relay` — 그것이 저장소 자리표시(`*.example.com`)면 `--relay` 없이는 `notConfigured`로 끝난다(RELAY.md 「네트워크 설정」). |
| `--stun` | STUN 주소 — **쉼표로 이은 인자 하나**(`stun:a:3478,stun:b:3478`). 여러 번 써도 된다. 없으면 `data/net.json`의 `stun`(자리표시 `*.example.com`은 뺀다). `off`면 묻지 않는다. |
| `--key-file` | (host) Ed25519 씨앗(16진 64자 한 줄). 없으면 만든다. 기본 `blitzlink-host.key`. |
| `--code-file` | (host) 지난번 참가 코드. 기본 `blitzlink-code.txt`. |
| `--public` | (host) 처음부터 공개 목록에 올린다(나중에 `public`으로 바꾼다). |
| `--upnp` | (host) 공유기에 TCP `--port`(와 `--turn-listen` 포트)·UDP `udpPort`를 UPnP-IGD로 연다. 끝낼 때 지운다. |
| `--no-turn` | (host) 내장 TURN을 켜지 않는다(`turnPort`가 오지 않는다). |
| `--turn-listen` | (host, **Bukkit**) 내장 TURN을 루프백 대신 이 TCP 주소(예: `0.0.0.0:25566`)에 바로 묶고, `answer.turn`에도 이 포트를 싣는다. `turnPort`는 그대로 알린다. 없으면 루프백에서 받고 Java가 게임 포트에서 넘겨준다. 포트 0이면 빈 포트(`turnPort`로 알림). |
| `--proxy-protocol` | (host, **Bukkit**) `--target`에 붙을 때 어떤 바이트보다 먼저 HAProxy PROXY protocol v2 머리로 플레이어의 실제 주소를 보낸다(Paper `proxies.proxy-protocol: true`). 주소를 모르면 `LOCAL` 머리. `peer`는 그대로 낸다. |
| `--turn-only` | (join, **개발용**) 직접 UDP 연결을 건너뛰고 호스트 TURN만 쓴다. |
| `--json` | stdout을 JSON 줄로. **Java는 늘 켠다.** 없으면 사람이 읽는 `[t] k=v …` 줄(개발용). |

- **stdout** — 사이드카 → Java, 한 줄에 JSON 하나.
- **stderr** — 사람이 읽는 로그(영어, `[blitzlink] …`). Java가 MC 로그로 옮긴다.
- **stdin** — Java → 사이드카, 한 줄에 JSON 하나. **stdin이 닫히면 `quit`과 같다**(게임이 죽어도 사이드카가 남지 않는다).
- **종료 코드** — 0: `quit`, (join) `closed` 뒤. 1: 오류(끝나기 직전에 `error{reason}` 한 줄).

## Java → 사이드카 (stdin)

| 메시지 | 모드 | 뜻 |
|---|---|---|
| `{"t":"listing","listing":{"name":…,"motd":…,"mcVersion":…,"protocol":…,"loader":…,"loaderVersion":…,"modCount":…,"modsHash":…,"players":…,"maxPlayers":…,"onlineMode":…}}` | host | 공개 목록·참가 화면 요약. `loader`는 `vanilla`·`neoforge`·`fabric`·`bukkit`(그 밖은 `vanilla`), `loaderVersion`은 `21.3.4`·`Paper 26.3-251` 꼴. 빠진 필드는 기본값. `t`와 같은 층에 둔 꼴도 받는다. 띄운 직후 한 번, 인원이 바뀔 때마다. 사이드카는 5초마다 바뀐 것만 중계에 보내고, 첫 `listing`을 5초까지 기다린 뒤 올라간다. |
| `{"t":"meta","info":{…}}` | host | 참가자에게 건넬 서버 정보(`ServerInfo{mcVersion, protocol, loader, loaderVersion, mods:[{id,name,version,side,required}]}`, `side`는 `plugin`일 수도). P2P `meta` 채널로 **그대로** 건넨다(256 KB 이하). 다시 보내면 붙어 있는 참가자에게도 다시 간다. |
| `{"t":"public","value":true}` | host | 공개 목록에 올리기·내리기. `{"t":"public","public":true}`도 받는다. |
| `{"t":"turnPeer","port":51234,"addr":"203.0.113.5:40000"}` | host | *선택.* Java가 게임 포트로 받은 TURN 접속(첫 바이트 0x00~0x03)을 `turnPort`로 넘길 때 — `port`는 **Java 루프백 소켓의 로컬 포트**, `addr`은 바깥 상대 주소. 주면 TURN 플레이어의 `peer.addr`(과 PROXY 머리)이 루프백 대신 실제 IP가 된다. 루프백 접속을 연 직후에 보낸다. |
| `{"t":"quit"}` | 둘 다 | 정리하고(상대에게 닫는다고 알린다) 0으로 끝낸다. |

모르는 줄·풀 수 없는 줄은 stderr에 적고 무시한다.

## host → Java (stdout)

| 메시지 | 언제 |
|---|---|
| `{"t":"turnPort","port":N}` | 시작 직후(`--no-turn`이 아니면). 내장 TURN의 루프백 TCP 포트(`--turn-listen`이면 묶은 포트). Java는 첫 바이트가 0x00~0x03인 게임 포트 접속을 `127.0.0.1:N`으로 잇는다(`--turn-listen`이면 할 일 없음). |
| `{"t":"hosted","code":"MCJE-7K3Q","fingerprint":"ABCD-EFGH-IJKL-MNOP"}` | 중계에 올라갈 때마다(재접속 포함, 코드는 보통 같다). `--code-file`에도 적힌다. |
| `{"t":"status","state":"online"\|"reconnecting","publicAddr"?:"ip:port","upnp"?:bool, …}` | 무엇이든 바뀌면(0.5초마다 비교). `publicAddr`는 STUN이 답한 뒤, `upnp`는 `--upnp`일 때 결과가 나면(`true` = 열림). 덧붙는 필드: `code`, `reason`(재접속 중), `public`, `udpPort`, `peers`(`meta`가 열린 참가자 수), `tunnels`(열린 `mc-N`), `turnAllocs`. |
| `{"t":"peer","localPort":N,"addr":"ip:port"}` | 터널 하나가 `--target`에 붙었을 때, **그 소켓에 어떤 바이트도 쓰기 전에**. `localPort`는 그 소켓의 로컬 포트(MC 서버는 `127.0.0.1:N`에서 온 접속으로 본다), `addr`은 플레이어의 실제 주소(`ip:port` 또는 `[v6]:port`) — 직접 연결이면 뽑힌 ICE 짝의 상대, TURN이면 그 TCP 상대(`--turn-listen`이면 플레이어 자신, 아니면 `turnPeer`의 주소 또는 루프백). 모를 때 `"unknown"`. |
| `{"t":"peerClosed","localPort":N}` | 그 터널의 MC 서버 쪽 소켓이 닫혔다(어느 쪽이 닫았든). |
| `{"t":"error","reason":…}` | 중계의 `badSignature`·`version`은 호스팅에 치명적이다(사이드카는 돌지만 코드가 없다). 1로 끝나기 직전에도. |

호스트는 `meta`가 열린 참가자를 내리지 않는다(상대가 닫거나 ICE가 10초 넘게 끊겼을 때만).

## join → Java (stdout)

| 메시지 | 언제 |
|---|---|
| `{"t":"status","state":"relay","code":"MCJE-7K3Q"}` | 시작 — 후보를 모으고 중계로 SDP를 주고받는 중. |
| `{"t":"status","state":"connecting"}` | 직접(UDP·홀펀칭) 시도 시작. |
| `{"t":"status","state":"turn"}` | TURN 시도 시작(2.5초 안에 UDP가 안 열렸거나 실패, 또는 `--turn-only`). |
| `{"t":"status","state":"open","via":"udp"\|"turn"[,"rttMs":N]}` | P2P 연결이 열렸다. 그 뒤 15초마다 `rttMs`(meta 핑 왕복)를 싣고 다시. |
| `{"t":"meta","info":{…},"fingerprint":…,"listing":{…},"via":…,"rttMs":N\|null}` | 채널이 열리고 **호스트 신원이 확인되면** — `info`는 호스트 Java가 준 그대로, `fingerprint`는 중계와 호스트 서명이 둘 다 확인한 지문, `listing`은 중계가 다듬은 요약. 호스트가 정보를 바꾸면 다시(`rttMs: null`). |
| `{"t":"listen","port":N}` | 첫 `meta` 바로 뒤. MC를 `127.0.0.1:N`에 붙이면 된다. 접속마다 `mc-N` 채널이 하나씩(상태 핑·로그인 따로 붙어도 된다). |
| `{"t":"closed"}` | 열린 뒤 P2P 세션이 끝났다(호스트가 끝남·연결 끊김). 그 뒤 0으로 끝난다. |
| `{"t":"error","reason":…}` | 1로 끝나기 직전. |

join 세션은 `quit`이나 stdin EOF까지 살아 있다 — 모드 목록 화면에 오래 머물러도 된다.

### 오류 `reason`

| 값 | 뜻 |
|---|---|
| `noSuchCode` | 그런 코드가 없다(코드 꼴이 아닌 입력도). |
| `hostTimeout` | 호스트가 제때 답하지 않았다. |
| `refused` | 호스트가 받지 않았다(가득 참·닫는 중). |
| `rateLimited` · `full` | 중계가 막았다. |
| `version` | 중계 프로토콜 판이 다르다 — 모드를 새 판으로. |
| `badSignature` · `badMessage` | (host) 중계가 서명·메시지를 거절했다. |
| `relayUnreachable` | 중계에 붙지 못했다. |
| `badPacket` | 중계·호스트가 풀 수 없는 것을 보냈다. |
| `rtcFailed` | 14초 안에 직접도 TURN도 열리지 않았다(또는 신원 확인 전에 끊겼다). |
| `badIdentity` | 호스트 서명이 틀렸거나 지문이 중계가 건넨 것과 다르다(중간자 의심 — 빨간 경고). |
| `timeout` | 연결은 열렸지만 신원 확인(`probe`)에 답이 없다. |
| `notConfigured` | (둘 다) 중계가 설정되지 않았다 — 자리표시 `data/net.json`으로 빌드했고 `--relay`도 없다. 시작하자마자, 1로 끝난다. |

## 내장 TURN — Java가 할 일(호스트)

UDP가 막힌 망의 플레이어는 호스트를 통해 TURN(RFC 8656, TCP)으로 들어온다.

- **모드 서버(NeoForge·Fabric, 기본)** — TURN은 **게임 포트**로 온다. Java는 MC 서버 TCP 채널 맨 앞에서 첫 바이트를 본다.
  - `0x00`~`0x03`(STUN 머리): 그 접속의 **모든 바이트(첫 바이트 포함)**를 `127.0.0.1:<turnPort>`로 양방향 파이프. 루프백 접속을 연 직후 `turnPeer`를 보내면 좋다.
  - 그 밖: 핸들러를 지우고 보통 MC 접속으로. MC 핸드셰이크 첫 바이트는 varint 길이(≥ 0x07) 또는 레거시 핑 `0xFE`라 겹치지 않는다.
- **Bukkit** — `--turn-listen 0.0.0.0:<포트>`(그 TCP 포트가 밖에서 닿아야 한다 — UPnP가 연다)와 `--proxy-protocol` + Paper의
  `proxies.proxy-protocol: true`. 첫 바이트 분기는 필요 없다.

TURN은 이 호스트의 WebRTC UDP 소켓으로만 데이터를 넘긴다(열린 중계가 아니다). 자격은 호스트가 Offer마다 만들어 중계로 건넨다(HMAC, 300초 안에 Allocate).

## P2P 채널(참고 — 사이드카끼리)

- `meta`(신뢰·순서, 참가자가 SDP에서 만든다) — JSON 텍스트: 참가자 `probe{nonce}` → 호스트 `info{key, sig, info}`(`sig` = Ed25519(`blitzlink/server-identity/v1` ‖ nonce)),
  바뀐 정보는 `info{info}`, 살아 있는지 `ping{n}` / `pong{n}`. 정본 `native/blitzlink-tunnel/src/meta.rs`.
- `mc-N`(신뢰·순서, 참가자가 로컬 TCP 접속마다 연다) — 바이트 스트림을 16 KB 메시지로. 밀린 양이 1 MB를 넘으면 TCP 읽기를 멈추고 256 KB 밑에서 다시
  읽는다. 한쪽 TCP가 닫히면(밀린 것을 다 보낸 뒤) 채널을 닫고, 채널이 닫히면 반대쪽 TCP를 닫는다.
