package nodomain.freeyourgadget.gadgetbridge.service.devices.sennheiser.momentum3

import android.os.Handler
import android.os.Looper
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState
import nodomain.freeyourgadget.gadgetbridge.service.AbstractHeadphoneBTBRDeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder
import nodomain.freeyourgadget.gadgetbridge.service.devices.sennheiser.momentum3.SennheiserMomentum3Protocol.Frame
import nodomain.freeyourgadget.gadgetbridge.service.devices.sennheiser.momentum3.SennheiserMomentum3Protocol.readU16
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.ArrayDeque
import java.util.UUID

class SennheiserMomentum3Support : AbstractHeadphoneBTBRDeviceSupport(LOG, 1024) {
    private val decoder = SennheiserMomentum3Protocol.Decoder()
    private val handler = Handler(Looper.getMainLooper())

    // The official app waits for each response before sending the next request.
    private val queue = ArrayDeque<Request>()
    private var inFlight: Request? = null

    // The headset only takes a frame that follows a 0x2A, and stops answering after an empty frame,
    // so only the first frame of a connection carries a leading 0x2A.
    private var startDelimiterPending = false

    private data class Request(val group: Int, val command: Int, val data: ByteArray = byteArrayOf())

    init {
        addSupportedService(SERVICE_UUID)
    }

    override fun useAutoConnect(): Boolean = false

    override fun dispose() {
        synchronized(ConnectionMonitor) {
            handler.removeCallbacksAndMessages(null)
            super.dispose()
        }
    }

    override fun initializeDevice(builder: TransactionBuilder): TransactionBuilder {
        handler.removeCallbacksAndMessages(null)
        synchronized(queue) {
            queue.clear()
            inFlight = null
            startDelimiterPending = true
        }
        builder.setDeviceState(GBDevice.State.INITIALIZED)

        request(GROUP_SYSTEM, CMD_PROTOCOL_VERSION)
        request(GROUP_BATTERY, CMD_BATTERY_LEVEL)
        handler.postDelayed(batteryPoll, BATTERY_POLL_MILLIS)
        return builder
    }

    override fun onSocketRead(data: ByteArray) {
        for (frame in decoder.decode(data)) {
            handleFrame(frame)
        }
    }

    private fun handleFrame(frame: Frame) {
        if (frame.isNotification) {
            handleNotification(frame)
            return
        }

        val request = synchronized(queue) {
            val current = inFlight
            if (current == null || current.group != frame.group || current.command != frame.command) {
                LOG.warn("Unexpected response {}/{}: {}", hex(frame.group), hex(frame.command), GB.hexdump(frame.data))
                return
            }
            inFlight = null
            current
        }
        handler.removeCallbacks(requestTimeout)
        handleResponse(request, frame.data)
        sendNext()
    }

    private fun handleResponse(request: Request, data: ByteArray) {
        when (request.group to request.command) {
            GROUP_BATTERY to CMD_BATTERY_LEVEL -> if (data.size >= 2) {
                evaluateGBDeviceEvent(GBDeviceEventBatteryInfo().apply {
                    level = readU16(data, 0)
                    state = BatteryState.BATTERY_NORMAL
                })
            }

            else -> LOG.debug("Response {}/{}: {}", hex(request.group), hex(request.command), GB.hexdump(data))
        }
    }

    private fun handleNotification(frame: Frame) {
        when (frame.group to frame.command) {
            else -> LOG.debug("Notification {}/{}: {}", hex(frame.group), hex(frame.command), GB.hexdump(frame.data))
        }
    }

    private val batteryPoll = object : Runnable {
        override fun run() {
            request(GROUP_BATTERY, CMD_BATTERY_LEVEL)
            handler.postDelayed(this, BATTERY_POLL_MILLIS)
        }
    }

    private val requestTimeout = Runnable {
        val timedOut = synchronized(queue) { inFlight.also { inFlight = null } }
        if (timedOut != null) {
            LOG.warn("No response to {}/{}", hex(timedOut.group), hex(timedOut.command))
        }
        sendNext()
    }

    private fun request(group: Int, command: Int, data: ByteArray = byteArrayOf()) {
        synchronized(queue) {
            queue.add(Request(group, command, data))
        }
        sendNext()
    }

    private fun sendNext() {
        var frame = ByteArray(0)
        val next = synchronized(queue) {
            if (!isConnected) {
                queue.clear()
                inFlight = null
                return
            }
            if (inFlight != null || queue.isEmpty()) {
                return
            }
            val request = queue.poll() ?: return
            inFlight = request
            frame = SennheiserMomentum3Protocol.encode(request.group, request.command, request.data)
            if (startDelimiterPending) {
                frame = START_DELIMITER + frame
                startDelimiterPending = false
            }
            request
        }
        handler.postDelayed(requestTimeout, REQUEST_TIMEOUT_MILLIS)
        val builder = createTransactionBuilder("request ${hex(next.group)}/${hex(next.command)}")
        builder.write(*frame)
        builder.queue()
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(SennheiserMomentum3Support::class.java)

        private val SERVICE_UUID: UUID = UUID.fromString("1ddce62a-ecb1-4455-8153-0743c87aec9f")
        private val START_DELIMITER = byteArrayOf(0x2A)
        private const val REQUEST_TIMEOUT_MILLIS = 2000L
        private const val BATTERY_POLL_MILLIS = 10 * 60 * 1000L

        const val GROUP_SYSTEM = 0x01
        const val CMD_PROTOCOL_VERSION = 0x16

        const val GROUP_BATTERY = 0x12
        const val CMD_BATTERY_LEVEL = 0x03

        private fun hex(value: Int) = String.format("%02x", value)
    }
}
