package app.rawboy.lut

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import app.rawboy.auto.looks.RetiredLooks
import java.io.IOException
import java.io.InputStream

/**
 * Finds LUTs: bundled ones in assets/luts, and the user's own in a folder they
 * granted through the system picker (Documents/luts by default). Shared storage
 * isn't readable without "All files access" on Android 11+, so a one-time
 * folder grant is the supported way to scan it.
 */
object LutRepository {
    private const val ASSET_DIR = "luts"
    private const val PREFS = "luts"
    private const val KEY_FOLDER = "userFolder"
    private const val ASSET_PREFIX = "asset:"
    private const val DOC_PREFIX = "doc:"
    private val EXTENSIONS = setOf("cube", "png")

    /** Where the folder picker opens: Documents/luts on the primary volume. */
    val suggestedFolder: Uri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents", "primary:Documents/luts",
    )

    fun library(context: Context): LutLibrary {
        val folder = userFolder(context)
        return LutLibrary(
            bundled = bundled(context),
            user = folder?.let { runCatching { scanFolder(context, it) }.getOrDefault(emptyList()) }.orEmpty(),
            userFolderGranted = folder != null,
        )
    }

    fun setUserFolder(context: Context, tree: Uri) {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FOLDER, tree.toString()).apply()
    }

    fun open(context: Context, id: String): InputStream = when {
        id.startsWith(ASSET_PREFIX) -> context.assets.open(RetiredLooks.lut(id).removePrefix(ASSET_PREFIX))
        id.startsWith(DOC_PREFIX) -> context.contentResolver.openInputStream(Uri.parse(id.removePrefix(DOC_PREFIX)))
            ?: throw IOException("Cannot open LUT")
        else -> throw IOException("Unknown LUT $id")
    }

    private fun bundled(context: Context): List<LutEntry> =
        context.assets.list(ASSET_DIR).orEmpty()
            .filter { it.substringAfterLast('.').lowercase() in EXTENSIONS }
            .map { LutEntry(ASSET_PREFIX + "$ASSET_DIR/$it", displayName(it), bundled = true) }
            .sortedBy { it.name.lowercase() }

    /** The granted folder, if the grant is still valid. */
    private fun userFolder(context: Context): Uri? {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FOLDER, null)
            ?: return null
        val uri = Uri.parse(saved)
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return if (granted) uri else null
    }

    private fun scanFolder(context: Context, tree: Uri): List<LutEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val entries = mutableListOf<LutEntry>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.substringAfterLast('.').lowercase() !in EXTENSIONS) continue
                val doc = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                entries += LutEntry(DOC_PREFIX + doc, displayName(name), bundled = false)
            }
        }
        return entries.sortedBy { it.name.lowercase() }
    }

    private fun displayName(fileName: String) = fileName.substringBeforeLast('.').trim()
}
