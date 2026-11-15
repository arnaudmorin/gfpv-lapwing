# PhobosLT BLE protocol

The timer is a BLE peripheral advertising as `PhobosLT_XXXX` (the last two bytes of
its Bluetooth MAC) with the primary service UUID below in the advertisement, so
clients can filter scans on it. Up to 3 clients may be connected at the same time
(e.g. the pilot's phone and a spotter's phone).

All multi-byte integers are **little-endian**. The timer accepts an ATT MTU up to 517;
clients should request a large MTU right after connecting.

Service UUID: `5a3e0001-7c1f-4d8b-9a6e-2f4c1b0d9e10`

| Characteristic | UUID                                   | Properties     | Payload |
|----------------|----------------------------------------|----------------|---------|
| Config         | `5a3e0002-7c1f-4d8b-9a6e-2f4c1b0d9e10` | read, write    | JSON, see below |
| Control        | `5a3e0003-7c1f-4d8b-9a6e-2f4c1b0d9e10` | write          | 1 byte command |
| RSSI           | `5a3e0004-7c1f-4d8b-9a6e-2f4c1b0d9e10` | notify         | `u8` filtered RSSI |
| Lap            | `5a3e0005-7c1f-4d8b-9a6e-2f4c1b0d9e10` | notify         | `u16` lap index, `u32` lap time ms |
| Status         | `5a3e0006-7c1f-4d8b-9a6e-2f4c1b0d9e10` | read, notify   | `u8` battery voltage ×10, `u8` running (0/1) |
| Info           | `5a3e0007-7c1f-4d8b-9a6e-2f4c1b0d9e10` | read           | JSON `{"fw","chip","rev","name"}` |
| OTA control    | `5a3e0008-7c1f-4d8b-9a6e-2f4c1b0d9e10` | write, notify  | see OTA |
| OTA data       | `5a3e0009-7c1f-4d8b-9a6e-2f4c1b0d9e10` | write          | raw firmware chunk |

## Config

```json
{"freq":5658,"minLap":100,"alarm":36,"anType":2,"anRate":10,"enterRssi":120,"exitRssi":100,"name":"Pilot"}
```

| Key         | Unit / meaning |
|-------------|----------------|
| `freq`      | VRx frequency in MHz (5000–6000), `1111` powers the RX5808 down |
| `minLap`    | minimum lap time, tenths of a second |
| `alarm`     | low battery alarm, tenths of a volt (0 = off) |
| `anType`    | announcer: 0 none, 1 beep, 2 lap time, 3 two laps, 4 three laps (used by the app) |
| `anRate`    | announcer speech rate ×10 (used by the app) |
| `enterRssi` | RSSI enter threshold (0–255) |
| `exitRssi`  | RSSI exit threshold (0–255) |
| `name`      | pilot name, max 20 characters |

Writes may contain only a subset of the keys; missing keys are left unchanged. The
configuration is persisted to flash about a second after a write.

## Control

| Byte   | Command |
|--------|---------|
| `0x01` | start the race (the timer beeps; the app plays the start countdown before sending this) |
| `0x02` | stop the race |

A Status notification follows each command.

## Notifications

- **RSSI** is streamed every 100 ms, but only while at least one client is subscribed
  to it. Subscribe on the calibration screen and unsubscribe when leaving it.
- **Lap** is sent for each detected lap. Index 0 is the hole shot (time from race
  start to the first crossing); index N > 0 is lap N. Indices reset on start/stop, so
  a gap in the indices means a notification was missed.
- **Status** is notified every 2 s while subscribed and after every control command.

## OTA firmware update

Send the application image (`.pio/build/<env>/firmware.bin`, not a merged factory
image). Data writes must use *write with response* so the client is paced by the
flash.

1. Write `0x01` + `u32` image size + 16 byte MD5 digest of the image to **OTA control**.
   The timer stops the race if one is running, and replies with a notification `01 00`
   on success or `01 <err>`.
2. Write the image to **OTA data** in chunks of at most `min(MTU - 3, 512)` bytes.
3. Write `0x02` to **OTA control**. The timer checks the size, the MD5 and the image
   header (including the chip type), replies `02 00`, and reboots about one
   second later. On failure it replies `02 <err>` and keeps the running firmware.

`0x03` aborts an update. If a data write fails, the timer notifies `FF <err>`
asynchronously. If the client disconnects mid-update, the update is aborted.

| Error | Meaning |
|-------|---------|
| 1     | could not begin the update (image too large for the OTA partition) |
| 2     | flash write failed |
| 3     | verification failed (MD5 mismatch, wrong chip, corrupt image) |
| 4     | invalid state (no update in progress, or another client is updating) |
| 5     | size mismatch |
