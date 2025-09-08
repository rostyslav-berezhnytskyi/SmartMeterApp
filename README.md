# SmartMetrApp

Small Spring Boot service that:
- Reads a smart meter over Modbus RTU
- Talks to SolisCloud to get inverter/grid state
- Computes/export/compensation and publishes a simple status UI at **`/status.html`**
- Exposes health/info at **Actuator** (`/_manage/health`, `/_manage/info`)
- Sends alerts/heartbeat to Telegram

## Quick start

```bash
mvn -DskipTests clean package
java -jar target/SmartMetrApp-0.0.1-SNAPSHOT.jar --spring.config.import=optional:file:.env[.properties]
