package app.unison.bridge;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A local clipboard item that the keyboard publishes to the app.
 *
 * Text and HTML travel in the parcel. Binary content is streamed through the pipe of
 * {@code publishBinary} and only described by this class.
 */
public final class ClipPayload implements Parcelable {

    /** Plain text, null for binary content. */
    public final String text;

    /** HTML representation of the text, null when there is none. */
    public final String html;

    /** MIME type of the content, null when it is not known. */
    public final String mime;

    /** Size of the content in bytes, -1 when it is not known. */
    public final long size;

    public ClipPayload(String text, String html, String mime, long size) {
        this.text = text;
        this.html = html;
        this.mime = mime;
        this.size = size;
    }

    private ClipPayload(Parcel in) {
        text = in.readString();
        html = in.readString();
        mime = in.readString();
        size = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(text);
        out.writeString(html);
        out.writeString(mime);
        out.writeLong(size);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ClipPayload> CREATOR = new Creator<>() {
        @Override
        public ClipPayload createFromParcel(Parcel in) {
            return new ClipPayload(in);
        }

        @Override
        public ClipPayload[] newArray(int size) {
            return new ClipPayload[size];
        }
    };
}
