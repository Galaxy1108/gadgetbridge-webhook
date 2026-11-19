package nodomain.freeyourgadget.gadgetbridge.devices

import io.mockk.every
import io.mockk.mockk
import nodomain.freeyourgadget.gadgetbridge.di.RootEntryPoints
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AbstractDeviceCoordinatorDependenciesTest {
    @get:Rule
    val dependencies = MockCoordinatorDependencies()

    private val deviceService = mockk<DeviceService>()

    @Test
    fun theNoArgumentConstructorTakesTheRegisteredApplicationsDependencies() {
        val coordinator = UnknownDeviceCoordinator()

        assertSame(dependencies.deviceService, coordinator.getDeviceService())
    }

    @Test
    fun theNoArgumentConstructorThrowsWithoutARegisteredApplication() {
        every { RootEntryPoints.get(AbstractDeviceCoordinatorEntryPoint::class.java) } answers { callOriginal() }

        val e = assertThrows(IllegalStateException::class.java) { UnknownDeviceCoordinator() }
        assertTrue(e.message, e.message!!.contains("MockCoordinatorDependencies"))
    }

    @Test
    fun theOtherConstructorTakesTheDependenciesItIsGiven() {
        val coordinator = CoordinatorWithGivenDependencies(deviceService)

        assertSame(deviceService, coordinator.getDeviceService())
    }

    private class CoordinatorWithGivenDependencies(
        deviceService: DeviceService,
    ) : AbstractDeviceCoordinator(deviceService) {
        override fun getManufacturer(): String = throw UnsupportedOperationException()

        override fun createDeviceSupport(device: GBDevice): DeviceSupport = throw UnsupportedOperationException()

        override fun getDeviceNameResource(): Int = throw UnsupportedOperationException()

        override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind = throw UnsupportedOperationException()
    }
}
