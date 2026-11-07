package nodomain.freeyourgadget.gadgetbridge.devices.test.samples;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import nodomain.freeyourgadget.gadgetbridge.devices.EcgDataProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.test.TestDeviceRand;
import nodomain.freeyourgadget.gadgetbridge.model.EcgRecord;
import nodomain.freeyourgadget.gadgetbridge.model.EcgSample;

public class TestEcgDataProvider implements EcgDataProvider {
    private static final long MEASUREMENT_TIME_MILLIS = 10 * 60 * 60 * 1000L;
    private static final int DURATION_SECONDS = 30;
    private static final int SAMPLE_RATE_HZ = 128;

    /**
     * The P, Q, R, S and T waves of a beat, as:
     * - offset from the R peak, in seconds
     * - amplitude in mV
     * - width in seconds
     */
    private static final float[][] WAVES = {
        {-0.16f, 0.12f, 0.02f},
        {-0.04f, -0.1f, 0.01f},
        {0f, 1f, 0.01f},
        {0.04f, -0.25f, 0.012f},
        {0.25f, 0.3f, 0.045f},
    };

    @NonNull
    @Override
    public List<EcgRecord> getSessions(final long startTimestamp, final long endTimestamp) {
        final List<EcgRecord> sessions = new ArrayList<>();
        final long until = Math.min(endTimestamp, System.currentTimeMillis());

        for (long day = AbstractTestSampleProvider.startOfDay(startTimestamp); day <= until; day += AbstractTestSampleProvider.DAY_MILLIS) {
            final long ts = day + MEASUREMENT_TIME_MILLIS;
            if (ts < startTimestamp || ts > until || !TestDeviceRand.randBool(ts, 0.5f)) {
                continue;
            }

            sessions.add(new EcgRecord(
                ts,
                0,
                0,
                ts,
                ts + DURATION_SECONDS * 1000L,
                "test",
                TestDeviceRand.randInt(ts + 1, 50, 90),
                TestDeviceRand.randBool(ts + 2, 0.8f) ? 0 : -1,
                0
            ));
        }

        return sessions;
    }

    @NonNull
    @Override
    public List<EcgSample> getSamples(@Nullable final EcgRecord record) {
        if (record == null) {
            return Collections.emptyList();
        }

        final Random random = new Random(record.getStartTimestamp() - TestDeviceRand.BASE_TIMESTAMP);

        final List<Float> beats = new ArrayList<>();
        final float beatSeconds = 60f / record.getAverageHeartRate();
        for (float beat = beatSeconds / 2; beat < DURATION_SECONDS; beat += beatSeconds * (0.97f + random.nextFloat() * 0.06f)) {
            beats.add(beat);
        }

        final int sampleCount = DURATION_SECONDS * SAMPLE_RATE_HZ;
        final List<EcgSample> samples = new ArrayList<>(sampleCount);
        for (int i = 0; i < sampleCount; i++) {
            final float seconds = (float) i / SAMPLE_RATE_HZ;
            float value = (random.nextFloat() - 0.5f) * 0.02f;
            for (final float beat : beats) {
                for (final float[] wave : WAVES) {
                    final float distance = (seconds - beat - wave[0]) / wave[2];
                    value += (float) (wave[1] * Math.exp(-distance * distance / 2));
                }
            }
            samples.add(new EcgSample(Math.round(seconds * 1000), value));
        }
        return samples;
    }
}
