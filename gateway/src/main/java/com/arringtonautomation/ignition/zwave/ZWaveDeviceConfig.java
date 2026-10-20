package com.arringtonautomation.ignition.zwave;

import com.arringtonautomation.ignition.zwave.zwjs.ZWaveModel;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.DefaultValue;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Description;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormCategory;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormField;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Label;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Required;
import com.inductiveautomation.ignition.gateway.web.nav.FormFieldType;

/**
 * The device settings form, as a record - the 8.3 way: the gateway generates the form from it.
 *
 * <p><b>Every scalar component must carry a {@code @FormField}</b>, or the gateway's device page
 * fails with "Application Error". Nested record components must not.
 */
public record ZWaveDeviceConfig(Connection connection, Browse browse, Writes writes) {

  /** How to reach the Z-Wave JS server. */
  public record Connection(
      @FormCategory("CONNECTION")
          @Label("Server URL*")
          @FormField(FormFieldType.TEXT)
          @DefaultValue("ws://127.0.0.1:3000")
          @Required
          @Description(
              "WebSocket address of the Z-Wave JS server. In Z-Wave JS UI this is Settings > Home"
                  + " Assistant > WS Server (port 3000 by default). The server has no"
                  + " authentication, so keep it bound to 127.0.0.1 when it runs on this gateway.")
          String serverUrl,
      @FormCategory("CONNECTION")
          @Label("Request Timeout (ms)")
          @FormField(FormFieldType.NUMBER)
          @DefaultValue("10000")
          @Description(
              "How long to wait for the server to answer a command, including writes. Commands to"
                  + " battery devices are queued by Z-Wave JS until the device wakes, so a write to"
                  + " a sleeping sensor reports success once queued, not once delivered.")
          int timeoutMs,
      @FormCategory("CONNECTION")
          @Label("Reconnect Interval (s)")
          @FormField(FormFieldType.NUMBER)
          @DefaultValue("10")
          @Description("How long to wait before retrying after the connection drops.")
          int reconnectSeconds,
      @FormCategory("CONNECTION")
          @Label("API Schema Version")
          @FormField(FormFieldType.NUMBER)
          @DefaultValue("0")
          @Description(
              "Z-Wave JS server API schema to request. 0 uses the newest the server supports."
                  + " Pin a number only if a server upgrade changes a message shape this driver"
                  + " depends on.")
          int schemaVersion) {}

  /** What to turn into tags. */
  public record Browse(
      @FormCategory("BROWSE")
          @Label("Name Node Folders")
          @FormField(FormFieldType.CHECKBOX)
          @DefaultValue("false")
          @Description(
              "Off, node folders are Node_005 and so on - stable however devices are renamed. On,"
                  + " folders use the node's name from Z-Wave JS UI; renaming a device then moves"
                  + " its tags and breaks any Ignition tag pointing at the old path.")
          boolean nameNodeFolders,
      @FormCategory("BROWSE")
          @Label("Excluded Command Classes")
          @FormField(FormFieldType.TEXT)
          @DefaultValue(ZWaveModel.DEFAULT_EXCLUDED_CCS)
          @Description(
              "Comma-separated command class names or numbers to leave out of the tag tree. The"
                  + " defaults hide device-management plumbing. Remove \"Configuration\" to expose"
                  + " every device parameter as a writable tag.")
          String excludedCommandClasses,
      @FormCategory("BROWSE")
          @Label("Node Info Tags")
          @FormField(FormFieldType.CHECKBOX)
          @DefaultValue("true")
          @Description(
              "Add an _Info folder to each node with Status (Alive/Dead/Asleep/Awake), Name,"
                  + " Location, Manufacturer, Product and the RefreshValues and Ping commands.")
          boolean includeNodeInfo) {}

  /** Write behaviour. */
  public record Writes(
      @FormCategory("WRITES")
          @Label("Allow Writes")
          @FormField(FormFieldType.CHECKBOX)
          @DefaultValue("true")
          @Description(
              "Off makes every tag read-only at the OPC UA layer, whatever Z-Wave JS permits.")
          boolean writesEnabled) {}
}
