package database

/**
 * The set of tables [DbHandler] should self-heal, registered by whichever app is running.
 *
 * DbHandler recreates any missing table on every connection open. It used to enumerate the
 * app's `DatabaseTables` enum directly; from :core it cannot see that enum, so each app
 * registers its own table list in Application.onCreate -- the same place it registers
 * [helpers.TerminalInfo], and before any database is opened.
 */
object DbSchema {

	@Volatile
	private var tables: List<DbTable> = emptyList()

	@JvmStatic
	fun register(tables: List<DbTable>) {
		this.tables = tables
	}

	/**
	 * Empty until an app registers. DbHandler treats that as "nothing to heal" and leaves the
	 * database untouched, which is the safe reading: better to skip the heal than to guess at
	 * a schema and write the wrong one.
	 */
	@JvmStatic
	fun allTables(): List<DbTable> = tables

	@Volatile
	private var migrationCacheInvalidator: (() -> Unit)? = null

	/**
	 * Registered by the app so [DbHandler] can drop the in-memory MIGRATE_VER copy after it
	 * recopies the preloaded database.
	 *
	 * ServiceHolder caches MIGRATE_VER in a static and only falls back to SharedPreferences when
	 * that static is 0, so resetting prefs alone would be masked by a value already read this
	 * process. ServiceHolder cannot move into :core (it reaches for R, the Application class and
	 * six database types), so it hands DbHandler this one callback instead.
	 */
	@JvmStatic
	fun onMigrationVersionReset(hook: () -> Unit) {
		migrationCacheInvalidator = hook
	}

	/** No-op when nothing is registered; the prefs reset alone is still correct, just not cached. */
	internal fun invalidateMigrationVersionCache() {
		try { migrationCacheInvalidator?.invoke() } catch (_: Exception) { }
	}
}
