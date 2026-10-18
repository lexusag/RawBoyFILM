package app.rawboy.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.rawboy.auto.looks.Look

/**
 * A look or film stock picked from a menu: one row instead of a strip of chips.
 * [selected] null shows [placeholder].
 */
@Composable
fun LookDropdown(
    label: String,
    looks: List<Look>,
    selected: Look?,
    onSelect: (Look) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    labelWidth: Dp = 96.dp,
    placeholder: String = "",
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(labelWidth))
        Box {
            OutlinedButton(
                onClick = { open = true },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 8.dp),
                modifier = Modifier.heightIn(min = 36.dp),
            ) {
                Text(selected?.name ?: placeholder)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (look in looks) {
                    val isSelected = look.id == selected?.id
                    DropdownMenuItem(
                        text = {
                            Text(
                                look.name,
                                fontWeight = if (isSelected) FontWeight.Bold else null,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = {
                            open = false
                            onSelect(look)
                        },
                    )
                }
            }
        }
        if (loading) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(18.dp), strokeWidth = 2.dp)
    }
}
