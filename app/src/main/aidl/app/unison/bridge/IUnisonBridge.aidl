// Contract between the Unison app and the keyboard.
//
// The interface is frozen: methods are only appended at the end, existing methods are never
// changed or removed. A caller reads protocolVersion() and compares it with
// BridgeContract.PROTOCOL_VERSION before it uses a method of a newer version.

package app.unison.bridge;

import android.os.ParcelFileDescriptor;
import app.unison.bridge.BridgeState;
import app.unison.bridge.ClipPayload;
import app.unison.bridge.ClipSummary;
import app.unison.bridge.IPublishCallback;
import app.unison.bridge.IUnisonBridgeListener;

interface IUnisonBridge {
    // Version of the contract served by the app. The publish results need version 2.
    int protocolVersion();

    // Publishes a local clipboard item as text. Binary content goes through publishBinary
    // because a binder transaction is limited to about 1 MB.
    void publishText(in ClipPayload payload);

    // Publishes a local clipboard item whose content is streamed through the pipe. The caller
    // writes the content, then closes the pipe. A type the core does not carry is refused: the
    // app closes the pipe, logs the type and reports the refusal to the callback of
    // publishBinaryWithResult.
    void publishBinary(in ClipPayload meta, in ParcelFileDescriptor data);

    // Subscribes to incoming items and state changes. Starts the core when it is not running,
    // so the state and the recent items are available after registration.
    void registerListener(IUnisonBridgeListener listener);

    // Removes a listener registered earlier.
    void unregisterListener(IUnisonBridgeListener listener);

    // Returns at most limit recent items that came from other devices, newest first.
    List<ClipSummary> recentRemoteItems(int limit);

    // Opens the content of an item as a pipe for the given MIME type; the caller closes the
    // descriptor. Null when the id is unknown or the item is gone, and also when the core cannot
    // be reached. A descriptor that carries no bytes means the payload could not be fetched.
    ParcelFileDescriptor openItem(String itemId, String mime);

    // Sets the pause: untilEpochMs > 0 pauses until that instant, 0 resumes, a negative value
    // pauses until it is resumed.
    void setPaused(long untilEpochMs);

    // Same as publishText, plus the outcome through the callback.
    void publishTextWithResult(in ClipPayload payload, in IPublishCallback callback);

    // Same as publishBinary, plus the outcome through the callback.
    void publishBinaryWithResult(
            in ClipPayload meta,
            in ParcelFileDescriptor data,
            in IPublishCallback callback);
}
