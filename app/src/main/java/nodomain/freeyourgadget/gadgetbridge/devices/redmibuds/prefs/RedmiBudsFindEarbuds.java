package nodomain.freeyourgadget.gadgetbridge.devices.redmibuds.prefs;

import androidx.annotation.StringRes;

import nodomain.freeyourgadget.gadgetbridge.R;

public enum RedmiBudsFindEarbuds implements RedmiBudsPreferenceEntry {
    OFF  ((byte) 0x00, R.string.off),
    LEFT ((byte) 0x01, R.string.left_earbud),
    RIGHT((byte) 0x02, R.string.right_earbud),
    BOTH ((byte) 0x03, R.string.both_earbuds),
    ;

    private final byte code;
    @StringRes
    private final int label;

    RedmiBudsFindEarbuds(final byte code, @StringRes final int label) {
        this.code = code;
        this.label = label;
    }

    @Override
    public byte getCode() {
        return code;
    }

    @Override
    public int getLabel() {
        return label;
    }
}
