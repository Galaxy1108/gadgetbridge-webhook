package nodomain.freeyourgadget.gadgetbridge.devices.huami

import nodomain.freeyourgadget.gadgetbridge.devices.DefaultSleepSessionProvider
import nodomain.freeyourgadget.gadgetbridge.devices.SleepSessionProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.SleepSession
import nodomain.freeyourgadget.gadgetbridge.model.SleepStage

class HuamiSleepSessionProvider(
    private val device: GBDevice,
    private val session: DaoSession
) : SleepSessionProvider {
    override fun getSleepSessions(tsFrom: Int, tsTo: Int): List<SleepSession> {
        val nativeSessions = getNativeSleepSessions(tsFrom, tsTo)
        return nativeSessions.ifEmpty {
            DefaultSleepSessionProvider(device, session).getSleepSessions(tsFrom, tsTo)
        }
    }

    /** Native records only, so activity overlays do not recursively request their own fallback. */
    fun getNativeSleepSessions(tsFrom: Int, tsTo: Int): List<SleepSession> {
        val sleepSessions = ArrayList<SleepSession>()

        val fromMillis: Long = (tsFrom - LOOK_BACK_SECONDS) * 1000L
        // The record timestamp is its report time, which may be on the following day.
        val toMillis: Long = (tsTo.toLong() + LOOK_AHEAD_SECONDS) * 1000L

        val sleepSessionSampleProvider = HuamiSleepSessionSampleProvider(device, session)
        val sleepSessionSamples = sleepSessionSampleProvider.getAllSamples(fromMillis, toMillis)

        for (sessionSample in sleepSessionSamples) {
            val rawSession = HuamiSleepSessionSampleProvider.SleepSession(sessionSample.data)
            if (rawSession.stages.isEmpty()) {
                continue
            }

            val stages = ArrayList<SleepStage>(rawSession.stages.size)
            for (stage in rawSession.stages) {
                val start = (rawSession.timestampMidnight - 24 * 3600 + stage.start * 60) * 1000L
                val end = (rawSession.timestampMidnight - 24 * 3600 + stage.end * 60) * 1000L
                stages.add(SleepStage(stage.asActivityKind(), start, end))
            }

            sleepSessions.add(SleepSession(stages))
        }

        return sleepSessions
            .filter { (it.startTime / 1000L) in tsFrom..<tsTo }
            // Ascending report times: retain the latest revision of each sleep session.
            .associateBy { it.startTime }
            .values
            .sortedBy { it.startTime }
    }

    companion object {
        private const val LOOK_BACK_SECONDS: Int = 12 * 60 * 60
        private const val LOOK_AHEAD_SECONDS: Int = 48 * 60 * 60
    }
}
