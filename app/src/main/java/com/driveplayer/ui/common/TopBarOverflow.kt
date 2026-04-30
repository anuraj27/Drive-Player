package com.driveplayer.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.driveplayer.ui.theme.TextPrimary
import com.driveplayer.ui.theme.TextSecondary

/**
 * Shared 3-dot overflow used in every Home tab's top bar.
 *
 * Layout note: the [IconButton] and [DropdownMenu] MUST share a single [Box]
 * parent. `DropdownMenu` is a `Popup`, and its onscreen position is computed
 * relative to its layout parent — without a wrapper Box that parent becomes
 * whatever container the call-site puts the overflow in (a wide TopAppBar
 * actions slot is fine, but a plain Row makes the popup anchor on the row's
 * left edge instead of the icon, drifting the menu to the wrong side of the
 * screen). Boxing them together pins the popup to the icon's bounds.
 *
 * Styling note: we deliberately let the dropdown surface and item colours fall
 * back to `MaterialTheme.colorScheme` defaults — explicit overrides like
 * `DropdownMenu(containerColor = …)` aren't part of this Material3 release's
 * stable API.
 *
 * @param onOpenSettings Required — every tab should expose Settings here.
 * @param onRefresh      Optional. When non-null a "Refresh" item appears
 *                       above Settings, replacing the standalone refresh icon
 *                       call-sites used to render in their action row.
 */
@Composable
fun TopBarOverflow(
    onOpenSettings: () -> Unit,
    onRefresh: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "More",
                tint = TextSecondary,
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            if (onRefresh != null) {
                DropdownMenuItem(
                    text = { Text("Refresh", color = TextPrimary) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        open = false
                        onRefresh()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Settings", color = TextPrimary) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = {
                    open = false
                    onOpenSettings()
                },
            )
        }
    }
}
