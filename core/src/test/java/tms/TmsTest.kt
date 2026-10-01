package tms

import android.content.Context
import android.content.ContextWrapper
import helpers.HelperLog
import org.junit.Assert.assertEquals
import org.junit.Test

/** The task dispatch in [Tms.applyTasks]: which download runs, and what lands in batch info. */
class TmsTest {

	private val context: Context = ContextWrapper(null)
	private val log = HelperLog("s", false, "", "t", "t", "t")

	private class Fake(val merchantOk: Boolean = true) : TmsHost {
		val calls = mutableListOf<String>()
		override fun appName(context: Context) = "app"
		override fun mcVersion() = "1"
		override fun terminalConfigValue(attr: String) = ""
		override fun merchantConfigValue(attr: String) = "v-$attr"
		override fun downloadMerchantConfig(log: HelperLog, context: Context): Boolean {
			calls += "merchant"; return merchantOk
		}
		override fun downloadTerminalConfig(log: HelperLog, context: Context): Boolean {
			calls += "terminal"; return true
		}
		override fun downloadInjectionKey(log: HelperLog, context: Context): Boolean {
			calls += "key"; return true
		}
	}

	private fun run(tasks: List<String?>, host: Fake): List<String> {
		val writes = mutableListOf<String>()
		Tms.applyTasks(tasks, host, log, context) { v, t, s -> writes += "$s/$t=$v" }
		return writes
	}

	@Test fun `each task runs its own download, in order`() {
		val host = Fake()
		run(listOf("InjectKeyUpdate", "TerminalConfigUpdate", "Unknown", "MerchantConfigUpdate"), host)
		assertEquals(listOf("key", "terminal", "merchant"), host.calls)
	}

	@Test fun `a merchant update copies the acquirer fields to both schemes`() {
		val writes = run(listOf("MerchantConfigUpdate"), Fake())
		val one = { s: String ->
			listOf("$s/mid=v-AcqMid", "$s/tid=v-AcqTid", "$s/isoTpduHeader=v-TPDU",
				"$s/isoTpduHeaderTle=v-TPDU", "$s/nii=v-NII", "$s/niiTle=v-NII")
		}
		assertEquals(one("visam") + one("mccs"), writes)
	}

	@Test fun `a failed merchant download writes nothing`() {
		val writes = run(listOf("MerchantConfigUpdate"), Fake(merchantOk = false))
		assertEquals(emptyList<String>(), writes)
	}

	@Test fun `a null entry is skipped, not fatal`() {
		val host = Fake()
		run(listOf("TerminalConfigUpdate", null, "InjectKeyUpdate"), host)
		assertEquals(listOf("terminal", "key"), host.calls)
	}
}
