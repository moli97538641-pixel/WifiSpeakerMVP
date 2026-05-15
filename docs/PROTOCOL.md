# WSPK v3 Protocol

v0.3.6 起，音频流协议升级为 `WSPK0003`。目标是支持 Android / Windows 跨平台、一对多推送、低延迟缓冲和后续同步扩展。

## Ports

```text
45777 TCP: audio stream
45778 UDP: discovery
45779 TCP: control command
```

## Stream header

所有整数均为 big-endian。PCM payload 为 little-endian。

```text
magic           8 bytes   ASCII "WSPK0003"
sampleRate      int32
channelCount    int32     1 or 2
bitsPerSample   int32     currently 16
formatCode      int32     1 = PCM_S16LE
frameMs         int32     usually 10
```

## Audio frame

```text
magic             4 bytes   ASCII "AFRM"
payloadSize       int32
sequence          int64
presentationTime  int64     ns relative to stream start
durationFrames    int32
payload           N bytes   PCM_S16LE
```

`presentationTime` 是相对发送端流起点的时间戳，不是跨设备绝对时钟。它用于低延迟缓冲、诊断和后续同步扩展。

## Control command: volume

```text
magic        8 bytes   ASCII "WSPKCTRL"
type         int32     1 = volume
value        int32     0..100
```

## Discovery

Request:

```text
WSPK_DISCOVER_1
```

Response:

```text
WSPK_RESPONSE_1|host|45777|deviceName
```
