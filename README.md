# SmartMetrApp

> A Spring Boot service that “bridges” two PV plants.  
> Reads a smart meter over RS-485, polls Solis Cloud API, and publishes a modified (“fake”) smart-meter image to a grid-tie inverter so it ramps up generation and offsets another building’s grid import.  
> Runs 24/7 on a Raspberry Pi as a `systemd` service. Includes a live status UI, robust serial/API retry logic, and Telegram alerts.

**Live status preview:** https://raspberrypi-bridge.tailaf01c4.ts.net/status.html  
*(Static UI preview only — data source is private.)*

---

## TL;DR

- Two PV plants share one substation. One often **imports** from grid; the other is **under-utilized**.  
- This app **nudges** the grid-tie inverter to produce more when the hybrid plant imports, by publishing an **adjusted meter frame**.  
- Result: **less paid energy** from the utility; **no inverter firmware hacks**; only RS-485 “meter” input.  
- Deployed on **Raspberry Pi**; resilient, observable, alerting to Telegram.

---

## Why this exists (business problem)

- **Plant A (hybrid, bigger)** — sometimes lacks PV and imports from grid.  
- **Plant B (grid-tie, smaller)** — sits on a lightly-loaded building and under-utilizes PV.

**Goal:** when Plant A imports, make Plant B generate more so its export flows through the on-site substation and is consumed by Plant A (or other campus loads).  
**Effect:** lower energy bills without touching the utility meter or any inverter firmware.

---

## How it works (high-level)

```mermaid
flowchart LR
  A[Plant A • Hybrid] <-- Polls --> SC[(Solis Cloud API)]
  subgraph R[SmartMetrApp • Raspberry Pi]
    direction TB
    Reader[ModbusSmReader\n(RS-485 meter poller)]
    Feeder[ModbusInverterFeeder\n(RS-485 inverter publisher)]
    Algo[PowerControlService\nfilter + distribute + slew]
  end
  M[Smart Meter • Plant B]
  I[Grid-tie Inverter • Plant B]

  M -- raw Modbus frame --> R
  SC -- grid import/export --> R
  R -- “edited” meter frame --> I
  R -- pass/zero on faults --> I
```
> GitHub supports Mermaid; the diagram renders in the web UI.

- Read **Acrel** power registers from Plant B’s smart meter.  
- Poll **Solis Cloud** for Plant A; detect current **grid import** (deadband + smoothing).  
- Compute a **compensation target**: if Plant A imports *X kW*, add *X kW* to Plant B’s “site load”.  
- Publish a **modified smart-meter frame** to Plant B’s inverter → it ramps output.  
- Adjust per-phase & total powers; keep V/I sane; enforce safety; show everything on a minimal web UI; alert via Telegram.  
- **No firmware changes**; inverter only “sees” a different RS-485 meter image.

---

## Before / After (impact example)

| Metric (grid-tie plant) | Before (typical) | After (typical) |
| --- | ---: | ---: |
| Average daytime output | ~3 kW | **15–20 kW** |
| Hybrid plant grid import | Higher | **Lower** |
| Customer energy cost | Higher | **Reduced** |

> Actual numbers depend on weather, loads, wiring topology, and site specifics.

---

## Project highlights (for reviewers & recruiters)

- **Java 17 + Spring Boot** monolith — intentionally simple & robust.  
- **Concurrency:** scheduled workers; shared state guarded by `volatile`/atomics; single-writer patterns.  
- **Resilience:** serial reopen/back-off; stale-data guards; bounded retries; graceful degrade to pass-through.  
- **Signal conditioning:** median-3 filter on meter powers; slew-rate limiter on published setpoint; near-zero bias to avoid oscillation.  
- **Device I/O:** stable RS-485 ports (named devices), full Modbus register handling (u16/i32 MSB-first utils).  
- **Operations:** `systemd` service, rolling logs (Logback), health endpoints, Telegram alerts & daily heartbeat.  
- **Observability:** built-in status UI (plain HTML/JS) + `/status` JSON; concise periodic summary logs.

---

## Package layout

```
com.elssolution.smartmetrapp
├─ alerts/…                → alert routing, Telegram sink, heartbeat pings
├─ config/                 → scheduling, Spring wiring
├─ domain/SmSnapshot       → atomic raw meter frames (+ timestamps)
├─ health/SmartmetrHealth  → liveness for management endpoints
├─ integration/
│   ├─ modbus/
│   │   ├─ ModbusSmReader        → RS-485 poller for the real smart meter
│   │   ├─ ModbusInverterFeeder  → RS-485 publisher to the grid-tie inverter
│   │   └─ SerialPortWrapperImpl → stable names, reopen/backoff, timeouts
│   └─ solis/SolisCloudClient    → API client with retry & staleness guards
├─ service/
│   ├─ LoadOverrideService → computes compensation target from Solis API
│   ├─ PowerControlService → **core algorithm**: filter + distribute + slew
│   └─ StatusService       → builds StatusView for UI/logs
├─ web/
│   ├─ StatusController    → /status JSON + serves /status.html
│   └─ static/status.html  → live UI (no framework, tiny & fast)
└─ SmartMetrAppApplication → Spring Boot bootstrap
```

---

## Control algorithm (nutshell)

1. Read **Acrel** power registers `P1/P2/P3/PTOT` and voltages `V1..V3`.  
2. If override disabled or data stale/offline → **pure pass-through** (no edits).  
3. Desired total: `PTOT' = PTOT − compensateKw * 1000`.  
4. Distribute the same offset across alive phases (voltage ≥ threshold).  
5. **Median-3** filter inputs; **slew-limit** outputs by `rateLimitKwPerSec`.  
6. Keep `PTOT` as the sum of clamped phases; apply a tiny near-zero bias (avoid oscillation).  
7. Write back **i32 (MSB-first)** raw values `W/(PT*CT)`.

---

## Status UI

- Minimal static page served by the app: **`/status.html`** (backend JSON: **`/status`**).  
- Shows Solis import/export, compensation, raw meter V/I, published per-phase power, data ages.  
- Color semantics for import/export; explainer for “P total meaning”.  
- Alerts panel mirrors the Telegram pipeline.

**Preview:** https://raspberrypi-bridge.tailaf01c4.ts.net/status.html  
*(Replace with your host/IP when deployed.)*

---

## Supported hardware (tested)

- **Smart meter:** Acrel DTSD1352 register map (V: 97..99; I: 100..102; P1/P2/P3: 356/358/360; PTOT: 362).
- **Inverter side:** Grid-tie inverter reading a Modbus “meter image” on RS-485 (Solis 5G family tested).
- **Controller:** Raspberry Pi 4 (4 GB) + two USB-RS485 adapters (use `/dev/serial/by-id/...` names).

---

## Setup checklist

1. Name your USB-RS485 adapters persistently (`/dev/serial/by-id/...`).
2. Create `.env` with Solis credentials and serial ports (see sample).
3. Set `SMARTMETR_ACREL_CT` / `SMARTMETR_ACREL_PT` to match the site.
4. (Optional) Enable Telegram alerts and set chat IDs.
5. `mvn clean package` → copy artifacts to the Pi.
6. Install & start `smart-meter-app.service`.
7. Open `/status.html` and verify values/ages/alerts.

---

## Alerts (catalog)

| Key                                 | Meaning / Trigger                                      |
|-------------------------------------|---------------------------------------------------------|
| `SOLIS_DOWN`                        | Cloud API failing beyond grace window                   |
| `METER_STALE`                       | No fresh SM frame for `serial.input.meterStaleMs`       |
| `INVERTER_FEEDER_WAITING_FOR_METER` | Publisher paused until a fresh SM frame is seen         |
| `HEARTBEAT`                         | Daily heartbeat ping                                    |
| `STARTUP` / `SHUTDOWN`              | Lifecycle pings                                         |

<details><summary>ASCII diagram (fallback)</summary>

Solis Cloud API  <----polls---->  SmartMetrApp (Raspberry)
|       ^
v       |
RS-485: Smart Meter (Plant B) --raw-->+--“edited meter”--> Grid-tie Inverter (Plant B)

</details>

---

## Configuration (`application.yml` excerpt)

```yaml
serial:
  input:
    port: ${SERIAL_INPUT_PORT:/dev/ttyUSB-METER}
    baudRate: ${SERIAL_INPUT_BAUD:9600}
    slaveId: ${SERIAL_INPUT_SLAVE:1}
    pollInterval: ${SERIAL_INPUT_POLL:1000}
    initialOpenDelayMs: 2000
    reopenBackoffMs: 2000
    warmupMs: 2000
    timeoutsBeforeReopen: 3
    meterStaleMs: 30000
  output:
    port: ${SERIAL_OUTPUT_PORT:/dev/ttyUSB-INVERTER}
    baudRate: ${SERIAL_OUTPUT_BAUD:9600}
    slaveId: ${SERIAL_OUTPUT_SLAVE:1}
    pollInterval: ${SERIAL_OUTPUT_POLL:1000}
    initRegisters: 400
    maxSmAgeForWriteMs: 60000
    republishOnStale: true

solis:
  api:
    id: ${SOLIS_API_ID}
    secret: ${SOLIS_API_SECRET}
    sn: ${SOLIS_API_SN}
    uri: ${SOLIS_URI}
  fetch:
    periodSeconds: ${SOLIS_FETCH_PERIOD_SECONDS:10}
  minImportKw: ${SOLIS_MIN_IMPORT_KW:0.2}   # deadband
  smoothingFactor: ${SOLIS_SMOOTHING_FACTOR:0.8}
  maxDataAgeMs: ${SOLIS_MAX_DATA_AGE_MS:300000}
  overrideEnabled: ${SOLIS_OVERRIDE_ENABLED:true}

smartmetr:
  scale: { pt: 1.0, ct: 30.0 }   # device ratios
  phaseMinVolt: 100.0
  safeDivMinVolt: 100.0
  publish:
    rateLimitKwPerSec: 2.0
  staleToZeroMs: 300000

alert:
  telegram:
    enabled: ${ALERT_TELEGRAM_ENABLED:false}
    botToken: ${ALERT_TELEGRAM_BOT_TOKEN}
    chatIds: ${ALERT_TELEGRAM_CHAT_IDS}
    cooldownMs: 900000
    heartbeat:
      enabled: true
```

---

## Logging

- Logback rolling logs with daily & size rotation (`/home/els/smart-meter-app/logs`).  
- See `src/main/resources/logback-spring.xml` (console + file, 5 GB cap, 180-day history).

---

## Build & Run

### Prerequisites
- **Java 17+**  
- **Maven** (or your IDE’s Maven wrapper)  
- **Raspberry Pi 4/4GB** recommended (any Linux host with two USB-RS485 adapters works)

### Build
```bash
mvn clean package
# → target/SmartMetrApp-*.jar
```

### Local run
```bash
export SPRING_CONFIG_LOCATION=classpath:/application.yml
java -jar target/SmartMetrApp-0.0.1-SNAPSHOT.jar
```

---

## Deploy on Raspberry Pi (`systemd`)

**`/etc/systemd/system/smart-meter-app.service`:**
```ini
[Unit]
Description=Smart Meter Java Service
Wants=network-online.target
After=network-online.target

[Service]
User=els
WorkingDirectory=/home/els/smart-meter-app/bin
EnvironmentFile=/home/els/smart-meter-app/config/.env
ExecStart=/home/els/.sdkman/candidates/java/current/bin/java -Xms128m -Xmx256m \
  -jar /home/els/smart-meter-app/bin/SmartMetrApp-0.0.1-SNAPSHOT.jar
SuccessExitStatus=143
KillSignal=SIGTERM
TimeoutStopSec=30
Restart=on-failure
RestartSec=5
ExecStartPre=/usr/bin/mkdir -p /home/els/smart-meter-app/logs
ExecStartPre=/usr/bin/chown els:els /home/els/smart-meter-app/logs
StandardOutput=append:/home/els/smart-meter-app/logs/stdout.log
StandardError=append:/home/els/smart-meter-app/logs/stderr.log

[Install]
WantedBy=multi-user.target
```

**Enable & start:**
```bash
sudo systemctl daemon-reload
sudo systemctl enable smart-meter-app
sudo systemctl start smart-meter-app
sudo systemctl status smart-meter-app
```

**Tip (stable ports):** use `/dev/serial/by-id/...` or `udev` rules to give USB-RS485 adapters persistent names (`/dev/ttyUSB-METER`, `/dev/ttyUSB-INVERTER`) and point `SERIAL_*_PORT` to those.

---

## Endpoints

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/status` | JSON snapshot (voltages, currents, powers, ages, Solis data) |
| `GET` | `/status.html` | Live status page (no framework) |
| `GET` | `/alerts`, `/alerts/last` | Active / last alert feed used by the UI |
| `GET` | `/_manage/health`, `/_manage/info` | Spring Boot management (if enabled) |

---

## Robustness features (what makes it “bulletproof”)

- **Serial layer** — open warm-up, read timeouts, timeouts-before-reopen, back-off, error windows; continuous republish on stale to keep the inverter’s Modbus session alive.  
- **Data freshness** — Solis API staleness cutoff → treat compensation as 0 until healthy; smart-meter staleness cutoff → pass-through (or zero) for safety.  
- **Signal conditioning** — median-3 filter on meter powers; slew-rate limiter on published setpoint (kW/s); near-zero hysteresis bias (avoid oscillation around 0 W).  
- **Guards** — per-phase “alive” check via measured voltage; clamps, sanity checks, safe-division guards.  
- **Ops** — `systemd` restarts on failure, graceful shutdown; rolling logs; clear periodic status summaries; Telegram alerts (start/stop pings, `SOLIS_DOWN`, stale meter, “inverter feeder waiting for meter”, etc.).

---

## Safety & limitations (read this)

- Works with **Acrel layout** smart meters (code currently targets specific Acrel register maps).  
- **No inverter firmware mods**; read-only from the inverter’s perspective (publishes the “meter image”).  
- Network/UI intended for **private LAN/VPN** (e.g., Tailscale). Solis credentials live only in a private `.env`.  
- Compliance and legal use are the **customer’s responsibility**; ensure your setup conforms to local regulations and safety practices.

---

## Screenshots

Add screenshots into your repo (e.g., `docs/status-dark.png`) and embed:

```markdown
![Status UI](docs/status-dark.png)
```
*(The live UI link above shows a current sample.)*

---

## License & credits

This project manipulates reported meter values fed to an inverter on a private network to optimize onsite energy. **Use at your own risk.**  
Built by **ELS — Energy Life Systems**.  
Core stack: **Java 17**, **Spring Boot**, **SLF4J/Logback**, **RS-485/Modbus**, **Telegram Bot API**, **systemd**.

---

## Appendix: sample `.env`

```bash
# Serial
SERIAL_INPUT_PORT=/dev/serial/by-id/usb-FTDI_Meter
SERIAL_OUTPUT_PORT=/dev/serial/by-id/usb-CH340_Inverter

# Solis Cloud
SOLIS_API_ID=...
SOLIS_API_SECRET=...
SOLIS_API_SN=...
SOLIS_URI=https://api.soliscloud.com:13333

# Feature toggles
SOLIS_OVERRIDE_ENABLED=true
ALERT_TELEGRAM_ENABLED=true
ALERT_TELEGRAM_BOT_TOKEN=12345:ABC...
ALERT_TELEGRAM_CHAT_IDS=12345678,22334455

# Logging
LOG_FILE_PATH=/home/els/smart-meter-app/logs/app.log
SMARTMETER_LOG_LEVEL=INFO
```
