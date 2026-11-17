package app.unison.bridge;

import android.os.Parcel;
import android.os.Parcelable;

/** Synchronization state that the app reports to the keyboard. */
public final class BridgeState implements Parcelable {

    /** True while the synchronization is paused. */
    public final boolean paused;

    /** End of the pause in Unix milliseconds, 0 when the pause has no end. */
    public final long untilMs;

    /** Number of devices that are online now. */
    public final int onlinePeers;

    public BridgeState(boolean paused, long untilMs, int onlinePeers) {
        this.paused = paused;
        this.untilMs = untilMs;
        this.onlinePeers = onlinePeers;
    }

    private BridgeState(Parcel in) {
        paused = in.readInt() != 0;
        untilMs = in.readLong();
        onlinePeers = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(paused ? 1 : 0);
        out.writeLong(untilMs);
        out.writeInt(onlinePeers);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<BridgeState> CREATOR = new Creator<>() {
        @Override
        public BridgeState createFromParcel(Parcel in) {
            return new BridgeState(in);
        }

        @Override
        public BridgeState[] newArray(int size) {
            return new BridgeState[size];
        }
    };
}
