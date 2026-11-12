package ru.tinyops.android.vboard

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException

/** Copies of assets that a native runtime can only read from a file. Blocks on disk: call off the main thread. */
internal object AssetFiles {
    /**
     * The asset [path] as `directory/name-<install time>.extension`, copied once per installed version of the app,
     * so an update brings its own copy. The copy is written atomically: a process killed halfway leaves no file
     * that would pass for the whole one. Earlier copies of the asset, any file whose name starts with [name], are
     * deleted.
     */
    fun extract(
        context: Context,
        path: String,
        directory: File,
        name: String,
        extension: String,
    ): File {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val target = File(directory, "$name-$installed.$extension")
        if (target.isFile) return target
        directory.mkdirs()
        directory.listFiles { file -> file.name.startsWith(name) }?.forEach(File::delete)
        val file = AtomicFile(target)
        val output = file.startWrite()
        try {
            context.assets.open(path).use { it.copyTo(output) }
            file.finishWrite(output)
        } catch (failure: IOException) {
            file.failWrite(output)
            throw failure
        }
        return target
    }
}
