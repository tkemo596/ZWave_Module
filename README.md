# Z-Wave JS Driver for Ignition 8.3

An Ignition 8.3 OPC UA device driver that brings a Z-Wave network into Ignition as tags. It talks
to a **Z-Wave JS server** (the "WS Server" inside Z-Wave JS UI), which owns the USB controller and
does all the Z-Wave work. The driver discovers every node and value automatically. Values arrive
as pushed events, and writes go straight to the devices.

```
USB controller ── Z-Wave JS UI ── WebSocket :3000 ── this driver ── Ignition OPC UA ── tags
                  (pairing, S2,                       (one device
                   mesh, OTA)                          connection)
```

It follows the standard Ignition 8.3 driver structure: Gradle build, module hook, record-based
settings form and a Milo node tree. The difference is that nothing polls. Z-Wave
is a 100 kbit/s mesh, and Z-Wave JS already pushes every change, so the driver keeps a live model
of the network and serves reads from it.

---

## 1. Set up Z-Wave JS UI (same machine as the gateway)

1. Download the Windows release of **Z-Wave JS UI** from its GitHub releases page and unzip it to
   a permanent folder, e.g. `C:\zwave-js-ui`. The packaged build needs no Node.js install. Skip
   Docker on Windows, because passing USB devices into a container there is fragile.
2. Plug in the controller. Windows normally assigns it a COM port with its built-in driver. If
   Device Manager shows an unknown device, install the Silicon Labs CP210x VCP driver.
3. Run it as a service (NSSM is the simplest wrapper). Set `STORE_DIR` to a fixed folder, e.g.
   `C:\zwave-js-ui\store`, so settings and keys survive upgrades.
4. Open `http://localhost:8091`:
   - **Settings → Z-Wave:** pick the COM port. Generate the S0/S2 security keys and **back them
     up**. Losing them means re-pairing every secure device.
   - **Settings → Home Assistant → WS Server:** enable it, port `3000`, host `127.0.0.1`.
     **The WS server has no authentication**, so keep it on localhost.
   - **Settings → MQTT:** disable it unless you also want MQTT.
5. Pair devices from the Z-Wave JS UI control panel. Inclusion, exclusion, heals and firmware
   updates all stay there. The driver only reads and writes values.

## 2. Build and install the module

Toolchain: JDK 17, the committed Gradle 8.14.3 wrapper,
`io.ia.sdk.modl` 0.5.0 and Ignition SDK 8.3.4 from the IA Nexus.

```
gradlew.bat build --console=plain        # -> build\ZWave-JS-Driver.unsigned.modl
```

The build is unsigned unless you configure signing. To install an unsigned module, add
`wrapper.java.additional.N=-Dignition.allowunsignedmodules=true` to the gateway's
`data\ignition.conf` (N = the next unused number) and restart it. Install under
**Config → Modules**.

Then add a device under **Connections → Devices → Create → Z-Wave JS**. The defaults connect to
`ws://127.0.0.1:3000`. The device status shows `Connected - N nodes, M tags` once it is up.

## 3. Tag layout

The tree appears in the OPC browser under `[DeviceName]`:

```
_Server/Connected, DriverVersion, ServerVersion, SchemaVersion, HomeId, ControllerNodeId, NodeCount
Node_005/
  _Info/Status            Alive | Dead | Asleep | Awake | Unknown
  _Info/Name, Location, Manufacturer, Product, Description, FirmwareVersion, NodeId, Ready
  _Info/RefreshValues     write true -> re-read every value from the device
  _Info/Ping              write true -> ping the node
  Binary_Switch/currentValue          Boolean, read-only (what the device reports)
  Binary_Switch/targetValue           Boolean, writable  (what you command)
Node_007/
  EP1/Multilevel_Sensor/Air_temperature              endpoints > 0 get their own folder
  Notification/Home_Security/Motion_sensor_status    property keys nest one level
  Meter/value/Electric_kWh_Consumed
```

- **Names** are sanitised to letters, digits and underscores. If two names collide after
  sanitising, the later one gets `_2`, `_3` and so on, assigned in a stable order.
- **Node folders** are `Node_NNN` by default, so renaming a device in Z-Wave JS UI does not move
  its tags. Turn on *Name Node Folders* to use the device names instead, at the cost of that
  stability.
- **Types:** number → Double, boolean → Boolean, string → String. Composite Z-Wave types
  (duration, color, buffers, arrays, `any`) are JSON strings. Writes to them accept JSON text, e.g.
  `{"value":2,"unit":"seconds"}` on a dimmer's `duration`.
- **Descriptions** carry the Z-Wave JS label, unit and enumerated states. A notification value's
  description, for example, includes `{"0":"idle","8":"Motion detection"}`.
- **Excluded command classes** (setting): Configuration, Association, Version, Manufacturer
  Specific and similar device-management command classes are hidden by default. Remove
  `Configuration` from the list to get every device parameter as a writable tag.

### Writing

Write to `targetValue`, not `currentValue`. The switch or dimmer then reports its new state on
`currentValue` a moment later, the same way the physical device does. Whole numbers are sent as
integers, because some command classes reject `50.0`. Out-of-range values are refused by Z-Wave JS
and come back as a failed write with the reason in the gateway log.

Battery devices queue writes until they next wake. Z-Wave JS acknowledges the queued write right
away, so a successful write to a sleeping sensor means it was queued, not delivered.

### Quality

| Situation | Quality |
| --- | --- |
| Normal | Good, timestamped with when the value last *changed* |
| Value the device has never reported | Bad_WaitingForInitialData |
| Write-only value (most `targetValue`s) | Good, null until written |
| Node is Dead | Bad_NoCommunication (last value kept) |
| Server connection down | Uncertain_LastUsableValue |

Sleeping battery nodes stay Good. Being asleep is their normal state.

## 4. Settings

| Setting | Default | Notes |
| --- | --- | --- |
| Server URL | `ws://127.0.0.1:3000` | `ws://` or `wss://` |
| Request Timeout | 10000 ms | covers writes too |
| Reconnect Interval | 10 s | |
| API Schema Version | 0 | 0 = newest the server offers; pin only if an upgrade breaks something |
| Name Node Folders | off | see above |
| Excluded Command Classes | management CCs | names or numbers, comma-separated |
| Node Info Tags | on | the `_Info` folders |
| Allow Writes | on | off = everything read-only |

## 5. How it works

- **Maintenance task (1 s).** Owns the connection: it dials, reads the `version` handshake,
  sends `set_api_schema` and `start_listening`, loads the full network state and builds the node
  tree. It pings every 15 s and reconnects if no pong arrives for 45 s.
- **Events** arrive on the socket thread and update the model directly. Ignition's subscription
  sampling reads from the model, so value changes cost nothing on the Z-Wave side.
- **Structural events** (a node added or removed, a re-interview, a new value, a metadata change)
  trigger a rebuild of the node tree. The rebuild waits until the event burst has been quiet for
  2 s, so including a device causes one rebuild, not hundreds.
- **Events that arrive during connect**, between the `start_listening` reply and the state
  loading, are queued and replayed in order.

## 6. Testing without hardware

```
pip install websockets
python tools\mock_zwjs_server.py tools\fixtures\start_listening_state.json 3000
```

This is a stand-in Z-Wave JS server with a switch, a multisensor, a dead dimmer and a moving
temperature reading. Point the real driver at it to see a believable tag tree on the gateway
before any hardware arrives. Stop the real Z-Wave JS UI first, or run the mock on another port.

Offline checks, with no Ignition or network needed:

```
javac -d out gateway\src\main\java\com\arringtonautomation\ignition\zwave\zwjs\*.java tools\ZWaveSelfCheck.java tools\ZWaveEndToEnd.java
java -cp out com.arringtonautomation.ignition.zwave.zwjs.ZWaveSelfCheck tools\fixtures\start_listening_state.json
python tools\mock_zwjs_server.py tools\fixtures\start_listening_state.json 3999 --pad 400
java -cp out com.arringtonautomation.ignition.zwave.zwjs.ZWaveEndToEnd ws://127.0.0.1:3999
```

## 7. Troubleshooting

**Device status says `connection refused - nothing is listening there`.** Port 3000 isn't open.
Run `Test-NetConnection 127.0.0.1 -Port 3000` in PowerShell. If it reports `False`:

- Make sure Z-Wave JS UI is running (`http://localhost:8091` loads).
- **Settings → Home Assistant → WS Server** must be enabled, on port 3000 and host `127.0.0.1`,
  and **saved** with the button at the bottom of the page.
- Z-Wave JS UI must be connected to the stick. The WS Server only starts once its driver is up,
  so a wrong or empty serial port keeps 3000 closed.

The driver retries every Reconnect Interval, so it connects on its own once the port opens.

## 8. Not implemented (yet)

- Inclusion, exclusion, heal and firmware updates from Ignition. Use Z-Wave JS UI for these.
- Central Scene button presses and other stateless notifications as momentary tags. They arrive
  as `value notification` events and update the value, but there is no auto-reset.
- Per-node polling. Z-Wave JS's own polling settings apply instead.
- Zigbee. A Zigbee2MQTT bridge would be a separate, similar driver.

## License

Copyright 2026 Arrington Automation Incorporated. Licensed under the
[Apache License 2.0](LICENSE); see [NOTICE](NOTICE) for attributions. Ignition is a trademark of
Inductive Automation, and this is an independent module, not an Inductive Automation product.
