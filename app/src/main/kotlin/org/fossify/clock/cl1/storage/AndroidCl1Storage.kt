@file:Suppress("LongMethod", "MagicNumber")

package org.fossify.clock.cl1.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.fossify.clock.cl1.Cl1Description
import org.fossify.clock.cl1.Cl1Payload
import org.fossify.clock.cl1.engine.Cl1DiscoverySnapshot
import org.fossify.clock.cl1.engine.Cl1EventIssueState
import org.fossify.clock.cl1.engine.Cl1RelationState
import org.fossify.clock.cl1.provider.Cl1EventRef

class AndroidCl1Storage private constructor(
    context: Context,
) : SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION),
    Cl1Storage {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE relations (
                slot_hex TEXT PRIMARY KEY NOT NULL,
                state TEXT NOT NULL,
                source_event_id INTEGER,
                source_calendar_id INTEGER,
                mirror_event_id INTEGER,
                mirror_calendar_id INTEGER,
                source_title TEXT,
                mirror_title TEXT,
                source_start_millis INTEGER,
                mirror_start_millis INTEGER,
                expected_revision_hex TEXT,
                actual_revision_hex TEXT,
                needs_revision_refresh INTEGER NOT NULL,
                detail TEXT,
                last_seen_millis INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE bindings (
                binding_key TEXT PRIMARY KEY NOT NULL,
                slot_hex TEXT,
                role TEXT NOT NULL,
                event_id INTEGER NOT NULL,
                calendar_id INTEGER NOT NULL,
                start_millis INTEGER NOT NULL,
                last_seen_millis INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX bindings_event_ref ON bindings(calendar_id, event_id)"
        )
        database.execSQL(
            """
            CREATE TABLE event_issues (
                event_key TEXT PRIMARY KEY NOT NULL,
                event_id INTEGER NOT NULL,
                calendar_id INTEGER NOT NULL,
                state TEXT NOT NULL,
                title TEXT,
                start_millis INTEGER NOT NULL,
                detail TEXT,
                last_seen_millis INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE pending_operations (
                operation_id TEXT PRIMARY KEY NOT NULL,
                slot_hex TEXT,
                type TEXT NOT NULL,
                phase TEXT NOT NULL,
                payload TEXT NOT NULL,
                created_at_millis INTEGER NOT NULL,
                updated_at_millis INTEGER NOT NULL,
                attempts INTEGER NOT NULL,
                last_error TEXT
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX pending_operations_slot ON pending_operations(slot_hex)"
        )
        createConfirmedOrphansTable(database)
    }

    override fun onUpgrade(
        database: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        if (oldVersion < 2) {
            createConfirmedOrphansTable(database)
        }
    }

    @Synchronized
    override fun listCachedBindings(): List<Cl1CachedBinding> {
        return readableDatabase.query(
            TABLE_BINDINGS,
            null,
            null,
            null,
            null,
            null,
            "last_seen_millis DESC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Cl1CachedBinding(
                            slotHex = cursor.nullableString("slot_hex"),
                            role = Cl1BindingRole.valueOf(cursor.string("role")),
                            ref = Cl1EventRef(
                                eventId = cursor.long("event_id"),
                                calendarId = cursor.long("calendar_id")
                            ),
                            startMillis = cursor.long("start_millis"),
                            lastSeenMillis = cursor.long("last_seen_millis")
                        )
                    )
                }
            }
        }
    }

    @Synchronized
    override fun listCachedRelations(): List<Cl1CachedRelation> {
        return readableDatabase.query(
            TABLE_RELATIONS,
            null,
            null,
            null,
            null,
            null,
            "last_seen_millis DESC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.readRelation())
                }
            }
        }
    }

    @Synchronized
    override fun listCachedEventIssues(): List<Cl1CachedEventIssue> {
        return readableDatabase.query(
            TABLE_EVENT_ISSUES,
            null,
            null,
            null,
            null,
            null,
            "last_seen_millis DESC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Cl1CachedEventIssue(
                            ref = Cl1EventRef(
                                eventId = cursor.long("event_id"),
                                calendarId = cursor.long("calendar_id")
                            ),
                            state = Cl1EventIssueState.valueOf(cursor.string("state")),
                            title = cursor.nullableString("title"),
                            startMillis = cursor.long("start_millis"),
                            detail = cursor.nullableString("detail"),
                            lastSeenMillis = cursor.long("last_seen_millis")
                        )
                    )
                }
            }
        }
    }

    @Synchronized
    override fun listConfirmedOrphanSlots(): Set<String> {
        return readableDatabase.query(
            TABLE_CONFIRMED_ORPHANS,
            arrayOf("slot_hex"),
            null,
            null,
            null,
            null,
            null
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    add(cursor.string("slot_hex"))
                }
            }
        }
    }

    @Synchronized
    override fun saveDiscovery(snapshot: Cl1DiscoverySnapshot) {
        writableDatabase.inTransaction { database ->
            val observedRefs = snapshot.events.mapTo(LinkedHashSet()) { it.ref }
            observedRefs.forEach { ref ->
                database.delete(
                    TABLE_BINDINGS,
                    "calendar_id = ? AND event_id = ?",
                    arrayOf(ref.calendarId.toString(), ref.eventId.toString())
                )
                database.delete(
                    TABLE_EVENT_ISSUES,
                    "event_key = ?",
                    arrayOf(ref.key())
                )
            }

            snapshot.relations.forEach { relation ->
                val source = relation.source
                val mirror = relation.mirror
                database.insertWithOnConflict(
                    TABLE_RELATIONS,
                    null,
                    ContentValues().apply {
                        put("slot_hex", relation.key.slot.toHex())
                        put("state", relation.state.name)
                        putRef("source", source?.ref)
                        putRef("mirror", mirror?.ref)
                        putNullable("source_title", source?.title)
                        putNullable("mirror_title", mirror?.title)
                        putNullable("source_start_millis", source?.startMillis)
                        putNullable("mirror_start_millis", mirror?.startMillis)
                        putNullable(
                            "expected_revision_hex",
                            relation.expectedRevision?.toHex()
                        )
                        putNullable(
                            "actual_revision_hex",
                            relation.actualRevision?.toHex()
                        )
                        put(
                            "needs_revision_refresh",
                            if (relation.needsRevisionRefresh) 1 else 0
                        )
                        putNullable("detail", relation.detail)
                        put("last_seen_millis", snapshot.capturedAtMillis)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }

            snapshot.events.forEach { event ->
                when (val parsed = event.parsedDescription) {
                    is Cl1Description.Valid -> when (val payload = parsed.payload) {
                        is Cl1Payload.Source -> payload.records.forEach { record ->
                            database.putBinding(
                                slotHex = record.slot.toHex(),
                                role = Cl1BindingRole.SOURCE,
                                ref = event.ref,
                                startMillis = event.startMillis,
                                lastSeenMillis = snapshot.capturedAtMillis
                            )
                        }

                        is Cl1Payload.Mirror -> database.putBinding(
                            slotHex = org.fossify.clock.cl1.Cl1Crypto
                                .deriveSlot(payload.secret)
                                .toHex(),
                            role = Cl1BindingRole.MIRROR,
                            ref = event.ref,
                            startMillis = event.startMillis,
                            lastSeenMillis = snapshot.capturedAtMillis
                        )
                    }

                    is Cl1Description.UnsupportedVersion,
                    is Cl1Description.Corrupt,
                    -> database.putBinding(
                        slotHex = null,
                        role = Cl1BindingRole.ISSUE,
                        ref = event.ref,
                        startMillis = event.startMillis,
                        lastSeenMillis = snapshot.capturedAtMillis
                    )

                    is Cl1Description.None -> Unit
                }
            }

            snapshot.eventIssues.forEach { issue ->
                database.insertWithOnConflict(
                    TABLE_EVENT_ISSUES,
                    null,
                    ContentValues().apply {
                        put("event_key", issue.event.ref.key())
                        put("event_id", issue.event.ref.eventId)
                        put("calendar_id", issue.event.ref.calendarId)
                        put("state", issue.state.name)
                        putNullable("title", issue.event.title)
                        put("start_millis", issue.event.startMillis)
                        putNullable("detail", issue.detail)
                        put("last_seen_millis", snapshot.capturedAtMillis)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
        }
    }

    @Synchronized
    override fun markConfirmedOrphan(slotHex: String) {
        writableDatabase.insertWithOnConflict(
            TABLE_CONFIRMED_ORPHANS,
            null,
            ContentValues().apply {
                put("slot_hex", slotHex)
                put("confirmed_at_millis", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    @Synchronized
    override fun putOperation(operation: Cl1PendingOperation) {
        writableDatabase.insertWithOnConflict(
            TABLE_PENDING_OPERATIONS,
            null,
            ContentValues().apply {
                put("operation_id", operation.operationId)
                putNullable("slot_hex", operation.slotHex)
                put("type", operation.type)
                put("phase", operation.phase)
                put("payload", operation.payload)
                put("created_at_millis", operation.createdAtMillis)
                put("updated_at_millis", operation.updatedAtMillis)
                put("attempts", operation.attempts)
                putNullable("last_error", operation.lastError)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    override fun listPendingOperations(): List<Cl1PendingOperation> {
        return readableDatabase.query(
            TABLE_PENDING_OPERATIONS,
            null,
            null,
            null,
            null,
            null,
            "created_at_millis ASC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Cl1PendingOperation(
                            operationId = cursor.string("operation_id"),
                            slotHex = cursor.nullableString("slot_hex"),
                            type = cursor.string("type"),
                            phase = cursor.string("phase"),
                            payload = cursor.string("payload"),
                            createdAtMillis = cursor.long("created_at_millis"),
                            updatedAtMillis = cursor.long("updated_at_millis"),
                            attempts = cursor.int("attempts"),
                            lastError = cursor.nullableString("last_error")
                        )
                    )
                }
            }
        }
    }

    @Synchronized
    override fun removeOperation(operationId: String) {
        writableDatabase.delete(
            TABLE_PENDING_OPERATIONS,
            "operation_id = ?",
            arrayOf(operationId)
        )
    }

    private fun Cursor.readRelation(): Cl1CachedRelation {
        return Cl1CachedRelation(
            slotHex = string("slot_hex"),
            state = Cl1RelationState.valueOf(string("state")),
            sourceRef = nullableRef("source"),
            mirrorRef = nullableRef("mirror"),
            sourceTitle = nullableString("source_title"),
            mirrorTitle = nullableString("mirror_title"),
            sourceStartMillis = nullableLong("source_start_millis"),
            mirrorStartMillis = nullableLong("mirror_start_millis"),
            expectedRevisionHex = nullableString("expected_revision_hex"),
            actualRevisionHex = nullableString("actual_revision_hex"),
            needsRevisionRefresh = int("needs_revision_refresh") == 1,
            detail = nullableString("detail"),
            lastSeenMillis = long("last_seen_millis")
        )
    }

    private fun Cursor.nullableRef(prefix: String): Cl1EventRef? {
        val eventId = nullableLong("${prefix}_event_id") ?: return null
        val calendarId = nullableLong("${prefix}_calendar_id") ?: return null
        return Cl1EventRef(eventId, calendarId)
    }

    private fun SQLiteDatabase.putBinding(
        slotHex: String?,
        role: Cl1BindingRole,
        ref: Cl1EventRef,
        startMillis: Long,
        lastSeenMillis: Long,
    ) {
        insertWithOnConflict(
            TABLE_BINDINGS,
            null,
            ContentValues().apply {
                put("binding_key", bindingKey(slotHex, role, ref))
                putNullable("slot_hex", slotHex)
                put("role", role.name)
                put("event_id", ref.eventId)
                put("calendar_id", ref.calendarId)
                put("start_millis", startMillis)
                put("last_seen_millis", lastSeenMillis)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun ContentValues.putRef(prefix: String, ref: Cl1EventRef?) {
        putNullable("${prefix}_event_id", ref?.eventId)
        putNullable("${prefix}_calendar_id", ref?.calendarId)
    }

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullable(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun Cursor.index(name: String): Int = getColumnIndexOrThrow(name)

    private fun Cursor.string(name: String): String = getString(index(name))

    private fun Cursor.nullableString(name: String): String? {
        val index = index(name)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.long(name: String): Long = getLong(index(name))

    private fun Cursor.nullableLong(name: String): Long? {
        val index = index(name)
        return if (isNull(index)) null else getLong(index)
    }

    private fun Cursor.int(name: String): Int = getInt(index(name))

    private fun Cl1EventRef.key(): String = "$calendarId:$eventId"

    private fun bindingKey(
        slotHex: String?,
        role: Cl1BindingRole,
        ref: Cl1EventRef,
    ): String = "${slotHex.orEmpty()}:${role.name}:${ref.key()}"

    private inline fun <T> SQLiteDatabase.inTransaction(
        block: (SQLiteDatabase) -> T,
    ): T {
        beginTransaction()
        return try {
            block(this).also { setTransactionSuccessful() }
        } finally {
            endTransaction()
        }
    }

    private fun createConfirmedOrphansTable(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS confirmed_orphans (
                slot_hex TEXT PRIMARY KEY NOT NULL,
                confirmed_at_millis INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    companion object {
        private const val DATABASE_NAME = "cl1.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_RELATIONS = "relations"
        private const val TABLE_BINDINGS = "bindings"
        private const val TABLE_EVENT_ISSUES = "event_issues"
        private const val TABLE_PENDING_OPERATIONS = "pending_operations"
        private const val TABLE_CONFIRMED_ORPHANS = "confirmed_orphans"

        @Volatile
        private var instance: AndroidCl1Storage? = null

        private fun getInstance(context: Context): AndroidCl1Storage {
            return instance ?: synchronized(this) {
                instance ?: AndroidCl1Storage(context).also { instance = it }
            }
        }

        fun from(context: Context): AndroidCl1Storage = getInstance(context)
    }
}
