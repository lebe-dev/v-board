package app.unison.bridge;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * An item that came from another device, as the keyboard sees it.
 *
 * It carries no content: {@code openItem} opens the content when the user picks the item.
 */
public final class ClipSummary implements Parcelable {

    /** Item id, the argument of {@code openItem}. */
    public final String id;

    /** Name of the device the item came from. */
    public final String deviceName;

    /** Kind of the content, one of the KIND_ constants of {@link BridgeContract}. */
    public final int kind;

    /** Short text preview for the suggestion strip, empty when there is no text. */
    public final String preview;

    /** MIME types of the available representations, never null. */
    public final String[] mimeTypes;

    /** Creation time in Unix milliseconds. */
    public final long createdAtMs;

    public ClipSummary(
            String id,
            String deviceName,
            int kind,
            String preview,
            String[] mimeTypes,
            long createdAtMs) {
        this.id = id;
        this.deviceName = deviceName;
        this.kind = kind;
        this.preview = preview;
        this.mimeTypes = mimeTypes != null ? mimeTypes : new String[0];
        this.createdAtMs = createdAtMs;
    }

    private ClipSummary(Parcel in) {
        id = in.readString();
        deviceName = in.readString();
        kind = in.readInt();
        preview = in.readString();
        mimeTypes = in.createStringArray();
        createdAtMs = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(id);
        out.writeString(deviceName);
        out.writeInt(kind);
        out.writeString(preview);
        out.writeStringArray(mimeTypes);
        out.writeLong(createdAtMs);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ClipSummary> CREATOR = new Creator<>() {
        @Override
        public ClipSummary createFromParcel(Parcel in) {
            return new ClipSummary(in);
        }

        @Override
        public ClipSummary[] newArray(int size) {
            return new ClipSummary[size];
        }
    };
}
