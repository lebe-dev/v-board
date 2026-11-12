package ru.tinyops.android.vboard

import android.content.Context
import android.content.res.AssetManager
import org.pytorch.executorch.Module
import ru.tinyops.android.vboard.core.gesture.neural.NeuralGestureEngine
import ru.tinyops.android.vboard.core.gesture.neural.SwipeModelSpec
import ru.tinyops.android.vboard.core.gesture.neural.SwipeSequenceModel
import java.io.File

/**
 * Opens the neural slide to type model packed in the assets. The runtime reads the model from a
 * file, so it is copied out of the APK once per installed version of the app.
 */
internal object SwipeModelAssets {
    private const val DIRECTORY = "swipe"
    private const val MODEL = "ru.pte"
    private const val SPEC = "ru.properties"

    fun isPresent(assets: AssetManager): Boolean {
        val files = assets.list(DIRECTORY).orEmpty()
        return MODEL in files && SPEC in files
    }

    /** Null when this build has no model. Blocks and loads a native library: call off the main thread. */
    fun open(context: Context): NeuralGestureEngine? {
        if (!isPresent(context.assets)) return null
        val spec = readSpec(context.assets)
        return NeuralGestureEngine(spec, openModel(context, spec))
    }

    fun readSpec(assets: AssetManager): SwipeModelSpec = assets.open("$DIRECTORY/$SPEC").use(SwipeModelSpec::parse)

    fun openModel(
        context: Context,
        spec: SwipeModelSpec,
    ): SwipeSequenceModel {
        val module = Module.load(extractModel(context).path, Module.LOAD_MODE_MMAP)
        return ExecuTorchSwipeModel(module, spec.numClasses)
    }

    private fun extractModel(context: Context): File =
        AssetFiles.extract(context, "$DIRECTORY/$MODEL", File(context.noBackupFilesDir, DIRECTORY), "ru", "pte")
}
