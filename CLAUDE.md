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

### Redis HA (Sentinel)

The Docker profile connects to the shared Redis via **Sentinel** (not a single host): `spring.data.redis.sentinel.master=mymaster`, `sentinel.nodes=jxt-redis-sentinel-1/2/3:26379` (compose env `REDIS_SENTINEL_MASTER` / `REDIS_SENTINEL_NODES`), data password `REDIS_PASSWORD`, db index `REDIS_DATABASE` (default 1). The authoritative config is `docker/wvp/wvp/application-docker.yml` (bind-mounted, loaded via `--spring.config.location=/opt/ylcx/wvp/application.yml`); editing it only needs `docker compose up -d polaris-wvp` (no image rebuild).

Failover behavior — Spring Data Redis / Lettuce Sentinel has **no native periodic topology refresh** (that is a Redis Cluster-only feature), so:
- **Master crash / unreachable** (the real HA case): WVP **auto-follows** the new master. Lettuce's ConnectionWatchdog retries the dead master, refreshes topology, reconnects to the promoted master in ~10s. Verified.
- **Manual / demotion-style failover** (`sentinel failover`, where the old master stays alive but becomes a read-only replica): WVP does **not** auto-follow — the TCP connection never breaks, so Lettuce keeps using the now-read-only node and writes throw `io.lettuce.core.RedisReadOnlyException`. **Restart WVP** (`docker restart docker-polaris-wvp-1`) to re-query Sentinel and reconnect to the current master.

Depends on the `infrastructure` Sentinel overlay (`docker-compose.sentinel.yml`) and a writable Redis conf there (Sentinel `CONFIG REWRITE` must be able to persist topology — the conf is seed-mounted to a writable `/data/redis.conf`, not `:ro`).

## ARM32 Native Library Reverse Engineering

The ZX/GY_GA/TL body-worn camera terminals use `libnative-lib.so` (ARM32 ELF, ~16MB) containing the SIP stack and JNI bridge. When debugging C++ routing issues (e.g., why a SIP MESSAGE isn't reaching the correct JNI callback), use this workflow:

### Prerequisites

- **Python capstone** (`pip install capstone`) for ARM32 disassembly
- **JADX** (at `/d/jadx-gui-1.5.5-with-jre-win/`) for DEX decompilation — use headless mode for single-class extraction

### Step 1: Verify .so identity across APKs

Different APKs may share the same native library. Always verify with MD5 before assuming code differs:

```python
import hashlib, zipfile
with zipfile.ZipFile('apk_path', 'r') as z:
    data = z.read('lib/armeabi-v7a/libnative-lib.so')
    print(hashlib.md5(data).hexdigest(), len(data))
```

### Step 2: Build PLT symbol resolution table

ARM32 PLT stubs are 12 bytes each starting at `.plt + 20`. Map PLT addresses to symbol names via `.rel.plt`:

```python
# Parse ELF headers to find .rel.plt, .dynsym, .dynstr, .plt sections
# Each .rel.plt entry (8 bytes): r_offset(4) + r_info(4)
# r_info >> 8 = symbol index into .dynsym
# .dynsym entry (16 bytes): st_name(4) offset into .dynstr
# PLT stub addr = .plt_base + 20 + (rel_entry_index * 12)
```

Save as JSON: `{hex(plt_addr): symbol_name}` for lookup during disassembly.

### Step 3: Resolve PC-relative string constants

ARM32 `LDR Rx, [PC, #imm]` loads a 32-bit value from a literal pool. In ARM mode, PC = instruction_address + 8. To resolve string references:

```python
import struct
def resolve_pc_str(data, ldr_addr, offset):
    pc = ldr_addr + 8  # ARM mode
    literal_val = struct.unpack_from('<I', data, pc + offset)[0]  # but check if it's LDR+ADD pattern
    # Common pattern: LDR r0, [pc, #X] then ADD r0, pc, r0
    # Final address = add_insn_addr + 8 + loaded_value
    return read_cstring(data, final_addr)
```

### Step 4: Disassemble with capstone (ARM mode, not Thumb)

```python
from capstone import *
md = Cs(CS_ARCH_ARM, CS_MODE_ARM)
md.detail = True
for insn in md.disasm(code_bytes, func_addr):
    if insn.mnemonic == 'bl':
        target = int(insn.op_str.lstrip('#'), 16)
        if target in plt_map:
            insn.op_str += f' <{plt_map[target]}>'
```

**Important**: This .so uses ARM mode (not Thumb). Confirmed by 4-byte-aligned instructions starting with PUSH `{fp, lr}` (opcode `0xe92d4830`).

### Step 5: DEX bytecode analysis for Java layer

When JADX fails to decompile a method (e.g., "Method dump skipped, 326 instruction units"), parse the DEX directly:

```python
# Parse DEX class_data_item to enumerate all methods
# Each method's code_item at code_off contains:
#   - registers_size, ins_size, outs_size, tries_size, insns_size
#   - Exception table (try-catch blocks) with type-indexed handlers
#   - Instruction bytecode (insns_size * 2 bytes)
# Key opcodes: invoke-virtual(0x6e), invoke-static(0x71), const-string(0x1a), goto/16(0x29)
# IMPORTANT: mask opcode with & 0xFF — the high byte contains register args, not the opcode
```

### Known C++ routing in libnative-lib.so

```
gb28181_msg_rx (0x0014ff38):
  XML root element → handler dispatch
  "Control"  → gb28181_control_rx
  "Query"    → gb28181_query_rx
  "Notify"   → gb28181_notify_rx
  "Response" → break

gb28181_control_rx (0x0014fafc, 736 bytes):
  strcasecmp(cmdt, "DeviceControl") → gb28181_device_control_req → updateState_controlDev
  strcasecmp(cmdt, "DeviceConfig")  → gb28181_device_config_req
  else branch (all other CmdTypes):
    1. Check ctx_type == 5 (SIP_CTX_XML) — if not, return
    2. sip_find_sdp_headline to extract XML body
    3. strstr(xml_body, "<?xml") — must find XML declaration
    4. updateState_controlServerCfg(1, xml_ptr) → fromJNIControlSerCfgBackFun → onReceiveFTPServiceBack
```

### Key JNI callbacks in Java layer

| C++ function | JNI callback | Java handler | Purpose |
|---|---|---|---|
| `updateState_controlDev` | `fromJNIControlDevBackFun` | `onReceiveControlDeviceBack` | Device control (PTZ, time sync, etc.) |
| `updateState_controlServerCfg` | `fromJNIControlSerCfgBackFun` | `onReceiveFTPServiceBack` | FTP/picture server config |

### Terminal APK file locations

```
d:/jxt/1.9.1_793_202603261758_ZX.apk      # ZX terminal (FTP NOT working)
d:/jxt/1.9.1421_827_202605211142_GY_GA.apk  # GY_GA terminal (FTP working)
d:/jxt/1.8.37_712_202606021138_TL.apk       # TL terminal (FTP working, different .so)
```

Source code (ZX decompiled): `d:/jxt/zfy/zx/app/src/main/java/com/dcw/biz/platform/gb28181/Gb28181Local.java`
