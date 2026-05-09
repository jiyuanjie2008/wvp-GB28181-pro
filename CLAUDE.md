# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

WVP-GB28181-Pro is a GB28181-2016 video surveillance platform that manages IP cameras, NVRs, and video streams. It also supports JT/T 808 (vehicle positioning) and JT/T 1078 (vehicle video) standards. Media streaming is handled by ZLMediaKit (ZLM), a separate service that WVP controls via REST API and HTTP webhooks.

## Tech Stack

- **Backend**: Java 21, Spring Boot 3.4.4, Maven, JAIN-SIP (GB28181 SIP stack), MyBatis, Virtual Threads enabled
- **Frontend**: Vue 2.6.11, Element UI 2.15.14, OpenLayers (maps), ECharts, Axios
- **Database**: MySQL 8 (primary), PostgreSQL, Kingbase, H2 (embedded for testing)
- **Media Server**: ZLMediaKit (external, deployed alongside WVP)
- **Players**: Jessibuca (WS-FLV), h265web.js (H.265), WebRTC

## Build & Run Commands

```bash
# Build backend (from project root)
mvn clean package -DskipTests

# Run backend (jar mode)
java -jar target/wvp-pro-2.7.4.jar --spring.profiles.active=dev

# Run backend (war mode)
mvn clean package -DskipTests -Pwar

# Frontend development (from web/ directory)
cd web
npm install
npm run dev          # Dev server on port 9528, proxies /dev-api to localhost:18080

# Frontend production build
npm run build:prod   # Output goes to src/main/resources/static/

# Docker deployment (from docker/ directory)
cd docker
docker-compose up -d --build
```

## Architecture

### Core Backend Packages (`src/main/java/com/genersoft/iot/vmp/`)

- **`gb28181/`** — GB28181 SIP signaling: device registration, catalog query, INVITE/BYE for video streams, PTZ control
  - `controller/PlayController.java` — REST API for live play (`/api/play/start/...`)
  - `controller/PlaybackController.java` — REST API for historical playback
  - `service/impl/PlayServiceImpl.java` — Core orchestration for play/stop/record (the largest service, ~1850 lines)
  - `transmit/cmd/impl/SIPCommander.java` — Constructs and sends all SIP requests (INVITE, BYE, MESSAGE, etc.)
  - `transmit/event/response/impl/InviteResponseProcessor.java` — Handles SIP INVITE 200 OK responses
  - `session/SipInviteSessionManager.java` — Tracks active SIP INVITE sessions and SSRC allocation
- **`media/`** — ZLM integration layer
  - `zlm/ZLMHttpHookListener.java` — Receives ZLM webhooks at `/index/hook/*` (stream registered, stream lost, play auth, etc.)
  - `zlm/ZLMMediaNodeServerService.java` — ZLM node management, RTP server creation, stream URL generation
  - `zlm/ZLMRESTfulUtils.java` — HTTP client for ZLM REST API
  - `bean/MediaServer.java` — MediaServer entity with connection details and port config
- **`conf/`** — Spring configuration: `MediaConfig` (ZLM connection), `SipConfig` (SIP server), `UserSetting` (feature flags)
- **`vmanager/`** — Management REST controllers (cloud record, users, alarms, media servers)
- **`web/`** — External API controllers (legacy stream API, third-party integration)
- **`common/`** — Shared DTOs: `StreamInfo`, `StreamURL`, `InviteInfo`, `SSRCInfo`, enums
- **`storager/`** — MyBatis DAO layer for database access

### Frontend Structure (`web/src/`)

- `api/play.js` — Play/start/stop API calls
- `views/dialog/devicePlayer.vue` — Main video player dialog (tabbed: Jessibuca / WebRTC / h265web)
- `views/device/` — Device and channel management pages
- `store/modules/play.js` — Vuex play state

### Docker Services (`docker/`)

Five containers on bridge network `media-net`:

| Service | Image | Exposed Ports | Role |
|---------|-------|---------------|------|
| `polaris-nginx` | Custom (nginx:alpine) | 8080 | Reverse proxy + static frontend |
| `polaris-wvp` | Custom (JDK 21) | 18978 (HTTP), 8160 (SIP) | WVP application |
| `polaris-media` | zlmediakit/zlmediakit:master | 10001 (RTMP), 10002 (RTSP), 10003 (RTP) | ZLM media server |
| `polaris-redis` | redis:latest | None (internal) | Session/cache store |
| `polaris-mysql` | mysql:8.0 | None (internal) | Database |

Nginx proxies: `/api/` → WVP:18978, `/rtp/` and `/mp4_record/` → ZLM:80 (with WebSocket support).

### Video Play Flow (Live)

1. Browser calls `GET /api/play/start/{deviceId}/{channelId}` via Nginx
2. `PlayController` → `PlayServiceImpl.play()` → selects a ZLM node
3. WVP opens an RTP server on ZLM (allocates SSRC, port 10003 in single-port mode)
4. WVP sends SIP INVITE to the camera with SDP (contains ZLM's IP:port and SSRC)
5. Camera responds 200 OK → WVP sends ACK (three-way handshake complete)
6. Camera pushes RTP media to ZLM
7. ZLM fires `on_stream_changed` webhook → WVP publishes `MediaArrivalEvent`
8. WVP generates stream URLs for all protocols (ws_flv, rtc, rtmp, rtsp, hls, etc.) and returns them
9. Browser's Jessibuca player opens WebSocket to `ws://IP:8080/rtp/{streamId}.live.flv`
10. Nginx proxies `/rtp/` to ZLM:80, video plays

### Stream Naming Convention

Stream IDs follow the pattern `{deviceId}_{channelId}` (e.g., `34020000001320000001_34020000001320000002`), with app name `rtp` for GB28181 streams. Playback streams append `_startTime_endTime`.

### ZLM Hook System

ZLM calls WVP via HTTP webhooks for lifecycle events. The hooks are configured in ZLM's `config.ini` to point to `http://polaris-wvp:18978/index/hook/`. Key hooks: `on_stream_changed` (stream register/deregister), `on_stream_not_found` (triggers auto-play), `on_stream_none_reader` (auto-close), `on_server_keepalive`.

## Configuration

- **Active Spring profile**: Set in `src/main/resources/application.yml` (`spring.profiles.active`)
- **Profile-specific config**: `application-{profile}.yml` in `src/main/resources/` (dev) or `docker/wvp/wvp/` (docker)
- **Docker env vars**: `docker/.env` drives all port/IP configuration for the Docker deployment
- **ZLM config**: `docker/media/config.ini` — ZLM ports, hook URLs, protocol settings
- **Nginx config template**: `docker/nginx/templates/nginx.conf.template` — uses `${Stream_IP}` env var for URL rewriting

Key config properties in `application-{profile}.yml`:
- `media.ip` / `media.stream-ip` / `media.sdp-ip` — ZLM connection and stream URL generation
- `media.http-port` / `media.rtmp-port` / `media.rtsp-port` — ZLM ports
- `sip.port` / `sip.domain` / `sip.id` — SIP server identity
- `user-settings.stream-on-demand` — Auto-play on stream-not-found
- `user-settings.use-source-ipAsStream-ip` — Replace stream URL host with requesting client's IP
