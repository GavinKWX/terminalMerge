package utils

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The five things [FileOps] needs from the app to read and write terminal config files.
 *
 * `:core` deliberately does not hold a `Context`. Where a `Context` is genuinely a parameter it is
 * passed in (see `helpers/StorageGuard`), but the file helpers moved here are called as
 * `Utils.readFromFile("tms.txt")` from around forty sites, so widening their signatures would
 * ripple across both apps for no gain. A narrow seam instead, the same shape as
 * [iso.HostCertStore] and [emv.EmvHost].
 *
 * The stream methods exist rather than being derived from [filesDir] on purpose: the app's
 * implementation calls `Context.openFileInput` / `openFileOutput(MODE_APPEND)` exactly as the code
 * did before the move, so nothing about file mode or creation semantics changes here.
 */
interface AppFiles {

	/** `Context.getFilesDir()` -- where terminal config and receipt files live. */
	fun filesDir(): File

	/** `<dataDir>/databases/`, with the trailing slash, for the `.db` branch of `deleteFiles`. */
	fun databasesDir(): String

	/** `Context.openFileInput(filename)`. */
	fun openInput(filename: String): InputStream

	/** `Context.openFileOutput(filename, MODE_APPEND)`. */
	fun openOutputAppend(filename: String): OutputStream

	/** `Context.getAssets().open(filename)`. */
	fun openAsset(filename: String): InputStream
}

/**
 * Process-wide holder, registered once per app in `Application.onCreate`.
 *
 * Throws when unregistered, like [iso.CurrentCertStore]. Failing soft here would mean config
 * reads quietly returning nothing, which surfaces much later as a terminal with no TID rather
 * than as the wiring mistake it actually is.
 */
object CurrentFiles : AppFiles {

	@Volatile
	private var backing: AppFiles? = null

	@JvmStatic
	fun register(files: AppFiles) {
		backing = files
	}

	@JvmStatic
	fun isRegistered(): Boolean = backing != null

	private val files: AppFiles
		get() = backing
			?: error("CurrentFiles used before register() -- wire it in Application.onCreate")

	override fun filesDir(): File = files.filesDir()

	override fun databasesDir(): String = files.databasesDir()

	override fun openInput(filename: String): InputStream = files.openInput(filename)

	override fun openOutputAppend(filename: String): OutputStream = files.openOutputAppend(filename)

	override fun openAsset(filename: String): InputStream = files.openAsset(filename)
}
