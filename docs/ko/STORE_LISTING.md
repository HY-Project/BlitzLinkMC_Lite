# 스토어 등록 시트 — Modrinth · CurseForge

올릴 때 채울 칸. 목록은 두 판으로 나뉜다.

- **CurseForge·GitHub**(기본판) — 본문 [STORE_DESCRIPTION.md](STORE_DESCRIPTION.md), 생성한 픽셀아트 아이콘·배너·로더 배지, 기본 jar.
- **Modrinth**(**BlitzLink Lite HAE**, Human Art Edition) — Modrinth는 AI가 만든 프로젝트 그림을 금지하므로 본문
  [STORE_DESCRIPTION_MODRINTH.md](STORE_DESCRIPTION_MODRINTH.md), `branding/modrinth/`의 손그림 아이콘, 배너·배지 대신 게임 스크린샷,
  `BlitzLink-Lite-HAE-<version>+mc26.3.jar` jar(코드는 같고 모드 아이콘만 손그림)를 쓴다.

본문은 그대로 붙여 넣는다. 제목·목록·굵게·링크·이미지만 쓴
평범한 Markdown이라(HTML·표 없음) 두 사이트에서 똑같이 보인다. 두 사이트 모두 영어 본문이 기본이니 공식 본문은
[../en/STORE_DESCRIPTION.md](../en/STORE_DESCRIPTION.md), 한국어판은 한국 커뮤니티·추가 설명용이다.
배너·로더 배지·스크린샷은 `mc-26.3` 브랜치의 `branding/`과 `docs/screenshots/`를 raw.githubusercontent.com 주소로 불러오므로, 붙여 넣기
전에 둘 다 푸시해 둔다. 사이트가 외부 이미지를 막으면 그 사이트에 PNG를 직접 올리고 주소만 바꾼다.
English: [../en/STORE_LISTING.md](../en/STORE_LISTING.md)

## 공통

| 칸 | 값 |
|---|---|
| 이름 | BlitzLink Lite |
| 슬러그 | `blitzlink-lite` |
| 요약(짧게, 100자 이하) | Join any Minecraft server with one code. Peer-to-peer, usually no port forwarding. (CurseForge용, Modrinth는 아래 따로) |
| 요약(길게, 256자 이하) | Share a join code like MCJE-7K3Q and friends join from the multiplayer screen. The relay only introduces you; game traffic flows directly over WebRTC. Mod list check before joining, real player IPs, NeoForge + Fabric in one jar. |
| 한국어 요약(참고) | 코드 하나로 마인크래프트 서버에 들어간다. P2P 직접 연결, 대개 포트포워딩 없이. |
| 게임 버전 | 26.3 |
| 라이선스 | Apache-2.0 |
| 소스 | https://github.com/HY-Project/BlitzLinkMC_Lite |
| 이슈 | https://github.com/HY-Project/BlitzLinkMC_Lite/issues |
| 웹사이트 | https://hyproj.com |

## Modrinth

| 칸 | 값 |
|---|---|
| 프로젝트 종류 | Mod(Bukkit 플러그인은 같은 프로젝트에 파일을 더해 로더 `bukkit`·`spigot`·`paper`·`purpur`로 올릴 수 있다) |
| 카테고리 | Social, Utility, Management |
| 환경 | Client: **required**(코드로 참가) · Server: **optional**(전용 서버에서 열 때만) |
| 로더(모드 jar) | NeoForge, Fabric |
| 의존성 | Fabric API — required(Fabric만) |
| 요약 | Join any Minecraft server with one code. Peer-to-peer, usually no port forwarding. (Modrinth Exclusive Human Art Edition) (121자, Modrinth 한도 256자) |
| 본문 | [../en/STORE_DESCRIPTION_MODRINTH.md](../en/STORE_DESCRIPTION_MODRINTH.md)(한국어판 [STORE_DESCRIPTION_MODRINTH.md](STORE_DESCRIPTION_MODRINTH.md)). 첫 줄이 판 표시: 🎨 **Modrinth Exclusive: Human Art Edition** |
| 아이콘 | `branding/modrinth/blitzlink-lite-icon-hand-drawn-512.png`(Piskel 손그림, BlitzLink-HAE jar 안의 모드 아이콘과 같은 그림) |
| 갤러리 | `docs/screenshots/`의 스크린샷 다섯 장을 아래 순서대로. **01을 대표 이미지로**. 배너·배지는 생성 그림이라 넣지 않는다 |
| 모드 파일 | `BlitzLink-Lite-HAE-<version>+mc26.3.jar` |

## CurseForge

| 칸 | 값 |
|---|---|
| 프로젝트(모드) | Minecraft → Mods. 카테고리 **Server Utility**, **Utility & QoL** |
| 프로젝트(플러그인) | Minecraft → **Bukkit Plugins**에 플러그인 jar용 프로젝트를 따로. 카테고리 Admin Tools / General |
| 모드 로더(모드 jar) | NeoForge, Fabric |
| 관계 | Fabric API — Required Dependency |
| 본문 | [../en/STORE_DESCRIPTION.md](../en/STORE_DESCRIPTION.md)(배너·배지와 스크린샷 포함) |
| 로고 | `branding/blitzlink-lite-icon-512.png`(기본 jar의 게임 안 `assets/blitzlink/icon.png`와 같은 그림) |
| 배너 | `branding/blitzlink-lite-banner-4x.png` |
| 이미지 | `docs/screenshots/`의 스크린샷 다섯 장. 업로더가 `.webp`를 받지 않으면 04·05를 PNG로 바꿔 올린다 |
| 모드 파일 | `blitzlink-lite-<version>+mc26.3.jar` |

## 올릴 파일

| 파일 | 어디에 | 로더 |
|---|---|---|
| `blitzlink-lite-<version>+mc26.3.jar` | CurseForge 모드 프로젝트, GitHub 릴리스 | NeoForge, Fabric(한 파일로 둘 다) |
| `BlitzLink-Lite-HAE-<version>+mc26.3.jar` | Modrinth 모드 프로젝트 | NeoForge, Fabric(한 파일로 둘 다) |
| `blitzlink-lite-bukkit-26.3-<version>.jar` | Modrinth(같은 프로젝트) 또는 CurseForge Bukkit Plugins 프로젝트 | Bukkit, Spigot, Paper, Purpur |

모두 GitHub Actions `build` 워크플로가 만든다. **저장소 변수 `BLITZLINK_NET_JSON`을 넣고 만든 빌드만** 올린다 — 자리표시 `data/net.json`으로
만든 빌드는 중계가 없어 「중계 서버가 설정되지 않았습니다」가 뜬다.

## 심사 메모(동봉 네이티브 라이브러리)

BlitzLink Lite에는 **실행 파일이 없다** — 프로세스로 풀어 띄우는 것이 하나도 없다. CurseForge 파일 설명이나 Modrinth 심사 메모에 아래를 적는다(영어 그대로).

> BlitzLink Lite contains no executable. Its WebRTC tunnel is Java code that runs inside the game. WebRTC itself is the open-source
> webrtc-java library (Apache-2.0, https://github.com/devopvoid/webrtc-java, from Maven Central `dev.onvoid.webrtc:webrtc-java:1.0.0`),
> whose JNI build of Google's libwebrtc (`.dll`/`.so`/`.dylib` for Windows, Linux and macOS) is packed into the jar unchanged, extracted
> to `config/blitzlink/natives/` and loaded with `System.load`. Nothing is downloaded at runtime. It contacts only the configured relay,
> STUN servers and the peer the player chose to join. Source: https://github.com/HY-Project/BlitzLinkMC_Lite (built by public GitHub Actions).

## 스크린샷

`docs/screenshots/`에 있다. Modrinth(갤러리·본문), CurseForge, README 모두 이 순서로 쓴다. 제목·설명은 사이트에 영어로 넣는다.

| 파일 | 제목 | 설명 |
|---|---|---|
| `01-relay-list.png` | Join with a code | The BlitzLink multiplayer screen: join code box and the public server list. A server for another loader is marked red with ✕. |
| `02-server-card.png` | Check before you join | The server card: version, loader, identity and the server's mod list, fetched straight from the server. |
| `03-loader-mismatch.png` | Wrong loader? You'll know | The same Fabric server seen from a NeoForge client: the card turns red and says why. |
| `04-pause-menu.webp` | Open your world | The BlitzLink button sits in the pause menu where "Open to LAN" used to be. |
| `05-playing-together.webp` | Then just play | Two players in the same world, connected peer-to-peer. |

나중에 더 찍어 두면 좋은 것: 참가 코드가 뜬 「서버 열기」, `BlitzLink: join code MCJE-…`가 찍힌 전용 서버 콘솔, 고급 설정(커스텀 중계).
