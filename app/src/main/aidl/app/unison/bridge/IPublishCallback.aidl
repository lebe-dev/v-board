// Contract between the Unison app and the keyboard.
//
// The interface is frozen: methods are only appended at the end, existing methods are never
// changed or removed.

package app.unison.bridge;

import app.unison.bridge.PublishResult;

oneway interface IPublishCallback {
    // Outcome of the publish that carried this callback. Called exactly once.
    void onPublishResult(in PublishResult result);
}
