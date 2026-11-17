package app.unison.bridge;

import android.os.Parcel;
import android.os.Parcelable;

/** Outcome of a publish that the app reports to the keyboard. */
public final class PublishResult implements Parcelable {

    /** One of the {@code PUBLISH_*} constants of {@link BridgeContract}. */
    public final int code;

    /** MIME type of the published content, empty when the payload carried none. */
    public final String mime;

    public PublishResult(int code, String mime) {
        this.code = code;
        this.mime = mime;
    }

    private PublishResult(Parcel in) {
        code = in.readInt();
        mime = in.readString();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(code);
        out.writeString(mime);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<PublishResult> CREATOR = new Creator<>() {
        @Override
        public PublishResult createFromParcel(Parcel in) {
            return new PublishResult(in);
        }

        @Override
        public PublishResult[] newArray(int size) {
            return new PublishResult[size];
        }
    };
}
