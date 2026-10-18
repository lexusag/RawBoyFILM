package app.rawboy.export

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.util.Locale

/**
 * Where exports go and how they are named: Pictures/<folder>/<original>-<suffix>.<ext>
 * (<original>.<ext> with an empty suffix). [folder] may contain subfolders ("RawBoy Camera/RAW").
 */
data class OutputNaming(val folder: String = "RawBoy", val suffix: String = "rawboy") {
    val relativePath: String get() = Environment.DIRECTORY_PICTURES + "/" + folder + "/"
}

/**
 * Creates output files in Pictures/<folder>/ named "<original>-<suffix>.<ext>",
 * falling back to "<original>-<suffix>-001.<ext>", "-002", ... on conflict.
 */
object MediaStoreOutput {
    private const val MAX_COUNTER = 999

    fun fileName(baseName: String, extension: String, counter: Int, naming: OutputNaming = OutputNaming()): String {
        val stem = if (naming.suffix.isEmpty()) baseName else "$baseName-${naming.suffix}"
        return if (counter == 0) "$stem.$extension" else String.format(Locale.ROOT, "%s-%03d.%s", stem, counter, extension)
    }

    /** Strips the extension and characters MediaStore won't accept. */
    fun baseNameOf(displayName: String): String {
        val stem = displayName.substringBeforeLast('.').ifBlank { displayName }
        return stem.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "image" }
    }

    /** Inserts a pending entry with a unique name. Returns its Uri and the final display name. */
    fun create(resolver: ContentResolver, baseName: String, format: ExportFormat, naming: OutputNaming): Pair<Uri, String> =
        create(resolver, baseName, format.extension, format.mimeType, naming)

    /** Like [create] for any image type (e.g. "dng", "image/x-adobe-dng"). */
    fun create(
        resolver: ContentResolver,
        baseName: String,
        extension: String,
        mimeType: String,
        naming: OutputNaming,
    ): Pair<Uri, String> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val visible = existingNames(resolver, collection, naming.relativePath)
        for (counter in 0..MAX_COUNTER) {
            val name = fileName(baseName, extension, counter, naming)
            if (name in visible) continue
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, naming.relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: throw IOException("Could not create $name")
            // Files we can't see (e.g. left by a previous install) make MediaStore
            // silently rename ours to "name (1).jpg". Detect that and use our own counter instead.
            if (displayName(resolver, uri) == name) return uri to name
            resolver.delete(uri, null, null)
        }
        throw IOException("Too many files named ${fileName(baseName, extension, 0, naming)}")
    }

    fun publish(resolver: ContentResolver, uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    private fun existingNames(resolver: ContentResolver, collection: Uri, relativePath: String): Set<String> {
        val names = HashSet<String>()
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(relativePath),
            null,
        )?.use { c -> while (c.moveToNext()) c.getString(0)?.let(names::add) }
        return names
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
