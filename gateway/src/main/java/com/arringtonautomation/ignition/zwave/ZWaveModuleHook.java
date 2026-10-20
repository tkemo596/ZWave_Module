package com.arringtonautomation.ignition.zwave;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.AbstractDeviceModuleHook;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import java.util.List;

/** Gateway entry point. Registers the Z-Wave JS device type with the OPC UA server. */
public class ZWaveModuleHook extends AbstractDeviceModuleHook {

  @Override
  public void setup(GatewayContext context) {
    super.setup(context);

    BundleUtil.get().addBundle(ZWaveDevice.class);
  }

  @Override
  public void startup(LicenseState activationState) {
    super.startup(activationState);
  }

  @Override
  public void shutdown() {
    super.shutdown();

    BundleUtil.get().removeBundle(ZWaveDevice.class);
  }

  @Override
  protected List<DeviceExtensionPoint<?>> getDeviceExtensionPoints() {
    return List.of(new ZWaveDeviceExtensionPoint());
  }

  @Override
  public boolean isMakerEditionCompatible() {
    return true;
  }
}
