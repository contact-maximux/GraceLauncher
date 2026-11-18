package com.galaxyrio.gracelauncher.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Base64
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/** JSON backup for launcher-owned preferences and Room data. */
class LauncherBackup(context: Context, private val database: LauncherDatabase) {
    private val resolver = context.contentResolver
    private val context = context.applicationContext
    private val sharedPreferences = listOf(
        context.getSharedPreferences("grace_launcher_preferences", Context.MODE_PRIVATE),
        context.getSharedPreferences("launcher_appearance", Context.MODE_PRIVATE),
    )

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val snapshot = database.withTransaction {
            val sql = database.openHelper.writableDatabase
            JSONObject()
                .put("format", FORMAT_VERSION)
                .put("databaseVersion", sql.version)
                .put("tables", encodeTables(sql))
                .put("preferences", encodePreferences())
                .put("files", encodeFiles())
        }
        val output = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("Unable to open the selected backup file for writing")
        output.use { stream ->
            stream.write(snapshot.toString().toByteArray(StandardCharsets.UTF_8))
        }
    }

    suspend fun restore(uri: Uri) = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(uri)
            ?: throw IOException("Unable to open the selected backup file")
        val payload = input.use { readLimited(it) }
        val backup = try {
            JSONObject(String(payload, StandardCharsets.UTF_8))
        } catch (error: Exception) {
            throw IOException("This is not a valid Grace Launcher backup", error)
        }
        require(backup.optInt("format", -1) == FORMAT_VERSION) { "Unsupported backup format" }

        val tables = backup.optJSONObject("tables") ?: throw IOException("Backup is missing its launcher data")
        val preferences = decodePreferences(backup.optJSONObject("preferences")
            ?: throw IOException("Backup is missing its preferences"))
        val files = decodeFiles(backup.optJSONObject("files") ?: throw IOException("Backup is missing custom files"))
        val sql = database.openHelper.writableDatabase
        require(backup.optInt("databaseVersion", -1) == sql.version) {
            "This backup was created by an incompatible launcher database version"
        }
        validateTables(sql, tables)

        database.withTransaction {
            val target = database.openHelper.writableDatabase
            TABLES.forEach { table ->
                target.delete(table, null, null)
                val rows = tables.getJSONArray(table)
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val values = ContentValues(row.length())
                    val keys = row.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        putValue(values, key, decodeDatabaseValue(row.get(key)))
                    }
                    check(target.insert(table, SupportSQLiteDatabase.CONFLICT_ABORT, values) != -1L) {
                        "Could not restore launcher data"
                    }
                }
            }
        }
        restorePreferences(preferences)
        files.forEach { (directory, contents) ->
            val targetDirectory = File(context.filesDir, directory)
            if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
                throw IOException("Unable to restore custom launcher files")
            }
            contents.forEach { (name, bytes) ->
                val target = File(targetDirectory, name)
                val temporary = File(targetDirectory, "$name.restore")
                try {
                    temporary.outputStream().use { it.write(bytes) }
                    if (!temporary.renameTo(target)) {
                        target.delete()
                        if (!temporary.renameTo(target)) {
                            throw IOException("Unable to restore custom launcher files")
                        }
                    }
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    private fun encodeTables(database: SupportSQLiteDatabase): JSONObject = JSONObject().apply {
        TABLES.forEach { table ->
            val rows = JSONArray()
            val cursor = database.query("SELECT * FROM $table")
            cursor.use {
                while (it.moveToNext()) {
                    val row = JSONObject()
                    for (column in 0 until it.columnCount) {
                        row.put(it.getColumnName(column), encodeDatabaseValue(it, column))
                    }
                    rows.put(row)
                }
            }
            put(table, rows)
        }
    }

    private fun encodeDatabaseValue(cursor: Cursor, column: Int): Any = when (cursor.getType(column)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
        Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
        Cursor.FIELD_TYPE_BLOB -> JSONObject().put("base64", Base64.encodeToString(cursor.getBlob(column), Base64.NO_WRAP))
        else -> throw IOException("Unsupported launcher database value")
    }

    private fun encodePreferences(): JSONObject = JSONObject().apply {
        sharedPreferences.forEachIndexed { index, preferences ->
            put(PREFERENCE_FILES[index], JSONObject().apply {
                preferences.all.toSortedMap().forEach { (key, value) ->
                    put(key, when (value) {
                        is String -> JSONObject().put("type", "string").put("value", value)
                        is Boolean -> JSONObject().put("type", "boolean").put("value", value)
                        is Int -> JSONObject().put("type", "int").put("value", value)
                        is Long -> JSONObject().put("type", "long").put("value", value)
                        is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
                        is Set<*> -> JSONObject().put("type", "string-set").put("value",
                            JSONArray(value.filterIsInstance<String>().sorted()))
                        else -> throw IOException("Unsupported launcher preference value: $key")
                    })
                }
            })
        }
    }

    private fun encodeFiles(): JSONObject = JSONObject().apply {
        FILE_DIRECTORIES.forEach { directoryName ->
            val directory = File(context.filesDir, directoryName)
            put(directoryName, JSONObject().apply {
                directory.listFiles().orEmpty().filter(File::isFile).forEach { file ->
                    if (!validFileName(directoryName, file.name)) {
                        throw IOException("Invalid custom launcher file name")
                    }
                    val bytes = file.readBytes()
                    if (bytes.size > MAX_FILE_SIZE) throw IOException("A custom launcher file is too large to back up")
                    put(file.name, Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
            })
        }
    }

    private fun decodeFiles(json: JSONObject): Map<String, Map<String, ByteArray>> = FILE_DIRECTORIES.associateWith { directory ->
        val entries = json.optJSONObject(directory) ?: throw IOException("Backup is missing custom files")
        buildMap {
            val keys = entries.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                if (!validFileName(directory, name)) throw IOException("Invalid custom launcher file name")
                val contents = try {
                    Base64.decode(entries.getString(name), Base64.NO_WRAP)
                } catch (error: IllegalArgumentException) {
                    throw IOException("Invalid custom launcher file", error)
                }
                if (contents.size > MAX_FILE_SIZE) throw IOException("A custom launcher file is too large")
                put(name, contents)
            }
        }
    }

    private fun validFileName(directory: String, name: String): Boolean = when (directory) {
        "item_icons" -> name.matches(Regex("[0-9a-f-]{36}\\.png"))
        "clock_fonts" -> name.matches(Regex("[0-9a-f-]{36}_[\\p{L}\\p{N} _-]+\\.(ttf|otf|ttc)"))
        else -> false
    }

    private fun decodePreferences(json: JSONObject): List<Map<String, Any>> = PREFERENCE_FILES.map { file ->
        val values = json.optJSONObject(file) ?: throw IOException("Backup is missing launcher preferences")
        buildMap {
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = values.getJSONObject(key)
                val value = entry.get("value")
                put(key, when (entry.getString("type")) {
                    "string" -> value as? String ?: throw IOException("Invalid string preference")
                    "boolean" -> value as? Boolean ?: throw IOException("Invalid boolean preference")
                    "int" -> (value as? Number)?.toInt() ?: throw IOException("Invalid integer preference")
                    "long" -> (value as? Number)?.toLong() ?: throw IOException("Invalid long preference")
                    "float" -> (value as? Number)?.toFloat() ?: throw IOException("Invalid float preference")
                    "string-set" -> {
                        val array = value as? JSONArray ?: throw IOException("Invalid string-set preference")
                        buildSet {
                            for (index in 0 until array.length()) add(array.getString(index))
                        }
                    }
                    else -> throw IOException("Unsupported launcher preference type")
                })
            }
        }
    }

    private fun restorePreferences(values: List<Map<String, Any>>) {
        sharedPreferences.forEachIndexed { index, preferences ->
            val editor = preferences.edit().clear()
            values[index].forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            check(editor.commit()) { "Unable to restore launcher preferences" }
        }
    }

    private fun validateTables(database: SupportSQLiteDatabase, tables: JSONObject) {
        TABLES.forEach { table ->
            val rows = tables.optJSONArray(table) ?: throw IOException("Backup is missing $table data")
            val cursor = database.query("SELECT * FROM $table")
            val columns = cursor.use { (0 until it.columnCount).map(it::getColumnName).toSet() }
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: throw IOException("Invalid $table row")
                val rowColumns = buildSet {
                    val keys = row.keys()
                    while (keys.hasNext()) add(keys.next())
                }
                if (rowColumns != columns) throw IOException("Backup $table schema does not match")
                row.keys().forEach { key -> decodeDatabaseValue(row.get(key)) }
            }
        }
    }

    private fun decodeDatabaseValue(value: Any): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> {
            val encoded = value.optString("base64", "")
            if (!value.has("base64")) throw IOException("Invalid binary data in backup")
            try {
                Base64.decode(encoded, Base64.NO_WRAP)
            } catch (error: IllegalArgumentException) {
                throw IOException("Invalid binary data in backup", error)
            }
        }
        is String, is Number -> value
        else -> throw IOException("Unsupported value in backup")
    }

    private fun putValue(values: ContentValues, key: String, value: Any?) {
        when (value) {
            null -> values.putNull(key)
            is ByteArray -> values.put(key, value)
            is String -> values.put(key, value)
            is Int -> values.put(key, value)
            is Long -> values.put(key, value)
            is Double -> values.put(key, value)
            is Float -> values.put(key, value)
            else -> throw IOException("Unsupported value in backup")
        }
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_BACKUP_SIZE) throw IOException("Backup file is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val MAX_BACKUP_SIZE = 64 * 1024 * 1024
        const val MAX_FILE_SIZE = 20 * 1024 * 1024
        val TABLES = listOf("launcher_settings", "hidden_apps", "folders", "item_icons", "saved_shortcuts", "app_popups")
        val PREFERENCE_FILES = listOf("grace_launcher_preferences", "launcher_appearance")
        val FILE_DIRECTORIES = listOf("item_icons", "clock_fonts")
    }
}
