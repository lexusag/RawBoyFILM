package app.rawboy.view

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.rawboy.engine.BuildConfig

/** The patent notice the DNG specification's licence requires in the product's documentation. */
const val DNG_NOTICE = "This product includes DNG technology under license by Adobe."

/**
 * The film edition's (RawBoy Film's) licence notice, shown first (GPLv3 section 5(d), "Appropriate Legal Notices").
 * That edition is built without the Adobe DNG SDK, so it has no DNG notice.
 */
const val GPL_NOTICE = "Free software: you can redistribute it and/or modify it under the terms of the GNU General " +
    "Public License, version 3 or (at your option) any later version. It comes with ABSOLUTELY NO WARRANTY. " +
    "Source code: https://github.com/lexusag/RawBoyFILM. Film simulation powered by spektrafilm by Andrea Volpato."

/** What the licences screen shows under the app's name: this edition's legal notice. */
val EDITION_NOTICE: String get() = if (BuildConfig.FILM_EDITION) GPL_NOTICE else DNG_NOTICE

/** <repo>/licenses/THIRD_PARTY_NOTICES.txt, packaged by the engine's copyNotices task. */
private const val NOTICES_ASSET = "third_party_notices.txt"

/** A line kept on its own line inside a paragraph (after trimming): a list item or a short all-caps heading. */
private val KEEP_BREAK = Regex("""^([-*•]|\(?[0-9a-z]{1,2}[.)]\s|[A-Z0-9 ,()-]{1,40}:?$)""")

/**
 * The notices split at blank lines, so the list only lays out what's on screen. The file is wrapped at 80
 * columns; paragraphs are reflowed to the screen width, and the "====" rules around headings are dropped.
 */
private fun loadNotices(context: Context): List<String> =
    context.assets.open(NOTICES_ASSET).bufferedReader().use { it.readText() }
        .split(Regex("\n\\s*\n"))
        .map { block ->
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.all { c -> c == '=' } }
            buildString {
                lines.forEachIndexed { i, line ->
                    if (i > 0) append(if (KEEP_BREAK.containsMatchIn(line) || KEEP_BREAK.matches(lines[i - 1])) "\n" else " ")
                    append(line)
                }
            }
        }
        .filter { it.isNotBlank() }

/** Full-screen list of the app's version, its edition's notice (DNG or GPL) and every third-party licence. */
@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val blocks = remember { loadNotices(context) }
    val title = remember {
        val name = context.applicationInfo.loadLabel(context.packageManager)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        "$name $version"
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Open-source licences", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                LazyColumn(Modifier.padding(horizontal = 24.dp)) {
                    item {
                        Text(title, style = MaterialTheme.typography.bodyMedium)
                        Text(EDITION_NOTICE, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))
                    }
                    items(blocks) { block ->
                        Text(
                            block,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
