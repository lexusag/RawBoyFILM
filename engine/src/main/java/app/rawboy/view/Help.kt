package app.rawboy.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One line of a help sheet: the gesture or control ([how]) and what it does. */
data class HelpItem(val how: String, val text: String)

/** A titled group of [HelpItem]s. */
data class HelpSection(val title: String, val items: List<HelpItem>)

/**
 * The top bars' "?" button: opens a sheet with the app's basics and gestures ([sections]), as RawBoy Web's
 * Ctrl+/ sheet does. With [licences] the sheet ends with "Open-source licences" (Pro and Auto keep them here;
 * Camera has them in Settings). [onOpenChange] reports the sheet opening and closing (Camera's volume keys pause).
 */
@Composable
fun HelpButton(title: String, sections: List<HelpSection>, licences: Boolean = false, onOpenChange: (Boolean) -> Unit = {}) {
    var showHelp by remember { mutableStateOf(false) }
    LaunchedEffect(showHelp) { onOpenChange(showHelp) }
    var showLicences by remember { mutableStateOf(false) }
    IconButton(onClick = { showHelp = true }) {
        Icon(HelpIcon, contentDescription = "Help")
    }
    if (showHelp) {
        HelpSheet(
            title,
            sections,
            onLicences = if (licences) {
                {
                    showHelp = false
                    showLicences = true
                }
            } else {
                null
            },
            onDismiss = { showHelp = false },
        )
    }
    if (showLicences) LicensesDialog(onDismiss = { showLicences = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpSheet(title: String, sections: List<HelpSection>, onLicences: (() -> Unit)?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            item { Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp)) }
            sections.forEach { section ->
                item {
                    Text(
                        section.title.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                }
                items(section.items) { item ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(item.how, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(item.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
            item {
                if (onLicences != null) {
                    TextButton(onClick = onLicences, modifier = Modifier.padding(top = 8.dp)) { Text("Open-source licences") }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** Material "help_outline" icon (Apache 2.0), built here because it isn't in material-icons-core. */
private val HelpIcon: ImageVector by lazy {
    ImageVector.Builder("HelpOutline", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = addPathNodes(
                "M11,18h2v-2h-2v2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
                    "M12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z" +
                    "M12,6c-2.21,0 -4,1.79 -4,4h2c0,-1.1 0.9,-2 2,-2s2,0.9 2,2c0,2 -3,1.75 -3,5h2c0,-2.25 3,-2.5 3,-5 0,-2.21 -1.79,-4 -4,-4z",
            ),
            fill = SolidColor(Color.Black),
        )
        .build()
}
