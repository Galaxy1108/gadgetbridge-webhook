package nodomain.freeyourgadget.gadgetbridge.devices;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.model.EcgRecord;
import nodomain.freeyourgadget.gadgetbridge.model.EcgSample;

/**
 * The ECG sessions of a device, and their waveforms.
 */
public interface EcgDataProvider {
    /**
     * Returns the sessions that start in the given range of millisecond timestamps, sorted by timestamp.
     */
    @NonNull
    List<EcgRecord> getSessions(long startTimestamp, long endTimestamp);

    /**
     * Returns a session's waveform, ordered by time.
     */
    @NonNull
    List<EcgSample> getSamples(@Nullable EcgRecord record);
}
