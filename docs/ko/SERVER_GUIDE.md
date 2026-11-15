# BlitzLink 서버 안내 (HY Project)

BlitzLink는 마인크래프트 서버를 **참가 코드 `MCJE-XXXX`** 하나로 잇는다. hyproj.com의 중계(`blitzlink-relay`)는 WebRTC
Offer/Answer(SDP) 한 쌍과 공개 목록만 다루고, **게임 데이터는 플레이어와 서버가 WebRTC DataChannel로 직접** 주고받는다
(`docs/ko/RELAY.md` 참고).

```
[MC 클라] ─TCP→ 127.0.0.1:임의 ─ [tunnel join] ══ WebRTC(UDP, 직접) ══ [tunnel host] ─TCP→ 127.0.0.1:게임포트 ─ [서버]
                                        └─ UDP가 막힌 망: 서버 내장 TURN(TCP) ─┘
```

## 어디에 깔리나

| 배포물 | 쓰는 곳 |
|---|---|
| `blitzlink-lite-<버전>+mc26.3.jar` | **NeoForge와 Fabric 공용 파일 하나** — 클라이언트·전용 서버(`mods/`, Fabric은 Fabric API도 필요) |
| `blitzlink-lite-bukkit-26.3-*.jar` | Spigot · Paper · Purpur 등 버킷 계열 서버(`plugins/`) |

BlitzLink Lite에는 실행 파일(사이드카)이 없다. 터널은 서버 JVM 안에서 돌고, WebRTC는 jar에 든 webrtc-java(libwebrtc)가 맡는다.
libwebrtc는 처음 쓸 때 `config/blitzlink/natives/`(플러그인은 `plugins/BlitzLink/natives/`)에 풀린다.
지원: Windows·Linux·macOS의 x64와 arm64. 화면 없는 Linux 서버(X11·GLib가 없는 Docker 이미지 등)도 그대로 된다. libwebrtc가 링크한
화면 캡처용 라이브러리 자리를 Lite가 빈 대역으로 채운다.

## 전용 서버 (NeoForge / Fabric)

1. `mods/`에 넣고 켠다. 콘솔에 `BlitzLink: join code MCJE-…`가 찍힌다. `/blitzlink code`로 다시 볼 수 있다.
2. 설정은 `config/blitzlink/config.json`:
   - `enabledOnDedicated` — 켤 때 중계에 올릴지
   - `publicListing` — 공개 목록에 띄울지 (`/blitzlink public on|off`)
   - `upnp` — 공유기에 게임 포트(TCP)를 열어 달라고 청한다. Lite는 연결마다 UDP 포트가 바뀌어 UDP는 열지 않는다(홀펀칭·TURN이 맡는다)
   - `turn` — 게임 포트 TCP로 받는 내장 TURN(첫 바이트로 마크 접속과 가른다)
   - `requiredClientMods` / `serverOnlyMods` — 클라이언트에 꼭 필요한 모드 / 서버 전용 모드를 손으로 정한다
3. 코드는 `config/blitzlink/code.txt`에 남아 껐다 켜도 같다. 새 코드는 `/blitzlink regen`.
4. 터널로 들어온 플레이어의 실제 IP가 로그·밴에 남는다(터널이 알려 준 주소로 바꿔 보여 줌).

## 버킷 계열 서버 (Spigot / Paper)

1. `plugins/`에 `blitzlink-lite-bukkit` jar를 넣는다. 설정은 `plugins/BlitzLink/config.yml`.
2. 버킷 서버는 바닐라 프로토콜이라 **바닐라·NeoForge·Fabric 클라이언트 모두** 들어올 수 있다. 플러그인 목록은 클라이언트 서버 카드에
   「서버의 플러그인」으로 보인다(설치할 것 없음).
3. 내장 TURN은 게임 포트를 나눠 쓸 수 없어 따로 TCP 포트를 연다(`turn-port`, 기본 게임 포트+1, `-1`이면 끔). 공유기 뒤라면
   이 포트도 연다.
4. 실제 IP: `proxy-protocol: true` + Paper `config/paper-global.yml`의 `proxies.proxy-protocol: true`를 **같이** 켠다.
   하나만 켜면 모든 접속이 실패한다.

## 클라이언트 — 「서버 열기」

- 타이틀 → 멀티플레이 → BlitzLink → 「서버 열기」:
  - **월드 골라서 열기** — 기존 싱글 월드를 열면 곧바로 BlitzLink로 공개된다.
  - **서버 ZIP 가져오기** — 서버 폴더(또는 월드)를 묶은 ZIP을 창에 끌어다 놓거나 `<게임 폴더>/blitzlink-import/`에 넣는다.
    가장 얕은 `level.dat`의 월드를 새 세이브로 옮기고, 버킷식 `<월드>_nether`/`<월드>_the_end`를 합친다.
    ZIP 안 `mods/`의 모드가 내 클라이언트에 없으면 빨갛게 보인다(통합 서버는 내 클라이언트의 모드로 돈다).
- 게임 안 ESC → **BlitzLink 서버 열기**(호스팅 중이면 「BlitzLink 서버 관리」) — 예전 「랜 서버 열기」 자리.

## 참가와 모드 확인

코드를 넣으면 서버와 직접 이은 뒤 서버의 모드 목록을 받아(중계를 지나지 않음) 내 것과 비교한다.

| 표시 | 뜻 |
|---|---|
| 빨간 배경 + ✕ 「설치 필요」/「버전 다름」 | 그 모드를 깔거나 버전을 맞춰야 「참가」가 켜진다 |
| ✓ | 같은 버전으로 있음 |
| 흐린 글 「서버 전용」 | 클라이언트에 필요 없음 |
| 서버 줄이 빨간 배경 + ✕ | 들어갈 수 없는 서버 — 게임 버전이 다르거나 NeoForge↔Fabric처럼 로더가 다름 |

서버 딱지: 게임 버전 + `VANILLA` / `NEOFORGE` / `FABRIC` / `BUKKIT`(Spigot·Paper 등 호환 서버 전부).

## 직접 띄운 중계

공식 빌드에는 hyproj.com 중계가 들어 있다. 저장소의 자리표시 `data/net.json`으로 만든 빌드(포크·로컬 빌드)에는 중계가 없어서,
설정하기 전까지 「중계 서버가 설정되지 않았습니다」가 뜬다(`docs/ko/RELAY.md`의 「네트워크 설정」).

- **플레이어:** BlitzLink → **설정** — *중계 서버 URL*(예: `https://relay.example.org/relay/mcje`)과 *STUN 서버*
  (`stun:호스트:포트`, 쉼표로 구분). **시험**은 `GET <중계>/health` → `ok`를 확인한다. 비워 두면 빌드에 든 네트워크를 쓴다.
- **NeoForge / Fabric 서버:** `config/blitzlink/config.json`의 `relay`, `stun`.
- **버킷 서버:** `plugins/BlitzLink/config.yml`의 `relay:`, `stun:`.

호스트와 플레이어가 같은 중계를 써야 서로 찾는다. 공개 목록도 그 중계에서 받는다.

## 로그

모든 로그는 영어로 남는다(모드 관례). 화면 문구는 `assets/blitzlink/lang/{ko_kr,en_us}.json`.
