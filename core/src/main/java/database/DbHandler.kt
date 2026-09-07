package database

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import enums.EnumLogFileName
import helpers.HelperLog
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Type
import java.security.InvalidParameterException
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties


class DbHandler(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
	companion object {
		private val DATABASE_VERSION = 1
		private val DATABASE_NAME = "isoengine_gobiz.db"
		private var sInstance: DbHandler? = null

		// Shared instance so type-adapter caches are reused across calls
		val gson: Gson by lazy { Gson() }

		// memberProperties is expensive on first touch — cache the property list per model class
		val propertyCache = ConcurrentHashMap<KClass<*>, Collection<KProperty1<out Any, *>>>()

		@Synchronized
		fun getInstance(context: Context): DbHandler {
			// Use the application context, which will ensure that you
			// don't accidentally leak an Activity's context.
			// See this article for more information: http://bit.ly/6LRzfx
			if (sInstance == null) {
				val appContext = context.applicationContext
				val dbHandler = DbHandler(appContext)
				dbHandler.copyPreloadedDatabaseIfNeeded(appContext)
				sInstance = dbHandler
			}
			return sInstance!!
		}
	}

	private fun copyPreloadedDatabaseIfNeeded(context: Context) {
		val dbFile = context.getDatabasePath(DATABASE_NAME)
		android.util.Log.i("DbHandler", "copyPreloadedDatabaseIfNeeded: exists=${dbFile.exists()} size=${dbFile.length()}")

		// An empty/corrupt DB shell (e.g. created by the framework before the asset copy ran,
		// or a partial copy) would block the asset copy forever and leave every table missing
		// — see TerminaDbException "no such table" incident. Remove it so the copy can run.
		if (dbFile.exists() && !isDatabaseUsable(dbFile.path)) {
			android.util.Log.w("DbHandler", "DB file exists but has no tables — deleting and recopying from assets")
			try {
				val sbLog = HelperLog.init("DbService - Preload")
				HelperLog.appendLine(sbLog, "DB file exists but has no tables — deleting and recopying from assets")
				HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
			} catch (_: Exception) { /* file logging may not be ready this early */ }
			File(dbFile.path + "-wal").delete()
			File(dbFile.path + "-shm").delete()
			File(dbFile.path + "-journal").delete()
			dbFile.delete()
		}

		if (!dbFile.exists()) {
			dbFile.parentFile?.mkdirs()
			context.assets.open(DATABASE_NAME).use { inputStream ->
				FileOutputStream(dbFile).use { outputStream ->
					inputStream.copyTo(outputStream)
				}
			}
			android.util.Log.i("DbHandler", "Preloaded DB copied from assets: size=${dbFile.length()}")
			// Placed here, on the actual copy, rather than only in the corrupt-DB branch
			// above: a DB deleted by a data clear (or by recovery tooling) also leaves a stale
			// MIGRATE_VER behind, and that path does not go through the corrupt branch at all.
			// On a genuinely fresh install the counter is already 0, so this is a no-op there.
			resetMigrationVersionForRecopy(context)
		}
	}

	/**
	 * D4 / F9 — the migration counter must not outlive the database it describes.
	 *
	 * `MIGRATE_VER` lives in SharedPreferences, so it survives a DB wipe. After a recopy the
	 * freshly-laid asset DB is at the schema the asset shipped with, but the counter still claims
	 * every migration has run, so the whole chain is skipped. On MF919 that is exactly how two
	 * tables ended up permanently missing. Resetting to 0 makes the chain replay against the new
	 * file.
	 *
	 * **Safe on Pro specifically because every migration here is additive.** `Migration1001`/`1002`
	 * add columns, `Migration1004` creates tables; `grep -rn "DROP TABLE" .../migration/` is empty.
	 * MF919 could not take this fix as-is — its `Migration2101` dropped `ReceiptUpload`
	 * unconditionally, so replaying the chain there would have destroyed un-uploaded receipts.
	 * **Re-run that grep before assuming this stays safe after any new migration is added.**
	 *
	 * Uses SharedPreferences directly rather than `ServiceHolder.setMigrationVersion`: this runs
	 * from `getInstance` during early startup, potentially before `Helper.Initialize`.
	 */
	private fun resetMigrationVersionForRecopy(context: Context) {
		try {
			val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
			val previous = prefs.getInt("MIGRATE_VER", 0)
			prefs.edit().putInt("MIGRATE_VER", 0).commit()   // commit: the process may die before apply() flushes
			// Drop the in-memory copy too: ServiceHolder caches MIGRATE_VER in a static and only
			// falls back to prefs when that static is 0, so without this a value already read
			// this process would mask the reset.
			DbSchema.invalidateMigrationVersionCache()
			android.util.Log.w("DbHandler", "DB recopied - MIGRATE_VER reset $previous -> 0 so migrations replay")
			try {
				val sbLog = HelperLog.init("DbService - Preload")
				HelperLog.appendLine(sbLog, "MIGRATE_VER reset for recopy", "$previous -> 0")
				HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
			} catch (_: Exception) { /* file logging may not be ready this early */ }
		} catch (e: Exception) {
			android.util.Log.e("DbHandler", "MIGRATE_VER reset failed: $e")
		}
	}

	// A usable DB has at least one user table; an empty shell or unopenable file does not.
	private fun isDatabaseUsable(path: String): Boolean = try {
		SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
			db.rawQuery(
				"SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'android_%' AND name NOT LIKE 'sqlite_%'",
				null
			).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) > 0 }
		}
	} catch (e: Exception) {
		false
	}

	// Called when the database connection is being configured.
	// Configure database settings for things like foreign key support, write-ahead logging, etc.
	override fun onConfigure(db: SQLiteDatabase) {
		super.onConfigure(db)
		db.setForeignKeyConstraintsEnabled(true)

		// ADR-005 Option A — no WAL on the terminal.
		// WAL keeps committed data in a -wal sidecar until a checkpoint folds it back in; if the
		// process dies or /data fills before that, those commits are gone. The Aisino A99 lost
		// ~76 transactions / RM 3,135.60 to exactly this. WAL was never a decision here — it was
		// bundled into the DB revamp (8fd3210) and no commit has ever mentioned SQLITE_BUSY, so
		// it is not paying for any measured contention. Lock contention is handled by
		// runWithDbLockRetry + busy_timeout below instead.
		//
		// Order is load-bearing: checkpoint FIRST so anything sitting in an existing -wal is
		// folded into the main DB, and only then switch journal mode. Switching first would
		// discard uncheckpointed commits.
		try {
			db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
		} catch (e: Exception) {
			android.util.Log.w("DbHandler", "wal_checkpoint failed (likely no -wal present): $e")
		}
		db.rawQuery("PRAGMA journal_mode = DELETE", null).use { it.moveToFirst() }
		// DELETE mode defaults synchronous to FULL — set it explicitly so an fsync at commit
		// is a stated guarantee rather than an inherited default.
		db.execSQL("PRAGMA synchronous = FULL")
		// Wait up to 3s before throwing SQLITE_BUSY instead of failing immediately
		db.rawQuery("PRAGMA busy_timeout = 3000", null).use { it.moveToFirst() }

		// F6b — DELETE frees pages inside the file but never hands them back to the filesystem,
		// so a table that grew and was cleaned still occupies the disk. INCREMENTAL lets
		// reclaimFreePages() return them during housekeeping.
		//
		// Two traps: (1) auto_vacuum can only be *changed* on an empty DB or via a full VACUUM,
		// so on an existing file the setting must be applied by VACUUM once; (2) under WAL it
		// silently does nothing — that is why this only became worth doing after WAL was removed
		// (ADR-005). Verify with `PRAGMA auto_vacuum` (2 = INCREMENTAL), never assume.
		try {
			val mode = db.rawQuery("PRAGMA auto_vacuum", null).use { c ->
				if (c.moveToFirst()) c.getInt(0) else 0
			}
			if (mode != 2) {
				db.execSQL("PRAGMA auto_vacuum = INCREMENTAL")
				// Rewrites the file to adopt the new mode. Only ever runs once per device.
				db.execSQL("VACUUM")
				android.util.Log.i("DbHandler", "auto_vacuum was $mode, set to INCREMENTAL via VACUUM")
			}
		} catch (e: Exception) {
			// Never fatal — a DB that cannot be vacuumed still works, it just keeps its free pages.
			android.util.Log.w("DbHandler", "auto_vacuum setup skipped: $e")
		}
	}

	/**
	 * Returns free pages to the filesystem. Safe to call repeatedly; a no-op when there is
	 * nothing to reclaim or when auto_vacuum is not INCREMENTAL. Never throws.
	 */
	fun reclaimFreePages(): Int {
		return try {
			val before = pageCount()
			writableDatabase.execSQL("PRAGMA incremental_vacuum")
			val after = pageCount()
			(before - after).coerceAtLeast(0)
		} catch (e: Exception) {
			android.util.Log.w("DbHandler", "incremental_vacuum failed: $e")
			0
		}
	}

	private fun pageCount(): Int = try {
		readableDatabase.rawQuery("PRAGMA page_count", null).use { c ->
			if (c.moveToFirst()) c.getInt(0) else 0
		}
	} catch (e: Exception) {
		0
	}

	/**
	 * Every table in [DbTable] is created here if missing, on every connection open.
	 *
	 * Heal-on-first-touch is not enough: a table nothing writes to (DenominationUpload) stays
	 * missing and silently broken, and a read path returns emptyList() rather than erroring, so
	 * "missing table" is indistinguishable from "no data". Doing it in onOpen also means adding a
	 * new DbTable entry no longer requires the asset DB to be regenerated.
	 */
	override fun onOpen(db: SQLiteDatabase) {
		super.onOpen(db)
		ensureAllTablesExist(db)
	}

	/** Creates [tableName] from its canonical createSql if absent. Never throws. */
	fun ensureTableExists(db: SQLiteDatabase, tableName: DbTable): Boolean {
		return try {
			val exists = db.rawQuery(
				"SELECT count(*) FROM sqlite_master WHERE type=? AND name=?",
				arrayOf("table", tableName.id)
			).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) > 0 }

			if (!exists) {
				val sbLog = HelperLog.init("DbService - SCHEMA HEAL")
				HelperLog.appendLine(sbLog, "Table missing, creating", tableName.id)
				db.execSQL("CREATE TABLE ${tableName.id} (${tableName.createSql.joinToString(", ")})")
				seedTableDefaults(db, tableName)
				HelperLog.appendLine(sbLog, "Table created and seeded", tableName.id)
				HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
				android.util.Log.w("DbHandler", "SCHEMA HEAL: created missing table ${tableName.id}")
			}
			true
		} catch (e: Exception) {
			// A failure here must never take the app down — the caller's own error handling
			// (insertToDb's self-heal, or a neutral return) still applies.
			android.util.Log.e("DbHandler", "ensureTableExists(${tableName.id}) failed: $e")
			try {
				val sbLog = HelperLog.init("DbService - SCHEMA HEAL")
				HelperLog.appendLine(sbLog, "DbException", e.toString())
				HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
			} catch (_: Exception) { /* logging must not mask the original failure */ }
			false
		}
	}

	/** Runs [ensureTableExists] across every declared table. Never throws. */
	fun ensureAllTablesExist(db: SQLiteDatabase): Boolean {
		var allOk = true
		for (table in DbSchema.allTables()) {
			if (!ensureTableExists(db, table)) allOk = false
		}
		return allOk
	}

	// Called when the database is created for the FIRST time.
	// If a database already exists on disk with the same DATABASE_NAME, this method will NOT be called.
	override fun onCreate(db: SQLiteDatabase) { }

	// Called when the database needs to be upgraded.
	// This method will only be called if a database already exists on disk with the same DATABASE_NAME,
	// but the DATABASE_VERSION is different than the version of the database that exists on disk.
	override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
		if (oldVersion != newVersion) {
			// Simplest implementation is to drop all old tables and recreate them
			// db.execSQL("DROP TABLE IF EXISTS " + TABLE_POSTS);
			onCreate(db);
		}
	}

	fun checkTableExist(tableName: DbTable): Boolean {
		val sbLog = HelperLog.init("DbService - Check Table Exist")

		return try {
			val count = readableDatabase.rawQuery("SELECT count(*) FROM sqlite_master WHERE type=? AND name=?", arrayOf("table", tableName.id))
				.use { cursor ->
					if (!cursor.moveToFirst()) 0
					else cursor.getInt(0)
				}
			count > 0
		} catch (e: Exception) {
			HelperLog.appendLine(sbLog, "Tables Not Exist")
			HelperLog.appendLine(sbLog, "DbException", e.toString())
			HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
			false
		}
	}

	fun checkTableColumnExist(tableName: DbTable, column: String): Boolean {
		val sbLog = HelperLog.init("DbService - Check Table Column Exist")

		HelperLog.appendLine(sbLog, "PRAGMA Column($column) from Table(${tableName.id})")
		readableDatabase.rawQuery("PRAGMA table_info(${tableName.id})", null).use { cursor ->
			val nameIdx = cursor.getColumnIndex("name")
			if (nameIdx < 0) return false
			HelperLog.appendLine(sbLog, "Column Found in ${tableName.id}")

			while (cursor.moveToNext()) {
				if (cursor.getString(nameIdx) == column) return true
			}
		}

		// If not found, add it
		return try {
			HelperLog.appendLine(sbLog, "Column($column) Not Exist")
			HelperLog.appendLine(sbLog, "Adding to the Tables(${tableName.id})")
			writableDatabase.execSQL("ALTER TABLE ${tableName.id} ADD COLUMN $column TEXT")
			false
		} catch (e: Exception) {
			HelperLog.appendLine(sbLog, "DbException", e.toString())
			HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
			false
		}
	}

	/*
	* Use for COUNT / SUM / AVG, which return a single row single column
	* */
	fun executeScalar(sql: String, parms: Array<String>): Int {
		val sbLog = HelperLog.init("DbService")
		HelperLog.appendLine(sbLog, "Function", "ExecuteScalar")

		return try {
			HelperLog.appendLine(sbLog, "mDb.rawQuery(sql, parms)")
			readableDatabase.rawQuery(sql, parms).use { cursor ->
				if (!cursor.moveToFirst()) 0
				else cursor.getInt(0)
			}
		} catch (e: Exception) {
			HelperLog.appendLine(sbLog, "DbException", e.toString())
			HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
			0
		}
	}

	/**
	 * Empties [tableName]. Returns whether the table ended up empty; **never throws.**
	 *
	 * This used to be the only write path in this class that rethrew — insert self-heals, and
	 * update/select/scalar/custom-SQL all return a neutral value — while all 10 repo wrappers
	 * were written `truncateTables(...); return true`, so a failure could only surface as a
	 * process kill. On a missing table it now *heals instead of reporting*: the table is
	 * recreated and reseeded, so the caller's following inserts still succeed.
	 *
	 * Deliberately does NOT rethrow on a full disk, unlike insertToDb's carve-out: truncate is
	 * value-removing, so leftover stale rows are not a money-loss and never justify killing the
	 * process mid-transaction.
	 */
	fun truncateTables(tableName: DbTable): Boolean {
		val sbLog = HelperLog.init("DbService")
		HelperLog.appendLine(sbLog, "Function", "Truncating Tables")

		return try {
			runWithDbLockRetry {
				val db: SQLiteDatabase = writableDatabase
				db.transaction {
					HelperLog.appendLine(sbLog, "DbService database connection establish")
					val rawSql = "DELETE FROM ${tableName.id}"
					HelperLog.appendLine(sbLog, "rawSQL", rawSql)
					execSQL(rawSql)
				}
			}
			true
		} catch (e: Exception) {
			val msg = e.message.orEmpty()
			HelperLog.appendLine(sbLog, "DbException", e.toString())

			if (msg.contains("no such table")) {
				// Heal rather than report: recreate + reseed so the caller can carry on.
				HelperLog.appendLine(sbLog, "SELF-HEAL", "recreating missing ${tableName.id}")
				val healed = try {
					writableDatabase.transaction {
						execSQL("CREATE TABLE IF NOT EXISTS ${tableName.id} (${tableName.createSql.joinToString(", ")})")
						seedTableDefaults(this, tableName)
					}
					true
				} catch (heal: Exception) {
					HelperLog.appendLine(sbLog, "SELF-HEAL failed", heal.toString())
					false
				}
				HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
				// A freshly created table is empty, which is what the caller asked for.
				healed
			} else {
				HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
				false
			}
		}
	}

	fun isDbLocked(e: SQLiteException): Boolean {
		val msg = e.message?.lowercase() ?: return false
		return msg.contains("database is locked") ||
				msg.contains("database is busy") ||
				msg.contains("sqlitE_busy".lowercase())
	}

	inline fun <T> runWithDbLockRetry(
		maxRetries: Int = 5,
		initialDelayMs: Long = 30,
		factor: Double = 2.0,
		block: () -> T
	): T {
		var attempt = 0
		var delay = initialDelayMs

		while (true) {
			try {
				return block()
			} catch (e: SQLiteException) {
				if (!isDbLocked(e) || attempt >= maxRetries) throw e
				try { Thread.sleep(delay) } catch (_: InterruptedException) {}
				attempt++
				delay = (delay * factor).toLong().coerceAtMost(1000L)
			}
		}
	}

	fun dropAndRecreateTable(db: SQLiteDatabase, tableName: DbTable) {
		db.execSQL("DROP TABLE IF EXISTS ${tableName.id}")
		val querySeperator = ", "
		db.execSQL("CREATE TABLE ${tableName.id} (${tableName.createSql.joinToString(querySeperator)})") // you MUST have canonical CREATE SQL
		seedTableDefaults(db, tableName)
	}

	/**
	 * Re-insert the default rows a table ships with in the preloaded asset DB.
	 * Idempotent: a (tag, subtag) pair that already exists is left untouched, so this is
	 * safe to run after the pending insert in the self-heal path. Without this, a healed
	 * IsoBatchInfo/IsoBatchLongInfo comes back empty and ISO processing breaks.
	 */
	fun seedTableDefaults(db: SQLiteDatabase, tableName: DbTable) {
		if (tableName.seedData.isEmpty()) return
		db.compileStatement(
			"INSERT INTO ${tableName.id} (tag, subtag, value) " +
				"SELECT ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM ${tableName.id} WHERE tag = ? AND subtag = ?)"
		).use { stmt ->
			for ((tag, subtag, value) in tableName.seedData) {
				stmt.clearBindings()
				stmt.bindString(1, tag)
				stmt.bindString(2, subtag)
				stmt.bindString(3, value)
				stmt.bindString(4, tag)
				stmt.bindString(5, subtag)
				stmt.executeInsert()
			}
		}
	}

	inline fun <reified T : Any> SQLiteDatabase.doInsert(sbLog: StringBuilder, tableName: DbTable, modelObject: T) {
		val values = ContentValues()
		@Suppress("UNCHECKED_CAST")
		val props = propertyCache.getOrPut(T::class) { T::class.memberProperties } as Collection<KProperty1<T, *>>
		for (prop in props) {
			val name = prop.name
			if (name == "id") continue // IMPORTANT for AUTOINCREMENT PK

			when (val insertValue = prop.get(modelObject)) {
				is String -> values.put(name, insertValue)
				is Boolean -> values.put(name, if (insertValue) "1" else "0")
				is Double -> values.put(name, insertValue)
				is Float -> values.put(name, insertValue)
				is Byte -> values.put(name, insertValue)
				is ByteArray -> values.put(name, insertValue)
				is Long -> values.put(name, insertValue)
				is Short -> values.put(name, insertValue)
				null -> values.put(name, "")
				else -> throw InvalidParameterException()
			}
		}

		HelperLog.appendLine(sbLog, "ContentValues: ", redactSecureValues(tableName, values))
		insertOrThrow(tableName.id, null, values)
	}

	/**
	 * Redact key material before a ContentValues row reaches the log.
	 *
	 * The `secureData` table is the terminal's key store: rows carry `tag` in
	 * (eKeyLeft, eKeyRight, eTmkKey, eTpkKey, eWakKey, eWekKey, eTmkId) with the key itself in
	 * `value`. Logging that row verbatim put live key material into TerminaLog, which is uploaded
	 * to TMS -- measured on device: a completed sale logged eKeyLeft and eKeyRight in full, and
	 * those two XOR together to form the TMK, so logging both IS logging the TMK.
	 *
	 * Keeps tag/subtag (needed to debug key sync) and reports only the value's length. Generic on
	 * purpose: this is the single choke point every insert/update funnels through, so no caller
	 * has to remember.
	 */
	@PublishedApi
	internal fun redactSecureValues(tableName: DbTable, values: ContentValues): String {
		if (!tableName.holdsSecrets) return values.toString()
		val copy = ContentValues(values)
		val raw = copy.get("value")?.toString()
		if (!raw.isNullOrEmpty()) {
			copy.put("value", "<redacted:${raw.length} chars>")
		}
		return copy.toString()
	}

	/**
	 * True when the exception is a full disk or an I/O error, rather than a schema problem.
	 *
	 * This is the D3a carve-out. The self-heal branches below both write schema — and the
	 * `has no column named` branch **drops the table first**. On a full disk the CREATE that
	 * follows a DROP is exactly the write most likely to fail, which would turn "could not insert
	 * one row" into "lost the whole table". A full disk is never a schema fault, so it must never
	 * reach the healer.
	 */
	fun isDiskFullOrIoError(e: SQLiteException): Boolean {
		if (e is android.database.sqlite.SQLiteFullException) return true
		if (e is android.database.sqlite.SQLiteDiskIOException) return true
		val msg = e.message?.lowercase() ?: return false
		return msg.contains("disk is full") ||
				msg.contains("database or disk is full") ||
				msg.contains("disk i/o error") ||
				msg.contains("sqlite_full") ||
				msg.contains("enospc")
	}

	/**
	 * Inserts [modelObject] into [tableName]. **Returns whether the row was actually persisted.**
	 *
	 * Previously returned `Unit`, so a caller could not tell a successful write from a swallowed
	 * failure — and every repo wrapper answered `true` regardless. That is how a card gets charged
	 * and a receipt printed for a transaction the terminal has no record of (Aisino).
	 *
	 * Disk-full/I/O errors are **rethrown**, deliberately, and are the one case that still throws:
	 * losing a financial record silently is worse than a visible failure, and the healer must not
	 * run (see [isDiskFullOrIoError]). Callers on the money path must catch this — the StorageGuard
	 * preflight is what stops it being reached in the first place.
	 */
	inline fun <reified T : Any> insertToDb(tableName: DbTable, modelObject: T): Boolean {
		val sbLog = HelperLog.init("DbService")
		HelperLog.appendLine(sbLog, "Function", "Insert to Database")
		HelperLog.appendLine(sbLog, "DbService database connection establish")

		try {
			runWithDbLockRetry {
				writableDatabase.transaction {
					doInsert(sbLog, tableName, modelObject)
				}
			}
			return true
		} catch (e: SQLiteException) {
			e.printStackTrace()
			val msg = e.message.orEmpty()
			HelperLog.appendLine(sbLog, "DbException", e.toString())

			// D3a — a full disk is not a schema fault. Never heal, never drop; fail loudly.
			if (isDiskFullOrIoError(e)) {
				HelperLog.appendLine(sbLog, "DISK FULL / IO ERROR", "self-heal skipped, insert failed")
				HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
				throw e
			}

			// The self-heal itself can fail; previously that propagated uncaught out of this
			// function, which is a crash on the money path. Report it instead.
			return try {
				if (msg.contains("no such table")) {
					HelperLog.appendLine(sbLog, "Fail Safe auto recreate table and insert")
					writableDatabase.transaction {
						val querySeperator = ", "
						execSQL("CREATE TABLE ${tableName.id} (${tableName.createSql.joinToString(querySeperator)})") // you MUST have canonical CREATE SQL
						doInsert(sbLog, tableName, modelObject)
						// restore the default rows the asset DB ships (skips the row just inserted)
						seedTableDefaults(this, tableName)
					}
					HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
					true
				} else if (msg.contains("has no column named")) {
					HelperLog.appendLine(sbLog, "Fail Safe Auto drop corrupted table and recreate")
					writableDatabase.transaction {
						dropAndRecreateTable(this, tableName)
						doInsert(sbLog, tableName, modelObject)
					}
					HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
					true
				} else {
					HelperLog.appendLine(sbLog, "Unhandled DbException, insert failed")
					HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
					false
				}
			} catch (heal: SQLiteException) {
				HelperLog.appendLine(sbLog, "Self-heal failed", heal.toString())
				HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
				// A disk-full discovered during the heal is still a disk-full.
				if (isDiskFullOrIoError(heal)) throw heal
				false
			}
		}
	}

	fun updateTableValue(
		tableName: DbTable,
		modelObject: MutableMap<Any, Any>,
		clauseObject: MutableMap<Any, Any>
	): Int {
		val sbLog = HelperLog.init("DbService")
		HelperLog.appendLine(sbLog, "Function", "Update Table Value")
		HelperLog.appendLine(sbLog, "DbService database connection establish")

		return try {
			runWithDbLockRetry {
				writableDatabase.transaction {
					val values = ContentValues()
					modelObject.forEach { (key, value) ->
						when (value) {
							is Int -> values.put(key.toString(), value)
							is String -> values.put(key.toString(), value)
							is Boolean -> values.put(key.toString(), value)
							is Double -> values.put(key.toString(), value)
							is Float -> values.put(key.toString(), value)
							is Byte -> values.put(key.toString(), value)
							is ByteArray -> values.put(key.toString(), value)
							is Long -> values.put(key.toString(), value)
							is Short -> values.put(key.toString(), value)
							else -> throw InvalidParameterException()
						}
					}
					HelperLog.appendLine(sbLog, "ContentValues: ", redactSecureValues(tableName, values))

					val whereClause = clauseObject.keys.joinToString(" AND ") { "$it = ?" }
					val clauseArray = clauseObject.values.map { v ->
						when (v) {
							is Boolean -> if (v) "1" else "0"
							else -> v.toString()
						}
					}.toTypedArray()

					HelperLog.appendLine(sbLog, "tables: ", tableName.id)
					HelperLog.appendLine(sbLog, "Where Statement: ", whereClause)
					HelperLog.appendLine(sbLog, "Clause Value: ", clauseArray.joinToString())
					val result = update(tableName.id, values, whereClause, clauseArray)
					result
				}
			}
		} catch (e: Exception) {
			e.printStackTrace()
			HelperLog.appendLine(sbLog, "DbException", e.toString())
			HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
			0
		}
	}

	@SuppressLint("Range")
	inline fun <reified T : Any> selectSingleData(sql: String, parms: Array<String>): T? {
		val sbLog = HelperLog.init("DbService")
		HelperLog.appendLine(sbLog, "Function", "SelectSingle")
		val gson = DbHandler.gson

		return try {
			HelperLog.appendLine(sbLog, "mDb.rawQuery($sql, parms)")
			readableDatabase.rawQuery(sql, parms).use { cursor ->
				if (!cursor.moveToFirst()) return null

				// Map the row straight into a JsonObject — no intermediate Map or JSON string
				val jsonObject = JsonObject()
				for (col in cursor.columnNames) {
					val idx = cursor.getColumnIndex(col)
					if (idx >= 0) {
						if (cursor.isNull(idx)) jsonObject.add(col, JsonNull.INSTANCE)
						else jsonObject.addProperty(col, cursor.getString(idx))
					}
				}

				gson.fromJson(jsonObject, T::class.java)
			}
		} catch (e: Exception) {
			HelperLog.appendLine(sbLog, "DbException", e.toString())
			HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
			null
		}
	}

	@SuppressLint("Range")
	inline fun <reified T : Any> selectListData(
		typeToken: Type,
		sql: String,
		parms: Array<String>
	): List<T> {
		val sbLog = HelperLog.init("DbService")
		HelperLog.appendLine(sbLog, "Function", "SelectList")
		val gson = DbHandler.gson

		return try {
			HelperLog.appendLine(sbLog, "mDb.rawQuery($sql, parms)")
			// Map rows straight into a JsonArray — no intermediate Map list or JSON string,
			// and no full-result-set copy held in the log buffer
			val jsonArray = JsonArray()
			readableDatabase.rawQuery(sql, parms).use { cursor ->
				while (cursor.moveToNext()) {
					val row = JsonObject()
					for (col in cursor.columnNames) {
						val idx = cursor.getColumnIndex(col)
						if (idx >= 0) {
							when (cursor.getType(idx)) {
								Cursor.FIELD_TYPE_NULL -> row.add(col, JsonNull.INSTANCE)
								Cursor.FIELD_TYPE_INTEGER -> row.addProperty(col, cursor.getLong(idx))
								Cursor.FIELD_TYPE_FLOAT -> row.addProperty(col, cursor.getDouble(idx))
								Cursor.FIELD_TYPE_BLOB -> row.add(col, gson.toJsonTree(cursor.getBlob(idx)))
								else -> row.addProperty(col, cursor.getString(idx))
							}
						}
					}
					jsonArray.add(row)
				}
			}
			HelperLog.appendLine(sbLog, "rowCount", jsonArray.size().toString())

			gson.fromJson(jsonArray, typeToken) ?: emptyList()
		} catch (e: Exception) {
			HelperLog.appendLine(sbLog, "DbException", e.toString())
			HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
			emptyList()
		}
	}

	fun runningCustomSql(sqlString: String, parms: Array<Any>): Int {
		val sbLog = HelperLog.init("DbService")
		HelperLog.appendLine(sbLog, "Function", "Running Custom SQL Scripts")

		return try {
			writableDatabase.transaction {
				HelperLog.appendLine(sbLog, "rawSQL", sqlString)
				compileStatement(sqlString).use { stmt ->
					parms.forEachIndexed { index, arg ->
						val i = index + 1
						when (arg) {
							null -> stmt.bindNull(i)
							is String -> stmt.bindString(i, arg)
							is Int -> stmt.bindLong(i, arg.toLong())
							is Long -> stmt.bindLong(i, arg)
							is Float -> stmt.bindDouble(i, arg.toDouble())
							is Double -> stmt.bindDouble(i, arg)
							is ByteArray -> stmt.bindBlob(i, arg)
							is Boolean -> stmt.bindLong(i, if (arg) 1 else 0)
							else -> throw IllegalArgumentException("Unsupported bind type: ${arg::class}")
						}
					}
					stmt.executeUpdateDelete()
				}
			}
		} catch (e: Exception) {
			HelperLog.appendLine(sbLog, "DbException", e.toString())
			HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
			0
		}
	}
}
