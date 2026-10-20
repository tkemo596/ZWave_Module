package com.arringtonautomation.ignition.zwave;

import com.inductiveautomation.ignition.gateway.config.ValidationErrors.Builder;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.SchemaUtil;
import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceProfileConfig;
import com.inductiveautomation.ignition.gateway.web.nav.ExtensionPointResourceForm;
import com.inductiveautomation.ignition.gateway.web.nav.WebUiComponent;
import java.net.URI;
import java.util.Optional;
import java.util.Set;

/** Registers "Z-Wave JS" as a device type in Ignition's OPC UA server. */
public class ZWaveDeviceExtensionPoint extends DeviceExtensionPoint<ZWaveDeviceConfig> {

  public static final String TYPE_ID = "com.arringtonautomation.zwave.ZWaveJsDevice";

  public ZWaveDeviceExtensionPoint() {
    super(
        TYPE_ID,
        "ZWaveDevice.Meta.DisplayName",
        "ZWaveDevice.Meta.Description",
        ZWaveDeviceConfig.class);
  }

  @Override
  protected Device createDevice(
      DeviceContext context, DeviceProfileConfig profileConfig, ZWaveDeviceConfig deviceConfig) {

    return new ZWaveDevice(context, deviceConfig);
  }

  @Override
  public Optional<WebUiComponent> getWebUiComponent(ComponentType type) {
    return Optional.of(
        new ExtensionPointResourceForm(
            DeviceExtensionPoint.DEVICE_RESOURCE_TYPE,
            "Device Connection",
            TYPE_ID,
            SchemaUtil.fromType(DeviceProfileConfig.class),
            SchemaUtil.fromType(ZWaveDeviceConfig.class),
            Set.of()));
  }

  @Override
  protected void validate(ZWaveDeviceConfig config, Builder errors) {
    var connection = config.connection();

    errors.check(
        isWebSocketUrl(connection.serverUrl()),
        "Server URL must be a ws:// or wss:// URL, e.g. ws://127.0.0.1:3000");

    errors.check(connection.timeoutMs() >= 500, "Request Timeout must be at least 500 ms");

    errors.check(
        connection.reconnectSeconds() >= 1, "Reconnect Interval must be at least 1 second");

    errors.check(
        connection.schemaVersion() >= 0, "API Schema Version cannot be negative (0 = automatic)");
  }

  static boolean isWebSocketUrl(String url) {
    if (url == null || url.isBlank()) {
      return false;
    }
    try {
      URI uri = URI.create(url.trim());
      String scheme = uri.getScheme();
      return ("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme))
          && uri.getHost() != null;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
