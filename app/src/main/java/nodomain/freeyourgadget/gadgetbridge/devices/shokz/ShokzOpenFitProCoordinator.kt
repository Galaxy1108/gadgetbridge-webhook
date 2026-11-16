package nodomain.freeyourgadget.gadgetbridge.devices.shokz

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import java.util.regex.Pattern

class ShokzOpenFitProCoordinator : ShokzCoordinator() {
    override fun getSupportedDeviceName(): Pattern? {
        return Pattern.compile("^OpenFit Pro by Shokz$")
    }

    override fun getDeviceNameResource(): Int {
        return R.string.devicetype_shokz_openfit_pro
    }

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind {
        return DeviceCoordinator.DeviceKind.EARBUDS
    }

    override fun supportsMp3(): Boolean {
        return false
    }

    // The official app never sends CONTROLS_GET to this device.
    override fun supportsControls(): Boolean {
        return false
    }

    override fun supportsTws(): Boolean {
        return true
    }

    override fun supportsNoiseCancelling(): Boolean {
        return true
    }
}
