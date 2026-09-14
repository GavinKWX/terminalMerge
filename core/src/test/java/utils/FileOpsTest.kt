package utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The config-file helpers moved to [FileOps] in tranche 3 of the Utils slice.
 *
 * These read and write the files that hold the terminal's TID, MID, acquirer config and last
 * settlement, so a change of shape here is a terminal that comes up unprovisioned. They were
 * untestable before the move because they reached a Context through each app's ServiceHolder --
 * being able to run them against a real directory with a fake asset bundle is the main thing the
 * [AppFiles] seam bought.
 */
class FileOpsTest {

	@get:Rule
	val temp = TemporaryFolder()

	/** Real files on disk, assets held in a map. */
	private class Fake(private val dir: File) : AppFiles {
		val assets = mutableMapOf<String, String>()

		override fun filesDir(): File = dir
		override fun databasesDir(): String = File(dir, "databases").absolutePath + File.separator
		override fun openInput(filename: String): InputStream = File(dir, filename).inputStream()
		override fun openOutputAppend(filename: String): OutputStream =
			FileOutputStream(File(dir, filename), true)

		override fun openAsset(filename: String): InputStream =
			ByteArrayInputStream(
				(assets[filename] ?: throw java.io.FileNotFoundException(filename)).toByteArray()
			)
	}

	private lateinit var fake: Fake

	@Before
	fun setUp() {
		fake = Fake(temp.root)
		// Process-wide and there is no unregister, which is fine here: every test in this class
		// installs its own fake first, and no other test class touches this seam.
		CurrentFiles.register(fake)
	}

	// ---------- existence and size ----------

	@Test
	fun `checkFiles sees only what is in the files dir`() {
		assertFalse(FileOps.checkFiles("tms.txt"))
		temp.newFile("tms.txt")
		assertTrue(FileOps.checkFiles("tms.txt"))
	}

	@Test
	fun `fileSizeInKb truncates to whole KB, so a small file reads as zero`() {
		File(temp.root, "small.txt").writeText("hello")
		assertEquals(0L, FileOps.fileSizeInKb("small.txt"))
		File(temp.root, "big.txt").writeText("x".repeat(3 * 1024))
		assertEquals(3L, FileOps.fileSizeInKb("big.txt"))
	}

	// ---------- read / write round trip ----------

	@Test
	fun `writeToFile appends a line at a time and readFromFile gives them back in order`() {
		FileOps.writeToFile("TID=12345678", "tms.txt")
		FileOps.writeToFile("MID=000000000000001", "tms.txt")
		assertArrayEquals(
			arrayOf("TID=12345678", "MID=000000000000001"),
			FileOps.readFromFile("tms.txt"),
		)
	}

	@Test
	fun `readFromFile returns a single null element for a missing file, not an empty array`() {
		// The trap worth pinning: callers do value[0] and compare against null. Anything that
		// "tidied" this into an empty array would turn every missing-config check into an
		// ArrayIndexOutOfBoundsException at startup.
		val ret = FileOps.readFromFile("absent.txt")
		assertEquals(1, ret.size)
		assertNull(ret[0])
	}

	@Test
	fun `write2File replaces the file rather than appending to it`() {
		FileOps.writeToFile("old", "last.txt")
		FileOps.write2File(arrayOf("new1", "new2"), "last.txt")
		assertArrayEquals(arrayOf("new1", "new2"), FileOps.readFromFile("last.txt"))
	}

	@Test
	fun `deleteFiles removes the file`() {
		FileOps.writeToFile("x", "gone.txt")
		assertTrue(FileOps.checkFiles("gone.txt"))
		FileOps.deleteFiles("gone.txt")
		assertFalse(FileOps.checkFiles("gone.txt"))
	}

	// ---------- absolute paths ----------

	@Test
	fun `readFromFilePath reads an absolute path outside the files dir`() {
		val outside = temp.newFolder("elsewhere")
		val f = File(outside, "note.txt")
		f.writeText("line1\nline2\n")
		assertArrayEquals(arrayOf("line1", "line2"), FileOps.readFromFilePath(f.absolutePath))
	}

	@Test
	fun `readFromFilePath also uses the single-null shape for a missing path`() {
		val ret = FileOps.readFromFilePath(File(temp.root, "nope.txt").absolutePath)
		assertEquals(1, ret.size)
		assertNull(ret[0])
	}

	// ---------- the APK install queue ----------

	@Test
	fun `getInstallApk returns null when nothing is queued`() {
		// Reads value[0] off the single-null shape, so "no queue file" and "empty queue" both
		// come back as null rather than as a crash.
		assertNull(FileOps.getInstallApk())
	}

	@Test
	fun `getInstallApk returns the first queued filename`() {
		FileOps.write2File(arrayOf("app-a.apk", "app-b.apk"), "installApk.txt")
		assertEquals("app-a.apk", FileOps.getInstallApk())
	}

	@Test
	fun `removeInstallApk drops just that entry and keeps the rest`() {
		FileOps.write2File(arrayOf("app-a.apk", "app-b.apk", "app-c.apk"), "installApk.txt")
		FileOps.removeInstallApk("app-b.apk")
		assertArrayEquals(
			arrayOf("app-a.apk", "app-c.apk"),
			FileOps.readFromFile("installApk.txt"),
		)
	}

	@Test
	fun `removeInstallApk on an empty queue does nothing and does not throw`() {
		FileOps.removeInstallApk("app-a.apk")
		assertFalse(FileOps.checkFiles("installApk.txt"))
	}

	// ---------- asset seeding ----------

	@Test
	fun `cpAssetFile seeds a file that does not exist yet`() {
		fake.assets["termInfo.txt"] = "TID=99999999\nMID=1\n"
		FileOps.cpAssetFile("termInfo.txt")
		assertArrayEquals(arrayOf("TID=99999999", "MID=1"), FileOps.readFromFile("termInfo.txt"))
	}

	@Test
	fun `cpAssetFile adds only the keys the existing file is missing`() {
		// This is the behaviour a terminal depends on across an app update: the provisioned TID
		// must survive, and a newly added key must arrive.
		FileOps.writeToFile("TID=12345678", "termInfo.txt")
		fake.assets["termInfo.txt"] = "TID=00000000\nNEWKEY=7\n"

		FileOps.cpAssetFile("termInfo.txt")

		assertArrayEquals(
			arrayOf("TID=12345678", "NEWKEY=7"),
			FileOps.readFromFile("termInfo.txt"),
		)
	}

	@Test
	fun `cpAssetFile does nothing when the line counts already match`() {
		// changeInFile compares line COUNTS, not content. Two files of equal length are treated
		// as in step, so a changed value in an asset never re-seeds. Documented, not fixed --
		// changing it would rewrite provisioned config on the next update.
		FileOps.writeToFile("TID=12345678", "termInfo.txt")
		fake.assets["termInfo.txt"] = "TID=00000000\n"

		FileOps.cpAssetFile("termInfo.txt")

		assertArrayEquals(arrayOf("TID=12345678"), FileOps.readFromFile("termInfo.txt"))
	}

	@Test
	fun `readFromAssetFile is gated on the internal file, not on the asset`() {
		// Its name says assets; its guard checks the files dir. Both callers happen to check the
		// same thing first, so it works -- but reused on its own it silently returns nothing.
		fake.assets["only-asset.txt"] = "A=1\n"
		val ret = FileOps.readFromAssetFile("only-asset.txt")
		assertEquals(1, ret.size)
		assertNull(ret[0])

		FileOps.writeToFile("A=1", "only-asset.txt")
		assertArrayEquals(arrayOf("A=1"), FileOps.readFromAssetFile("only-asset.txt"))
	}
}
