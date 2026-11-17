package app.unison.bridge;

/** Names, versions and enums that both the app and the keyboard share. */
public final class BridgeContract {

    /** Package of the app that hosts the bridge service. */
    public static final String PACKAGE = "app.unison";

    /** Action of the intent that binds the bridge service. */
    public static final String ACTION_BIND = "app.unison.bridge.BIND";

    /** Class of the bridge service in {@link #PACKAGE}. */
    public static final String SERVICE_CLASS = "app.unison.service.BridgeService";

    /** Signature permission that the bridge service requires from its callers. */
    public static final String PERMISSION = "app.unison.permission.BRIDGE";

    /**
     * Version of the contract this artifact describes.
     *
     * A caller compares it with {@code protocolVersion()} of the bound service and does not use
     * methods it does not know. The methods of version 1 behave the same in every later version.
     */
    public static final int PROTOCOL_VERSION = 2;

    /** First version of the contract; every app that serves the bridge supports it. */
    public static final int FIRST_PROTOCOL_VERSION = 1;

    /** First contract version that reports the outcome of a publish. */
    public static final int PUBLISH_RESULTS_VERSION = 2;

    /** Kind of an item whose content has no known type. */
    public static final int KIND_UNSPECIFIED = 0;

    /** Kind of a text item. */
    public static final int KIND_TEXT = 1;

    /** Kind of an image item. */
    public static final int KIND_IMAGE = 2;

    /** Kind of an item that carries one or more files. */
    public static final int KIND_FILES = 3;

    /** The core recorded the content in the history. */
    public static final int PUBLISH_ACCEPTED = 0;

    /** The synchronization is paused, so the core refused the content. */
    public static final int PUBLISH_PAUSED = 1;

    /** The core did not answer or did not start. */
    public static final int PUBLISH_CORE_UNAVAILABLE = 2;

    /** The core does not carry content of this MIME type. */
    public static final int PUBLISH_UNSUPPORTED_TYPE = 3;

    /** The streamed content is larger than the bridge accepts. */
    public static final int PUBLISH_TOO_LARGE = 4;

    /** The content could not be read from the pipe or arrived too late. */
    public static final int PUBLISH_READ_FAILED = 5;

    /** The payload carried no content. */
    public static final int PUBLISH_EMPTY = 6;

    /** The core refused the content for another reason; the app log has the message. */
    public static final int PUBLISH_FAILED = 7;

    /**
     * True when the core carries content of this MIME type through a publish: plain text, HTML
     * and images.
     */
    public static boolean isSupportedMime(String mime) {
        if (mime == null || mime.isEmpty()) {
            return false;
        }
        return mime.equals("text/plain") || mime.equals("text/html") || mime.startsWith("image/");
    }

    private BridgeContract() {
    }
}
