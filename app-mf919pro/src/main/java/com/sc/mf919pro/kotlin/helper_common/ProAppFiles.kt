package com.sc.mf919pro.kotlin.helper_common

import android.content.Context
import utils.AppFiles
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * This app's side of [AppFiles].
 *
 * Every call goes through the same Context accessors the file helpers used before they moved to
 * `:core`, including `MODE_APPEND` on the output stream, so nothing about where files land or how
 * they are created changes. Registered once per process in MF919.onCreate.
 */
object ProAppFiles : AppFiles {

	override fun filesDir(): File = ServiceHolder.getContext().filesDir

	override fun databasesDir(): String =
		ServiceHolder.getContext().applicationInfo.dataDir + "/databases/"

	override fun openInput(filename: String): InputStream =
		ServiceHolder.getContext().openFileInput(filename)

	override fun openOutputAppend(filename: String): OutputStream =
		ServiceHolder.getContext().openFileOutput(filename, Context.MODE_APPEND)

	override fun openAsset(filename: String): InputStream =
		ServiceHolder.getContext().assets.open(filename)
}
