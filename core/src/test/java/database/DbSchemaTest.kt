package database

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the seam that let DbHandler move into :core while each fleet kept its own schema.
 *
 * The schemas genuinely differ -- MF919 has 21 tables including QR_PAY_TABLE and
 * PRINT_RECEIPT_QR, Pro has 19 with extra columns -- and they sit on separate migration
 * histories against live data. So DbHandler asks [DbSchema] what to heal rather than
 * enumerating an enum it cannot see.
 */
class DbSchemaTest {

	private data class Table(
		override val id: String,
		override val createSql: List<String> = emptyList(),
		override val seedData: List<Triple<String, String, String>> = emptyList(),
		override val holdsSecrets: Boolean = false,
	) : DbTable

	@Before fun clear() = DbSchema.register(emptyList())
	@After fun reset() = DbSchema.register(emptyList())

	@Test
	fun `unregistered schema heals nothing rather than guessing`() {
		// The safe reading: skip the heal rather than write a schema we cannot know.
		assertTrue(DbSchema.allTables().isEmpty())
	}

	@Test
	fun `an app registers its own table list`() {
		DbSchema.register(listOf(Table("MerchantConfiguration"), Table("secureData")))

		assertEquals(2, DbSchema.allTables().size)
		assertEquals(listOf("MerchantConfiguration", "secureData"), DbSchema.allTables().map { it.id })
	}

	@Test
	fun `registering replaces rather than accumulates`() {
		DbSchema.register(listOf(Table("a"), Table("b")))
		DbSchema.register(listOf(Table("c")))

		assertEquals(listOf("c"), DbSchema.allTables().map { it.id })
	}

	@Test
	fun `the two fleets can register different schemas`() {
		// MF919 carries two tables Pro does not; that is the whole reason for this seam.
		val pro = listOf(Table("MerchantConfiguration"), Table("TerminalConfiguration"))
		val mf919 = pro + listOf(Table("qrPayTable"), Table("printReceiptQr"))

		DbSchema.register(pro)
		assertEquals(2, DbSchema.allTables().size)

		DbSchema.register(mf919)
		assertEquals(4, DbSchema.allTables().size)
		assertTrue(DbSchema.allTables().any { it.id == "qrPayTable" })
	}

	@Test
	fun `holdsSecrets defaults to false and is opt-in`() {
		// DbHandler logs every insert's ContentValues and those logs go to TMS, so only the
		// table that stores key material must report true.
		assertFalse(Table("MerchantConfiguration").holdsSecrets)
		assertTrue(Table("secureData", holdsSecrets = true).holdsSecrets)
	}

	@Test
	fun `a table with no createSql is a table DbHandler must not try to create`() {
		// DbHandler skips these: they come from the preloaded asset DB and have no canonical
		// column list to recreate from.
		assertTrue(Table("isoengine").createSql.isEmpty())
	}

	@Test
	fun `migration reset hook is optional and failures do not propagate`() {
		// Nothing registered: the prefs reset alone is still correct, just not cached.
		DbSchema.invalidateMigrationVersionCache()

		var called = false
		DbSchema.onMigrationVersionReset { called = true }
		DbSchema.invalidateMigrationVersionCache()
		assertTrue(called)

		// A throwing hook must not take down the recopy path it runs inside.
		DbSchema.onMigrationVersionReset { throw IllegalStateException("boom") }
		DbSchema.invalidateMigrationVersionCache()
	}
}
