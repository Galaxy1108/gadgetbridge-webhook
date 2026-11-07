package nodomain.freeyourgadget.gadgetbridge.devices.test.samples;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.devices.test.TestDeviceRand;
import nodomain.freeyourgadget.gadgetbridge.model.SleepBreathingSample;

public class TestSleepBreathingSampleProvider extends AbstractTestSampleProvider<SleepBreathingSample> {
    private static final int SAMPLE_DURATION_SECONDS = 5 * 60;
    private static final long SAMPLE_DURATION_MILLIS = SAMPLE_DURATION_SECONDS * 1000L;
    private static final long HALF_DAY_MILLIS = DAY_MILLIS / 2;

    @NonNull
    @Override
    protected List<SleepBreathingSample> generateSamples(final long timestampFrom, final long timestampTo) {
        final List<SleepBreathingSample> samples = new ArrayList<>();

        final long remainder = timestampFrom % SAMPLE_DURATION_MILLIS;
        final long first = remainder == 0 ? timestampFrom : timestampFrom + SAMPLE_DURATION_MILLIS - remainder;
        for (long ts = first; ts < timestampTo; ts += SAMPLE_DURATION_MILLIS) {
            samples.add(new TestSleepBreathingSample(ts));
        }

        return samples;
    }

    @Nullable
    @Override
    public SleepBreathingSample getLatestSample() {
        final long now = System.currentTimeMillis();
        return new TestSleepBreathingSample(now - now % SAMPLE_DURATION_MILLIS);
    }

    @Nullable
    @Override
    public SleepBreathingSample getFirstSample() {
        return new TestSleepBreathingSample(TestDeviceRand.BASE_TIMESTAMP);
    }

    protected static class TestSleepBreathingSample implements SleepBreathingSample {
        private final long timestamp;

        public TestSleepBreathingSample(final long timestamp) {
            this.timestamp = timestamp;
        }

        @Override
        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public int getDuration() {
            return SAMPLE_DURATION_SECONDS;
        }

        @Override
        public int getBreathingEventProbability() {
            // One base value for all samples of the same night, so that the score only changes from night to night
            final int nightBase = TestDeviceRand.randInt(startOfDay(timestamp + HALF_DAY_MILLIS), 5, 110);
            return clamp(nightBase + TestDeviceRand.randInt(timestamp, -10, 10), 0, 127);
        }
    }
}
