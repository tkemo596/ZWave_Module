# CLAUDE.md — Z-Wave JS Driver for Ignition 8.3

Project memory for Claude Code. Read `README.md` for the user-facing docs. This file holds the
reasoning behind the design, what has and hasn't been verified, and what's left.

---

## What this is

An Ignition 8.3 OPC UA **device driver module** that connects to a **Z-Wave JS server**
(zwave-js-server, embedded in Z-Wave JS UI as "WS Server") over WebSocket, and exposes every
Z-Wave node and value as OPC UA tags with read/write support.

Published by Arrington Automation under Apache-2.0: module id
`com.arringtonautomation.ignition.zwave`, device type id `com.arringtonautomation.zwave.ZWaveJsDevice`,
vendor "Arrington Automation".

**8.3 SDK rules that bite:** every scalar config record component needs `@FormField` (without it
the device page fails with "Application Error"), nested record components must not have one, and
`ExecutionManager.registerAtFixedRate` takes an `int` rate.

**Status: working on the gateway (19 Oct 2026).** First build succeeded against the real SDK
8.3.4 with no code changes. Connected to a real Z-Wave JS UI WS Server (3 nodes, 131 tags);
every device read, wrote and updated live as expected.

---

## Why Z-Wave JS, not the stick directly

The Z-Wave Serial API, S0/S2 security, inclusion and the device database are a very large amount
of work, and Z-Wave JS already does all of it well. The driver is a protocol adapter from the
Z-Wave JS WebSocket API to OPC UA. Pairing and network management stay in Z-Wave JS UI.

Controller choice doesn't matter to the driver: anything Z-Wave JS supports works, e.g. the Zooz
ZST39, Aeotec Z-Stick 10 Pro or Home Assistant Connect ZWA-2 (all Silicon Labs 800-series with
Long Range).

---

## Toolchain

JDK 17, the committed Gradle 8.14.3 wrapper, `io.ia.sdk.modl` 0.5.0, SDK 8.3.4 from the IA Nexus.

```
gradlew.bat build --console=plain        # -> build\ZWave-JS-Driver.unsigned.modl
```

**No runtime dependencies.** WebSocket is the JDK's `java.net.http.WebSocket`; JSON is
`zwjs/Json.java` (reader and writer). Do not add a JSON or WebSocket library, because the gateway
classpath already has several of each and version clashes follow.

---

## Architecture

```
gateway/src/main/java/com/arringtonautomation/ignition/zwave/
  ZWaveModuleHook             AbstractDeviceModuleHook; registers the extension point
  ZWaveDeviceExtensionPoint   registers the device type; validation
  ZWaveDeviceConfig           settings form record (Connection / Browse / Writes)
  ZWaveDevice                 lifecycle, connection, node tree, reads, writes  <-- Milo-facing
  zwjs/                       pure Java, no Ignition/Milo imports - testable with plain javac
    ZwjsClient                WebSocket client: handshake, request/response, events, ping
    ZWaveModel                network state + event application + tag-tree layout
    ZNode, ZValue, ValueId    model types
    TagSpec                   one OPC variable to create (address, not live object)
    ValueCodec                Z-Wave JS <-> Variant conversion; set_value result parsing
    Json                      dependency-free JSON
tools/
  ZWaveSelfCheck.java         102 offline checks (JSON, ids, codec, tree layout, events)
  ZWaveEndToEnd.java          21 checks against the mock server, mirroring ZWaveDevice's flow
  mock_zwjs_server.py         stand-in zwave-js-server (Python websockets)
  fixtures/start_listening_state.json
```

### Protocol, as implemented

1. Connect → server sends `{"type":"version", driverVersion, serverVersion, homeId,
   minSchemaVersion, maxSchemaVersion}`.
2. `set_api_schema` with the newest the server offers (or the configured pin, clamped into range).
3. `start_listening` → `result.state = {driver, controller, nodes:[NodeState]}`. Each NodeState has
   `values: [ValueState]`, where a ValueState carries commandClass, commandClassName, endpoint,
   property, propertyName, propertyKey?, propertyKeyName?, metadata{type, readable, writeable,
   label, unit, min, max, states}, and value.
4. Events `{"type":"event","event":{source, event, nodeId, args}}`. Handled: `value updated`,
   `value notification`, `value added`, `value removed`, `metadata updated`,
   `alive`/`dead`/`sleep`/`wake up`, `ready` (full nodeState, replaces the node), and controller
   `node added`/`node removed`.
5. Writes use `node.set_value {nodeId, valueId, value}`. The result is parsed in both shapes:
   legacy `{success: bool}` and newer `{result: {status, message}}` (SetValueStatus: 0/2/3/4/5
   fail; 1 Working, 254 and 255 succeed).

### Design decisions worth not relitigating

- **No polling.** The model is the value cache, events keep it current, and Milo's
  SubscriptionModel samples the attribute filters. Polling a Z-Wave mesh slows it down for
  everyone.
- **Node IDs are paths, and node folders are `Node_NNN` by default**, so device renames don't
  break Ignition tags. Name-based folders are opt-in.
- **TagSpecs carry addresses (nodeId + value key), not object references.** A `ready` event
  replaces the whole ZNode; nodes built before it must keep working.
- **ValueId keeps JSON types.** Configuration CC uses `property: 3` and Meter uses numeric
  `propertyKey`s. Z-Wave JS matches by type, so the value has to go back exactly as it came.
- **Debounced rebuild (2 s quiet).** An inclusion produces hundreds of metadata and value events.
  `metadata updated` triggers a rebuild only when something visible changed (`shapeOf`).
- **Sends are serialised.** `java.net.http.WebSocket` throws on a second `sendText` while one is
  in flight. OPC write threads and maintenance can overlap, which the end-to-end test exercises
  with 20 parallel requests.
- **Whole-number Doubles go out as integers.** Ignition writes Double, and some CCs reject `50.0`.

---

## Verification status

**Verified (offline, cloud workspace):**
- The whole module compiles with `-Xlint:all` against hand-written stubs of the SDK/Milo surface
  it uses. The only warning is JDK 21's `this-escape` lint on the SubscriptionModel constructor,
  which is the standard 8.3 driver pattern.
- `ZWaveSelfCheck`: 102/102.
- `ZWaveEndToEnd` against the mock: 21/21. Covers the handshake, a 404-node multi-frame
  `start_listening` (~12k tags, about 0.8 s), a write followed by the device's reported value,
  server-side validation errors, error codes, 20 concurrent requests, ping/pong, server-initiated
  close, a non-Z-Wave-JS server, and connection refused.

**Verified on the gateway (19 Oct 2026):**
- `.setDescription(LocalizedText)` on the UaVariableNode builder and `new DateTime(Date)` both
  compile against SDK 8.3.4.
- Handshake, schema negotiation and `start_listening` parsing against a real Z-Wave JS UI WS
  Server; the tree matched the devices; writes, live updates and node status all behave.

**Still unexercised on real hardware:**
1. **Inclusion and exclusion while connected.** These test the `node added`, `ready` and
   `node removed` handling. The code accepts `event.nodeState`/`event.args.nodeState` and
   `event.node`/`event.args.node`. Pair a new device with the driver running and confirm its tags
   appear about 2 s after the interview finishes.
2. **`_Info/RefreshValues` and `_Info/Ping`.** `node.refresh_values` and `node.ping` are assumed
   to exist under those names.
3. **A Z-Wave JS UI restart with the driver running.** It should reconnect within the 10 s
   interval and show Uncertain quality in between.

**Self-check count is now 102** (three added for the connect-failure message), and the
end-to-end count is 21.

**Add a fixture and a check for any protocol finding.** The offline harness is the fast feedback
loop.

---

## Gotchas (environment, not code)

**The WS Server doesn't start until Z-Wave JS UI's own driver has opened the stick.** With no
serial port set, a wrong COM port, or the stick unplugged, port 3000 never opens, even with WS
Server enabled. Toggling WS Server without clicking Save at the bottom of the settings page also
leaves it off. On the first gateway run, `Test-NetConnection 127.0.0.1 -Port 3000` showed
`TcpTestSucceeded : False` until this was sorted out on the Z-Wave JS UI side.

**On Windows, a refused connection surfaces as a bare `ClosedChannelException`** from the JDK
HTTP client. `ZwjsClient.connectFailure()` now translates it (and `ConnectException`) into
"connection refused - check WS Server / the stick", which is what the user actually needs.

**WS Server host `localhost` can bind IPv6 only** (`[::1]:3000`) under Node on Windows, which a
`ws://127.0.0.1` URL won't reach. Set the host to `127.0.0.1` explicitly.

**Unsigned modules** need `wrapper.java.additional.N=-Dignition.allowunsignedmodules=true` in
`data\ignition.conf`, with N the next unused number (a gap in the sequence silently drops later
lines), then a gateway restart.

**Z-Wave JS UI runs as a Windows service via NSSM**: exe `zwave-js-ui-win.exe` from the
`-win.zip` release asset (not "Source code"), `AppDirectory` set to its folder, and `STORE_DIR`
pointing at the `store` folder shipped next to the exe. That folder holds settings and the S0/S2
keys, so back it up.

---

## Open work

1. Exercise the "still unexercised" list above: inclusion while connected, the node commands,
   a Z-Wave JS UI restart.
2. Central Scene / stateless notifications as momentary tags with auto-reset.
3. Optional: expose `node.set_name` / `node.set_location` as writable `_Info` tags.
4. Optional: controller statistics (`_Server`).
5. Zigbee: a Zigbee2MQTT-based sibling driver.

---

## Working notes

- Prefer small standalone diagnostics over speculation. The mock server is the first one here.
- The Ignition SDK repository (nexus.inductiveautomation.com) may not be reachable from sandboxed
  build environments. `javac` against API stubs plus the self-checks is the fallback for
  verifying changes; the real build is `gradlew build` on a machine with Nexus access.
