"""A small stand-in for zwave-js-server, for testing the driver without a Z-Wave stick.

    pip install websockets
    python tools/mock_zwjs_server.py tools/fixtures/start_listening_state.json 3000

Speaks enough of the real protocol to exercise the driver end to end: the version handshake,
set_api_schema, start_listening, node.set_value (with a follow-up "value updated" event, as a real
device produces), node.refresh_values and node.ping, plus error replies for unknown commands.

Pointing the real Ignition driver at this is a useful smoke test on the gateway too: it populates a
believable tag tree and moves values every few seconds.

Test hooks used by tools/ZWaveEndToEnd.java:
  - connecting to path /silent gets no version message (simulates a non-Z-Wave-JS server)
  - command "test.close" makes the server drop the connection
  - argument "--pad N" adds N filler nodes to make start_listening's reply large (frame splitting)
"""

import asyncio
import copy
import json
import random
import sys

import websockets

SCHEMA_MIN, SCHEMA_MAX = 0, 42


def load_state(path, pad):
    with open(path, encoding="utf-8") as f:
        state = json.load(f)
    for i in range(pad):
        node_id = 100 + i
        state["nodes"].append({
            "nodeId": node_id, "status": 4, "ready": True, "name": f"Filler {i}",
            "deviceConfig": {"manufacturer": "Mock", "label": "PAD", "description": "x" * 200},
            "values": [{
                "commandClass": 49, "commandClassName": "Multilevel Sensor", "endpoint": 0,
                "property": f"Sensor {j}", "propertyName": f"Sensor {j}",
                "metadata": {"type": "number", "readable": True, "writeable": False,
                             "label": "Filler sensor " + "y" * 100},
                "value": j,
            } for j in range(20)],
        })
    return state


def find_value(state, node_id, value_id):
    for node in state["nodes"]:
        if node["nodeId"] != node_id:
            continue
        for v in node["values"]:
            if (v["commandClass"] == value_id.get("commandClass")
                    and v.get("endpoint", 0) == value_id.get("endpoint", 0)
                    and v["property"] == value_id.get("property")
                    and v.get("propertyKey") == value_id.get("propertyKey")):
                return node, v
        return node, None
    return None, None


async def main():
    fixture = sys.argv[1]
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 3000
    pad = int(sys.argv[sys.argv.index("--pad") + 1]) if "--pad" in sys.argv else 0
    template = load_state(fixture, pad)

    async def handler(ws):
        state = copy.deepcopy(template)
        listening = False

        async def event(ev):
            await ws.send(json.dumps({"type": "event", "event": ev}))

        if ws.request.path != "/silent":
            await ws.send(json.dumps({
                "type": "version", "driverVersion": "15.0.0-mock", "serverVersion": "3.0.0-mock",
                "homeId": state["controller"]["homeId"],
                "minSchemaVersion": SCHEMA_MIN, "maxSchemaVersion": SCHEMA_MAX,
            }))

        async def ticker():
            # Move a sensor now and then, like a real network.
            while True:
                await asyncio.sleep(3)
                if listening:
                    await event({
                        "source": "node", "event": "value updated", "nodeId": 7,
                        "args": {"commandClassName": "Multilevel Sensor", "commandClass": 49,
                                 "endpoint": 1, "property": "Air temperature",
                                 "propertyName": "Air temperature",
                                 "newValue": round(68 + random.random() * 6, 1)},
                    })

        tick = asyncio.create_task(ticker())
        try:
            async for raw in ws:
                msg = json.loads(raw)
                mid, cmd = msg.get("messageId"), msg.get("command")

                def ok(result=None):
                    return ws.send(json.dumps({"type": "result", "messageId": mid,
                                               "success": True, "result": result or {}}))

                def fail(code, message=""):
                    return ws.send(json.dumps({"type": "result", "messageId": mid,
                                               "success": False, "errorCode": code,
                                               "message": message}))

                if cmd == "set_api_schema":
                    v = msg.get("schemaVersion")
                    if not isinstance(v, int) or not SCHEMA_MIN <= v <= SCHEMA_MAX:
                        await fail("schema_incompatible")
                    else:
                        await ok()
                elif cmd == "start_listening":
                    await ok({"state": state})
                    listening = True
                elif cmd == "node.set_value":
                    node, v = find_value(state, msg.get("nodeId"), msg.get("valueId", {}))
                    if node is None:
                        await fail("node_not_found", "Node %s not found" % msg.get("nodeId"))
                        continue
                    if v is None:
                        await ok({"result": {"status": 0}})
                        continue
                    md = v.get("metadata") or {}
                    value = msg.get("value")
                    if not md.get("writeable"):
                        await ok({"result": {"status": 5, "message": "value is read-only"}})
                        continue
                    if md.get("type") == "number":
                        if isinstance(value, bool) or not isinstance(value, (int, float)):
                            await ok({"result": {"status": 5, "message": "expected a number"}})
                            continue
                        if ("max" in md and value > md["max"]) or ("min" in md and value < md["min"]):
                            await ok({"result": {"status": 5, "message": "out of range"}})
                            continue
                    if md.get("type") == "boolean" and not isinstance(value, bool):
                        await ok({"result": {"status": 5, "message": "expected a boolean"}})
                        continue
                    await ok({"result": {"status": 255}})
                    # A switch reports its new state on currentValue, as real devices do.
                    if v["property"] == "targetValue":
                        await asyncio.sleep(0.05)
                        _, cur = find_value(state, node["nodeId"], {
                            "commandClass": v["commandClass"], "endpoint": v.get("endpoint", 0),
                            "property": "currentValue"})
                        if cur is not None:
                            cur["value"] = value
                            await event({"source": "node", "event": "value updated",
                                         "nodeId": node["nodeId"],
                                         "args": {"commandClassName": v["commandClassName"],
                                                  "commandClass": v["commandClass"],
                                                  "endpoint": v.get("endpoint", 0),
                                                  "property": "currentValue",
                                                  "propertyName": "currentValue",
                                                  "newValue": value}})
                elif cmd in ("node.refresh_values", "node.ping"):
                    await ok({"responded": True} if cmd == "node.ping" else {})
                elif cmd == "test.close":
                    await ws.close(1011, "test close")
                    return
                else:
                    await fail("unknown_command", "Unknown command %s" % cmd)
        except websockets.ConnectionClosed:
            pass
        finally:
            tick.cancel()

    async with websockets.serve(handler, "127.0.0.1", port, max_size=None):
        print("mock zwave-js-server listening on ws://127.0.0.1:%d" % port, flush=True)
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
