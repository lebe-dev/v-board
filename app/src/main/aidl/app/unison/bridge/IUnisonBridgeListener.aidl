// Contract between the Unison app and the keyboard.
//
// The interface is frozen: methods are only appended at the end, existing methods are never
// changed or removed.

package app.unison.bridge;

import app.unison.bridge.BridgeState;
import app.unison.bridge.ClipSummary;

oneway interface IUnisonBridgeListener {
    // Another device offered an item.
    void onRemoteItem(in ClipSummary summary);

    // The pause state or the number of online devices changed.
    void onStateChanged(in BridgeState state);
}
