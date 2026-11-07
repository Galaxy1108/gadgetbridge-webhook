package nodomain.freeyourgadget.gadgetbridge.devices.withingssteelhr;

import androidx.annotation.NonNull;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractTimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.WithingsBreathingDisturbanceSample;
import nodomain.freeyourgadget.gadgetbridge.entities.WithingsBreathingDisturbanceSampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

public class WithingsBreathingDisturbanceSampleProvider extends AbstractTimeSampleProvider<WithingsBreathingDisturbanceSample> {
    public WithingsBreathingDisturbanceSampleProvider(@NonNull final GBDevice device, @NonNull final DaoSession session) {
        super(device, session);
    }

    @NonNull
    @Override
    public AbstractDao<WithingsBreathingDisturbanceSample, ?> getSampleDao() {
        return getSession().getWithingsBreathingDisturbanceSampleDao();
    }

    @NonNull
    @Override
    protected Property getTimestampSampleProperty() {
        return WithingsBreathingDisturbanceSampleDao.Properties.Timestamp;
    }

    @NonNull
    @Override
    protected Property getDeviceIdentifierSampleProperty() {
        return WithingsBreathingDisturbanceSampleDao.Properties.DeviceId;
    }

    @Override
    public WithingsBreathingDisturbanceSample createSample() {
        return new WithingsBreathingDisturbanceSample();
    }
}
