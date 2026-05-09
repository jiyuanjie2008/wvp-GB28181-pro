# ZLM & WVP HTTP Interface Specification

> WVP-GB28181-Pro v2.7.4
> Date: 2026-04-28

## Overview

ZLMediaKit (ZLM) and WVP communicate via HTTP in two directions:

| Direction | Base URL | Protocol | Auth |
|-----------|----------|----------|------|
| **ZLM → WVP** (Hook Callbacks) | `http://{wvp-ip}:{wvp-port}/index/hook` | POST + JSON body | Configured in ZLM `config.ini` `hook.on_*` |
| **WVP → ZLM** (REST API) | `http://{zlm-ip}:{zlm-http-port}/index/api/{api}` | POST + form-urlencoded | `secret` parameter |

---

## Part 1: ZLM → WVP Hook Callbacks

ZLM actively calls WVP's HTTP endpoints when events occur. All hook URLs are configured in ZLM's `config.ini`.

**Base path**: `/index/hook`
**HTTP method**: POST
**Content-Type**: `application/json;charset=UTF-8`

### 1.1 on_server_keepalive - Server Heartbeat

ZLM periodically reports liveness to WVP.

- **URL**: `POST /index/hook/on_server_keepalive`
- **Trigger**: Periodic (interval = `hook.alive_interval` seconds in ZLM config)
- **Purpose**: WVP uses this to determine if the media server is still online

**Request Body** (`OnServerKeepaliveHookParam`):

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| mediaServerId | String | Yes | ZLM instance unique ID (`general.mediaServerId`) |
| data | Object | No | Additional keepalive data (currently empty) |

**Response** (`HookResult`):

```json
{
  "code": 0,
  "msg": "success"
}
```

---

### 1.2 on_server_started - Server Startup Notification

ZLM notifies WVP when it starts up, sending its full configuration.

- **URL**: `POST /index/hook/on_server_started`
- **Trigger**: ZLM process startup
- **Purpose**: WVP registers the ZLM node and reads its port/config info

**Request Body** (`JSONObject`, parsed into `ZLMServerConfig`):

| Field | Type | Description |
|-------|------|-------------|
| general.mediaServerId | String | ZLM instance unique ID |
| api.secret | String | API access secret |
| api.apiDebug | String | Debug mode |
| api.snapRoot | String | Snapshot root path |
| api.defaultSnap | String | Default snapshot image |
| ffmpeg.bin | String | FFmpeg binary path |
| ffmpeg.cmd | String | FFmpeg command template |
| ffmpeg.snap | String | FFmpeg snapshot command |
| ffmpeg.log | String | FFmpeg log path |
| ffmpeg.restart_sec | String | FFmpeg restart interval |
| protocol.modify_stamp | String | Timestamp modification mode |
| protocol.enable_audio | String | Enable audio |
| protocol.add_mute_audio | String | Add mute audio track |
| protocol.continue_push_ms | String | Continue push duration |
| protocol.enable_hls | String | Enable HLS |
| protocol.enable_mp4 | String | Enable MP4 recording |
| protocol.enable_rtsp | String | Enable RTSP |
| protocol.enable_rtmp | String | Enable RTMP |
| protocol.enable_ts | String | Enable TS |
| protocol.enable_fmp4 | String | Enable FMP4 |
| protocol.mp4_as_player | String | MP4 as player |
| protocol.mp4_max_second | String | MP4 max duration per file |
| protocol.mp4_save_path | String | MP4 save path |
| protocol.hls_save_path | String | HLS save path |
| protocol.hls_demand | String | HLS on-demand |
| protocol.rtsp_demand | String | RTSP on-demand |
| protocol.rtmp_demand | String | RTMP on-demand |
| protocol.ts_demand | String | TS on-demand |
| protocol.fmp4_demand | String | FMP4 on-demand |
| general.enableVhost | String | Enable virtual host |
| general.flowThreshold | String | Flow threshold |
| general.maxStreamWaitMS | String | Max stream wait (ms) |
| general.streamNoneReaderDelayMS | int | Auto-close delay with no readers (ms) |
| general.resetWhenRePlay | String | Reset on replay |
| general.mergeWriteMS | String | Merge write interval |
| general.mediaServerId | String | Server ID |
| general.wait_track_ready_ms | String | Track ready wait timeout |
| general.wait_add_track_ms | String | Add track wait timeout |
| general.unready_frame_cache | String | Unready frame cache size |
| ip | String | Server IP (auto-filled by WVP from remote address) |
| hls.fileBufSize | String | HLS file buffer size |
| hls.filePath | String | HLS file path |
| hls.segDur | String | HLS segment duration |
| hls.segNum | String | HLS segment count |
| hls.segRetain | String | HLS segments to retain |
| hls.broadcastRecordTs | String | Broadcast recorded TS |
| hls.deleteDelaySec | String | HLS delete delay (seconds) |
| hls.segKeep | String | HLS segment keep |
| hook.alive_interval | Float | Keepalive interval (seconds) |
| hook.enable | String | Hook enabled |
| hook.on_flow_report | String | Flow report hook URL |
| hook.on_http_access | String | HTTP access hook URL |
| hook.on_play | String | Play auth hook URL |
| hook.on_publish | String | Publish auth hook URL |
| hook.on_record_mp4 | String | MP4 record hook URL |
| hook.on_rtsp_auth | String | RTSP auth hook URL |
| hook.on_rtsp_realm | String | RTSP realm |
| hook.on_shell_login | String | Shell login hook URL |
| hook.on_stream_changed | String | Stream changed hook URL |
| hook.on_stream_none_reader | String | No reader hook URL |
| hook.on_stream_not_found | String | Stream not found hook URL |
| hook.on_server_started | String | Server started hook URL |
| hook.on_server_keepalive | String | Keepalive hook URL |
| hook.on_send_rtp_stopped | String | Send RTP stopped hook URL |
| hook.on_rtp_server_timeout | String | RTP server timeout hook URL |
| hook.timeoutSec | String | Hook timeout (seconds) |
| http.port | int | HTTP port |
| http.sslport | int | HTTPS port |
| rtmp.port | int | RTMP port |
| rtmp.sslport | int | RTMPS port |
| rtsp.port | int | RTSP port |
| rtsp.sslport | int | RTSPS port |
| rtp_proxy.port | int | RTP proxy port |
| rtp_proxy.port_range | String | RTP port range |
| rtp_proxy.timeoutSec | String | RTP proxy timeout |
| record.appName | String | Record app name |
| record.filePath | String | Record file path |
| record.fileSecond | String | Record file duration |
| transcode.suffix | String | Transcode stream suffix |

**Response** (`HookResult`):

```json
{
  "code": 0,
  "msg": "success"
}
```

---

### 1.3 on_stream_changed - Stream Registration/Deregistration

ZLM notifies WVP when a stream is registered (publish starts) or deregistered (publish stops).

- **URL**: `POST /index/hook/on_stream_changed`
- **Trigger**: Stream publish starts or stops
- **Purpose**: WVP tracks active streams and triggers play/record workflows

**Request Body** (`OnStreamChangedHookParam`):

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| mediaServerId | String | Yes | ZLM instance ID |
| regist | boolean | Yes | `true` = stream registered, `false` = stream deregistered |
| app | String | Yes | Application name (e.g., `rtp`) |
| stream | String | Yes | Stream ID (e.g., `34020000001320000001_34020000001320000002`) |
| schema | String | Yes | Protocol schema (e.g., `rtsp`) |
| vhost | String | No | Virtual host |
| callId | String | No | Push session call ID |
| totalReaderCount | int | No | Total reader/viewer count |
| originType | int | No | Origin type: 0=unknown, 1=rtmp_push, 2=rtsp_push, 3=rtp_push, 4=pull, 5=ffmpeg_pull, 6=mp4_vod, 7=device_chn |
| originTypeStr | String | No | Origin type string description |
| originUrl | String | No | Origin source URL |
| severId | String | No | Server ID (note: typo preserved from source) |
| createStamp | Long | No | Creation timestamp (unix seconds) |
| aliveSecond | Long | No | Stream alive duration (seconds) |
| bytesSpeed | Long | No | Data speed (bytes/s) |
| params | String | No | Extra query parameters string |
| docker | boolean | No | Whether running in Docker |
| originSock | Object | No | Origin socket info (see below) |
| tracks | Array | No | Media track info (see below) |

**OriginSock** sub-object:

| Field | Type | Description |
|-------|------|-------------|
| identifier | String | Connection identifier |
| local_ip | String | Local IP |
| local_port | int | Local port |
| peer_ip | String | Peer IP |
| peer_port | int | Peer port |

**MediaTrack** sub-object (array item):

| Field | Type | Description |
|-------|------|-------------|
| channels | int | Audio channel count (1=mono, 2=stereo) |
| codec_id | int | Codec: 0=H264, 1=H265, 2=AAC, 3=G711A, 4=G711U |
| codec_id_name | String | Codec name (e.g., `CodecH264`, `CodecAAC`) |
| codec_type | int | 0=Video, 1=Audio |
| ready | boolean | Track is ready for playback |
| sample_bit | int | Audio sample bits |
| sample_rate | int | Audio sample rate |
| fps | float | Video frame rate |
| height | int | Video height (pixels) |
| width | int | Video width (pixels) |
| frames | int | Total frame count |
| key_frames | int | Key frame count |
| gop_size | int | GOP size |
| gop_interval_ms | int | GOP interval (ms) |
| loss | float | Frame loss rate |

**Response** (`HookResult`):

```json
{
  "code": 0,
  "msg": "success"
}
```

---

### 1.4 on_play - Play Authentication

ZLM asks WVP to authorize a play request before serving the stream.

- **URL**: `POST /index/hook/on_play`
- **Trigger**: Client requests to play a stream (HTTP-FLV, HLS, RTSP, etc.)
- **Purpose**: WVP validates play access (token/callId check)

**Request Body** (`OnPlayHookParam`):

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| mediaServerId | String | Yes | ZLM instance ID |
| id | String | No | Session ID |
| app | String | Yes | Application name |
| stream | String | Yes | Stream ID |
| ip | String | No | Client IP address |
| port | int | No | Client port |
| params | String | No | Query parameters (contains `callId` for auth) |
| schema | String | No | Protocol schema |
| vhost | String | No | Virtual host |

**Response** - Success (`HookResult`):

```json
{
  "code": 0,
  "msg": "success"
}
```

**Response** - Unauthorized:

```json
{
  "code": 401,
  "msg": "Unauthorized"
}
```

---

### 1.5 on_publish - Publish Authentication

ZLM asks WVP to authorize a push/publish request.

- **URL**: `POST /index/hook/on_publish`
- **Trigger**: A source attempts to publish a stream to ZLM
- **Purpose**: WVP validates push access and returns publishing parameters

**Request Body** (`OnPublishHookParam`):

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| mediaServerId | String | Yes | ZLM instance ID |
| id | String | No | Session ID |
| app | String | Yes | Application name |
| stream | String | Yes | Stream ID |
| ip | String | No | Source IP address |
| port | int | No | Source port |
| params | String | No | Query parameters |
| schema | String | No | Protocol schema |
| vhost | String | No | Virtual host |

**Response** - Success (`HookResultForOnPublish`):

```json
{
  "code": 0,
  "msg": "success",
  "enable_audio": true,
  "enable_mp4": false,
  "mp4_max_second": 3600,
  "mp4_save_path": "/opt/media/record/",
  "stream_replace": null,
  "modify_stamp": 0
}
```

| Field | Type | Description |
|-------|------|-------------|
| code | int | 0 = allow, non-0 = reject |
| msg | String | Status message |
| enable_audio | Boolean | Whether to enable audio pass-through |
| enable_mp4 | Boolean | Whether to enable MP4 recording |
| mp4_max_second | Integer | Max MP4 file duration (seconds) |
| mp4_save_path | String | MP4 file save directory |
| stream_replace | String | Replace stream ID (null = no replacement) |
| modify_stamp | Integer | Timestamp modification mode |

**Response** - Failure:

```json
{
  "code": -1,
  "msg": "fail"
}
```

---

### 1.6 on_stream_none_reader - No Viewer Auto-Close

ZLM asks WVP whether to close a stream that has no viewers.

- **URL**: `POST /index/hook/on_stream_none_reader`
- **Trigger**: Stream has zero viewers for a configured duration
- **Purpose**: WVP decides whether to keep or close the stream

**Request Body** (`OnStreamNoneReaderHookParam`):

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| mediaServerId | String | Yes | ZLM instance ID |
| schema | String | No | Protocol schema |
| app | String | Yes | Application name |
| stream | String | Yes | Stream ID |
| vhost | String | No | Virtual host |

**Response** (`JSONObject`):

```json
{
  "code": 0,
  "close": true
}
```

| Field | Type | Description |
|-------|------|-------------|
| code | int | Always 0 |
| close | boolean | `true` = close the stream, `false` = keep it alive |

---

### 1.7 on_stream_not_found - Stream Not Found (Auto-Play)

ZLM notifies WVP that a requested stream does not exist.

- **URL**: `POST /index/hook/on_stream_not_found`
- **Trigger**: Client requests to play a stream that doesn't exist in ZLM
- **Purpose**: WVP may auto-initiate stream pull (if `autoApplyPlay` is enabled)

**Request Body** (`OnStreamNotFoundHookParam`):

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| mediaServerId | String | Yes | ZLM instance ID |
| id | String | No | Session ID |
| app | String | Yes | Application name |
| stream | String | Yes | Stream ID |
| ip | String | No | Client IP address |
| port | int | No | Client port |
| params | String | No | Query parameters |
| schema | String | No | Protocol schema |
| vhost | String | No | Virtual host |

**Response** (`HookResult`):

```json
{
  "code": 0,
  "msg": "success"
}
```

---

### 1.8 on_record_mp4 - MP4 Recording Complete

ZLM notifies WVP that an MP4 recording file has been completed.

- **URL**: `POST /index/hook/on_record_mp4`
- **Trigger**: MP4 recording file finished writing
- **Purpose**: WVP processes the recording (stores metadata, triggers events)

**Request Body** (`OnRecordMp4HookParam`):

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| mediaServerId | String | Yes | ZLM instance ID |
| app | String | Yes | Application name |
| stream | String | Yes | Stream ID |
| vhost | String | No | Virtual host |
| file_name | String | Yes | Recorded file name |
| file_path | String | Yes | Full file path on disk |
| file_size | long | Yes | File size in bytes |
| folder | String | No | Containing folder path |
| url | String | No | File URL |
| start_time | long | Yes | Recording start time (unix timestamp, seconds) |
| time_len | double | Yes | Recording duration (seconds) |
| params | String | No | Extra parameters |

**Response** (`HookResult`):

```json
{
  "code": 0,
  "msg": "success"
}
```

---

### 1.9 on_send_rtp_stopped - RTP Sending Stopped

ZLM notifies WVP that an outbound RTP stream has stopped.

- **URL**: `POST /index/hook/on_send_rtp_stopped`
- **Trigger**: Outbound RTP sending session ended
- **Purpose**: WVP cleans up the send RTP session state (used for cascade/platform pushing)

**Request Body** (`OnSendRtpStoppedHookParam`):

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| mediaServerId | String | Yes | ZLM instance ID |
| app | String | Yes | Application name |
| stream | String | Yes | Stream ID |

**Response** (`HookResult`):

```json
{
  "code": 0,
  "msg": "success"
}
```

---

### 1.10 on_rtp_server_timeout - RTP Server Timeout

ZLM notifies WVP that an RTP receive server has timed out (no incoming data).

- **URL**: `POST /index/hook/on_rtp_server_timeout`
- **Trigger**: RTP server port received no data within the timeout period
- **Purpose**: WVP cleans up the timed-out RTP session (camera didn't push media)

**Request Body** (`OnRtpServerTimeoutHookParam`):

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| mediaServerId | String | Yes | ZLM instance ID |
| stream_id | String | Yes | RTP stream identifier |
| local_port | int | No | Local RTP port |
| ssrc | String | No | Allocated SSRC |
| tcpMode | int | No | TCP mode flag |
| re_use_port | boolean | No | Port reuse flag |

**Response** (`HookResult`):

```json
{
  "code": 0,
  "msg": "success"
}
```

---

## Part 2: WVP → ZLM REST API

WVP calls ZLM's REST API to control media server operations. All requests use form-urlencoded POST bodies.

**Base URL**: `http://{zlm-ip}:{zlm-http-port}/index/api/{api}`
**HTTP method**: POST
**Content-Type**: `application/x-www-form-urlencoded`
**Auth**: Every request includes `secret` as a form field (matches `api.secret` in ZLM config)
**HTTP Client**: OkHttp with 8s connect timeout, configurable read timeout (default 10s)

### 2.1 getServerConfig - Get ZLM Configuration

- **API**: `POST /index/api/getServerConfig`
- **Purpose**: Read all ZLM runtime configuration key-value pairs

**Request Parameters**: (none beyond `secret`)

**Response** (`ZLMResult<List<JSONObject>>`):

```json
{
  "code": 0,
  "data": [
    {
      "general.mediaServerId": "zlm-1",
      "http.port": "80",
      "rtsp.port": "554",
      "..."
    }
  ]
}
```

---

### 2.2 setServerConfig - Set ZLM Configuration

- **API**: `POST /index/api/setServerConfig`
- **Purpose**: Modify ZLM runtime configuration

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| (any config key) | String | No | Configuration key-value pairs to set |

---

### 2.3 isMediaOnline - Check Stream Online Status

- **API**: `POST /index/api/isMediaOnline`
- **Purpose**: Check if a stream is currently active in ZLM

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| app | String | No | Application name filter |
| stream | String | No | Stream ID filter |
| schema | String | No | Protocol filter |
| vhost | String | No | Virtual host (`__defaultVhost__`) |

**Response** (`ZLMResult<?>`):

```json
{
  "code": 0,
  "online": true
}
```

---

### 2.4 getMediaList - Get Active Stream List

- **API**: `POST /index/api/getMediaList`
- **Purpose**: Query all active streams, with optional filters

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| app | String | No | Application name filter |
| stream | String | No | Stream ID filter |
| schema | String | No | Protocol filter |
| vhost | String | No | Virtual host (`__defaultVhost__`) |

**Response** (`ZLMResult<JSONArray>`):

```json
{
  "code": 0,
  "data": [
    {
      "app": "rtp",
      "stream": "34020000001320000001_34020000001320000002",
      "schema": "rtsp",
      "..."
    }
  ]
}
```

---

### 2.5 getMediaInfo - Get Stream Detail Info

- **API**: `POST /index/api/getMediaInfo`
- **Purpose**: Get detailed information about a specific stream

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| app | String | Yes | Application name |
| schema | String | Yes | Protocol schema |
| stream | String | Yes | Stream ID |
| vhost | String | No | Virtual host (`__defaultVhost__`) |

**Response** (`ZLMResult<JSONObject>`):

```json
{
  "code": 0,
  "data": {
    "app": "rtp",
    "stream": "34020000001320000001_34020000001320000002",
    "tracks": [],
    "..."
  }
}
```

---

### 2.6 getRtpInfo - Get RTP Receive Session Info

- **API**: `POST /index/api/getRtpInfo`
- **Purpose**: Query RTP receive session status for a stream

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| stream_id | String | Yes | RTP stream identifier |

**Response** (`ZLMResult<?>`):

```json
{
  "code": 0,
  "exist": true,
  "peer_ip": "192.168.1.100",
  "peer_port": 5000,
  "local_ip": "172.18.0.3",
  "local_port": 10003
}
```

---

### 2.7 openRtpServer - Open RTP Receive Port

- **API**: `POST /index/api/openRtpServer`
- **Purpose**: Open a port to receive RTP media streams from cameras (GB28181 INVITE)

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| port | int | No | Specify port (0 = auto-assign) |
| stream_id | String | Yes | RTP stream identifier |
| ssrc | long | No | SSRC for the RTP session |
| tcp_mode | int | No | TCP mode (0=UDP, 1=TCP active, 2=TCP passive) |
| re_use_port | boolean | No | Allow port reuse |
| only_auto | boolean | No | Only auto-assign port |

**Response** (`ZLMResult<?>`):

```json
{
  "code": 0,
  "port": 10003
}
```

---

### 2.8 closeRtpServer - Close RTP Receive Port

- **API**: `POST /index/api/closeRtpServer`
- **Purpose**: Close an RTP receive server and release the port

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| stream_id | String | Yes | RTP stream identifier to close |

---

### 2.9 listRtpServer - List All RTP Servers

- **API**: `POST /index/api/listRtpServer`
- **Purpose**: List all currently open RTP receive servers

**Request Parameters**: (none beyond `secret`)

**Response** (`ZLMResult<List<RtpServerResult>>`):

```json
{
  "code": 0,
  "data": [
    {
      "port": 10003,
      "stream_id": "34020000001320000001_34020000001320000002"
    }
  ]
}
```

---

### 2.10 updateRtpServerSSRC - Update RTP Session SSRC

- **API**: `POST /index/api/updateRtpServerSSRC`
- **Purpose**: Update the expected SSRC for an existing RTP server

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| app | String | Yes | Application name |
| stream_id | String | Yes | RTP stream identifier |
| ssrc | String | Yes | New SSRC value |

---

### 2.11 connectRtpServer - Actively Connect to Remote RTP

- **API**: `POST /index/api/connectRtpServer`
- **Purpose**: Initiate an active TCP connection to a remote RTP sender

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| dst_url | String | Yes | Destination IP/hostname |
| dst_port | int | Yes | Destination port |
| app | String | Yes | Application name |
| stream_id | String | Yes | Stream identifier |

---

### 2.12 startSendRtp - Start Outbound RTP (Active)

- **API**: `POST /index/api/startSendRtp`
- **Purpose**: Actively push RTP stream to a remote destination (used for cascade/platform forwarding)

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| vhost | String | No | Virtual host (`__defaultVhost__`) |
| app | String | Yes | Source application name |
| stream | String | Yes | Source stream ID |
| ssrc | String | Yes | Target SSRC |
| dst_url | String | Yes | Destination IP |
| dst_port | int | Yes | Destination port |
| is_udp | String | No | `1` = UDP, `0` = TCP |
| src_port | int | No | Source port |
| pt | int | No | Payload type |
| use_ps | String | No | `1` = PS encapsulation, `0` = raw |
| only_audio | String | No | `1` = audio only |
| enable_origin_recv_limit | String | No | `1` = enable origin receive limit |
| udp_rtcp_timeout | String | No | RTCP keepalive (`0` = disabled, `500` = enabled) |

---

### 2.13 startSendRtpPassive - Start Outbound RTP (Passive/TCP)

- **API**: `POST /index/api/startSendRtpPassive`
- **Purpose**: Open a local port and wait for remote side to connect for RTP delivery (TCP passive mode)

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| vhost | String | No | Virtual host (`__defaultVhost__`) |
| app | String | Yes | Source application name |
| stream | String | Yes | Source stream ID |
| ssrc | String | Yes | Target SSRC |
| src_port | int | No | Source port |
| pt | int | No | Payload type |
| use_ps | String | No | `1` = PS, `0` = raw |
| only_audio | String | No | Audio only mode |
| is_udp | String | No | `0` for TCP passive |
| recv_stream_id | String | No | Receive stream ID |
| enable_origin_recv_limit | String | No | Enable origin receive limit |
| close_delay_ms | int | No | Close delay timeout (ms) |
| dst_url | String | No | Destination (if not passive) |
| dst_port | int | No | Destination port |
| udp_rtcp_timeout | String | No | RTCP timeout for UDP |

**Response** (`ZLMResult<?>`):

```json
{
  "code": 0,
  "local_port": 10050
}
```

---

### 2.14 startSendRtpTalk - Start RTP for Talkback

- **API**: `POST /index/api/startSendRtpTalk`
- **Purpose**: Start RTP send/receive for two-way audio talkback

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| vhost | String | No | Virtual host (`__defaultVhost__`) |
| app | String | Yes | Application name |
| stream | String | Yes | Stream ID |
| ssrc | String | Yes | SSRC |
| pt | int | No | Payload type |
| type | String | No | Media type |
| only_audio | String | No | Audio only |
| recv_stream_id | String | No | Receive stream ID |
| enable_origin_recv_limit | String | No | `1` = enable limit |

---

### 2.15 stopSendRtp - Stop Outbound RTP

- **API**: `POST /index/api/stopSendRtp`
- **Purpose**: Stop an active outbound RTP stream

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| vhost | String | No | Virtual host (`__defaultVhost__`) |
| app | String | Yes | Application name |
| stream | String | Yes | Stream ID |
| ssrc | String | No | SSRC to stop (optional filter) |

---

### 2.16 close_streams - Close Stream

- **API**: `POST /index/api/close_streams`
- **Purpose**: Close an active stream and disconnect all clients

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| vhost | String | No | Virtual host (`__defaultVhost__`) |
| app | String | Yes | Application name |
| stream | String | Yes | Stream ID |
| force | int | No | Force close flag (`1` = force) |

---

### 2.17 addStreamProxy - Add Stream Pull Proxy

- **API**: `POST /index/api/addStreamProxy`
- **Purpose**: Add a stream pull proxy (ZLM actively pulls from RTSP/RTMP source URL)

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| vhost | String | No | Virtual host (`__defaultVhost__`) |
| app | String | Yes | Target application name |
| stream | String | Yes | Target stream ID |
| url | String | Yes | Source URL (rtsp://..., rtmp://...) |
| enable_mp4 | int | No | `1` = enable MP4 recording |
| enable_audio | int | No | `1` = enable audio |
| rtp_type | String | No | RTP transport type |
| timeout_sec | int | No | Pull timeout (seconds) |
| retry_count | int | No | Retry count (default 3) |

**Response** (`ZLMResult<StreamProxyResult>`):

```json
{
  "code": 0,
  "data": {
    "key": "proxy-key-xxx"
  }
}
```

---

### 2.18 delStreamProxy - Remove Stream Pull Proxy

- **API**: `POST /index/api/delStreamProxy`
- **Purpose**: Remove a previously added stream pull proxy

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| key | String | Yes | Proxy key (returned by `addStreamProxy`) |

---

### 2.19 addFFmpegSource - Add FFmpeg Pull Proxy

- **API**: `POST /index/api/addFFmpegSource`
- **Purpose**: Add an FFmpeg-based stream pull proxy (supports transcoding)

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| src_url | String | Yes | Source URL (URL-encoded) |
| dst_url | String | Yes | Destination URL (push to ZLM) |
| timeout_ms | int | No | Timeout (ms) |
| enable_mp4 | boolean | No | Enable MP4 recording |
| ffmpeg_cmd_key | String | No | FFmpeg command template key from ZLM config |

**Response** (`ZLMResult<StreamProxyResult>`):

```json
{
  "code": 0,
  "data": {
    "key": "ffmpeg-key-xxx"
  }
}
```

---

### 2.20 delFFmpegSource - Remove FFmpeg Pull Proxy

- **API**: `POST /index/api/delFFmpegSource`
- **Purpose**: Remove a previously added FFmpeg pull proxy

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| key | String | Yes | FFmpeg proxy key |

---

### 2.21 getSnap - Capture Snapshot

- **API**: `GET /index/api/getSnap`
- **Purpose**: Capture a snapshot from a stream
- **Method**: GET (unlike other APIs)

**Request Parameters** (query string):

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| url | String | Yes | Stream URL to capture from |
| timeout_sec | int | Yes | Capture timeout (seconds) |
| expire_sec | int | Yes | Cache expiration (seconds) |
| async | int | No | `1` = async mode |

**Response**: Binary image data (JPEG)

---

### 2.22 getAllSession - Get All Active Sessions

- **API**: `POST /index/api/getAllSession`
- **Purpose**: List all active TCP/UDP sessions in ZLM

**Request Parameters**: (none beyond `secret`)

**Response** (`ZLMResult<List<SessionData>>`):

```json
{
  "code": 0,
  "data": [
    {
      "id": "session-1",
      "local_ip": "172.18.0.3",
      "local_port": 80,
      "peer_ip": "192.168.1.100",
      "peer_port": 54321,
      "typeid": "HttpSession"
    }
  ]
}
```

---

### 2.23 kick_sessions - Disconnect Sessions

- **API**: `POST /index/api/kick_sessions`
- **Purpose**: Disconnect sessions by local port

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| local_port | String | Yes | Local port to match for disconnection |

---

### 2.24 restartServer - Restart ZLM

- **API**: `POST /index/api/restartServer`
- **Purpose**: Restart the ZLM process

**Request Parameters**: (none beyond `secret`)

---

### 2.25 pauseRtpCheck - Pause RTP Validation

- **API**: `POST /index/api/pauseRtpCheck`
- **Purpose**: Temporarily stop SSRC/port validation on an RTP server (used during media re-negotiation)

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| stream_id | String | Yes | RTP stream identifier |

---

### 2.26 resumeRtpCheck - Resume RTP Validation

- **API**: `POST /index/api/resumeRtpCheck`
- **Purpose**: Resume SSRC/port validation on an RTP server

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| stream_id | String | Yes | RTP stream identifier |

---

### 2.27 loadMP4File - Load MP4 for VOD Playback

- **API**: `POST /index/api/loadMP4File`
- **Purpose**: Load an MP4 file into ZLM for on-demand playback

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| vhost | String | No | Virtual host (`__defaultVhost__`) |
| app | String | Yes | Application name |
| stream | String | Yes | Stream ID for the VOD stream |
| file_path | String | Yes | MP4 file path on disk |
| file_repeat | String | No | `0` = no loop, `1` = loop |

---

### 2.28 deleteRecordDirectory - Delete Recording Files

- **API**: `POST /index/api/deleteRecordDirectory`
- **Purpose**: Delete recording files from disk

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| vhost | String | No | Virtual host (`__defaultVhost__`) |
| app | String | Yes | Application name |
| stream | String | Yes | Stream ID |
| period | String | Yes | Date period (e.g., `2026-04-28`) |
| name | String | Yes | File name to delete |

---

### 2.29 setRecordSpeed - Set Playback Speed

- **API**: `POST /index/api/setRecordSpeed`
- **Purpose**: Change the playback speed of a recording/VOD stream

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| vhost | String | No | Virtual host (`__defaultVhost__`) |
| app | String | Yes | Application name |
| stream | String | Yes | Stream ID |
| speed | int | Yes | Playback speed multiplier (1 = normal) |
| schema | String | No | Protocol schema |

---

### 2.30 seekRecordStamp - Seek Playback Position

- **API**: `POST /index/api/seekRecordStamp`
- **Purpose**: Seek to a specific timestamp in a recording/VOD stream

**Request Parameters**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| secret | String | Yes | API secret |
| vhost | String | No | Virtual host (`__defaultVhost__`) |
| app | String | Yes | Application name |
| stream | String | Yes | Stream ID |
| stamp | BigDecimal | Yes | Target timestamp (seconds, decimal) |
| schema | String | No | Protocol schema |

---

### 2.31 downloadFile - Download Recording File

- **API**: `GET /index/api/downloadFile`
- **Purpose**: Download a recording file directly from ZLM
- **Method**: GET (direct file download)

**Request Parameters** (query string):

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| file_path | String | Yes | Full file path of the recording |

**Response**: Binary file data (MP4)

---

## Part 3: Typical Interaction Sequences

### 3.1 Live Play (GB28181 Camera → WVP → Browser)

```
Camera          WVP              ZLM              Browser
  |               |                |                |
  |--- REGISTER -->|                |                |
  |<-- 200 OK ----|                |                |
  |               |                |                |
  |--- Keepalive ->|                |                |
  |<-- 200 OK ----|                |                |
  |               |                |                |
  |               |<----------- browser: GET /api/play/start ---|
  |               |                |                |
  |               |-- openRtpServer ------------->|
  |               |<- port:10003 --|                |
  |               |                |                |
  |--- SIP INVITE (SDP: ZLM IP:port+SSRC) -------->|
  |<-- 200 OK ----|                |                |
  |               |                |                |
  |--- RTP media ----------------->|                |
  |               |                |                |
  |               |<- on_stream_changed(regist=true) --|
  |               |-- 200 OK ---->|                |
  |               |                |                |
  |               |------- stream URLs (ws-flv, rtsp, etc.) --->|
  |               |                |                |
  |               |                |<-- GET /rtp/xxx.live.flv ---|
  |               |<- on_play ----|                |
  |               |-- 200 OK ---->|                |
  |               |                |== video data =>|
```

### 3.2 ZLM Startup and Keepalive

```
ZLM                              WVP
  |                                |
  |-- on_server_started ---------->|
  |  (full config + ports)         |
  |<-- 200 OK --------------------|
  |                                |
  |  (every ~10 seconds)           |
  |-- on_server_keepalive -------->|
  |<-- 200 OK --------------------|
  |                                |
```

### 3.3 Cascade Push (WVP pushes stream to another platform)

```
WVP                ZLM                 Remote Platform
  |                   |                      |
  |-- openRtpServer ->|                      |
  |                   |                      |
  |  (camera streams to ZLM via SIP INVITE) |
  |                   |                      |
  |-- startSendRtpPassive --------------->| |
  |<- local_port:10050 ---|               | |
  |                   |                      |
  |  (SIP INVITE to remote platform with port 10050)
  |                   |                      |
  |                   |<--- TCP connect -----|
  |                   |--- RTP media ------->|
  |                   |                      |
  |<- on_send_rtp_stopped --| (when done)   | |
  |-- 200 OK -------->|                      |
```

---

## Part 4: Common Response Structure

### ZLMResult (WVP → ZLM responses)

All ZLM REST API calls return a JSON structure:

```json
{
  "code": 0,
  "msg": "success",
  "data": "..."
}
```

| Field | Type | Description |
|-------|------|-------------|
| code | int | 0 = success, non-0 = error |
| msg | String | Status/error message |
| data | T | Response payload (varies by API) |

Additional fields present in some responses:

| Field | Type | Description |
|-------|------|-------------|
| online | Boolean | Stream online status |
| exist | Boolean | Session/stream exists |
| peer_ip | String | Remote peer IP |
| peer_port | Integer | Remote peer port |
| local_ip | String | Local IP |
| local_port | Integer | Local port |
| port | Integer | Allocated port |
| hit | Integer | Cache hit flag |
| changed | Integer | Config changed flag |

### HookResult (ZLM → WVP hook responses)

All hook callbacks expect a JSON response:

```json
{
  "code": 0,
  "msg": "success"
}
```

| code | Meaning |
|------|---------|
| 0 | Success / Allow |
| -1 | Failure / Reject |
| 401 | Unauthorized (on_play only) |

`HookResultForOnPublish` adds extra fields for publish control:

| Field | Type | Description |
|-------|------|-------------|
| enable_audio | Boolean | Enable audio pass-through |
| enable_mp4 | Boolean | Enable MP4 recording |
| mp4_max_second | Integer | Max recording duration per file |
| mp4_save_path | String | Recording save directory |
| stream_replace | String | Stream ID replacement |
| modify_stamp | Integer | Timestamp modification mode |
