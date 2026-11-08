/*  Copyright (C) 2026 d3vv3

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.model;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

/**
 * An ECG measurement, with timestamps in milliseconds.
 */
public class EcgRecord {
    private final long id;
    private final long startTimestamp;
    private final long endTimestamp;
    private final float sampleRateHz;
    @Nullable
    private final Integer averageHeartRate;
    private final long classificationCode;
    @StringRes
    private final int classification;

    public EcgRecord(final long id,
                     final long startTimestamp,
                     final long endTimestamp,
                     final float sampleRateHz,
                     @Nullable final Integer averageHeartRate,
                     final long classificationCode,
                     @StringRes final int classification) {
        this.id = id;
        this.startTimestamp = startTimestamp;
        this.endTimestamp = endTimestamp;
        this.sampleRateHz = sampleRateHz;
        this.averageHeartRate = averageHeartRate;
        this.classificationCode = classificationCode;
        this.classification = classification;
    }

    /**
     * The key of the record, in the database of its provider.
     */
    public long getId() {
        return id;
    }

    public long getStartTimestamp() {
        return startTimestamp;
    }

    public long getEndTimestamp() {
        return endTimestamp;
    }

    public float getSampleRateHz() {
        return sampleRateHz;
    }

    @Nullable
    public Integer getAverageHeartRate() {
        return averageHeartRate;
    }

    /**
     * The classification code that the device reported.
     */
    public long getClassificationCode() {
        return classificationCode;
    }

    /**
     * The label of the classification code, or 0 if the meaning of the code is unknown.
     */
    @StringRes
    public int getClassification() {
        return classification;
    }
}
