package com.mgafk.app.ui.screens.shops

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mgafk.app.data.model.ShopSnapshot
import com.mgafk.app.data.model.WatchlistItem
import com.mgafk.app.data.repository.MgApi
import com.mgafk.app.ui.components.AppCard
import com.mgafk.app.ui.components.SpriteImage
import com.mgafk.app.ui.theme.Accent
import com.mgafk.app.ui.theme.AccentDim
import com.mgafk.app.ui.theme.SurfaceBorder
import com.mgafk.app.ui.theme.SurfaceDark
import com.mgafk.app.ui.theme.TextMuted
import com.mgafk.app.ui.theme.TextPrimary

private val WATCHLIST_SHOP_TYPES = listOf("seed", "tool", "egg")

/**
 * Card hiển thị danh sách Watchlist items.
 * User có thể thêm item từ shop hiện tại hoặc xoá từng item.
 *
 * @param watchlist  Danh sách item đang theo dõi (từ UiState)
 * @param shops      Danh sách shop hiện tại để chọn item thêm vào
 * @param onAdd      Callback khi user thêm item
 * @param onRemove   Callback khi user xoá item
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchlistCard(
    watchlist: List<WatchlistItem>,
    shops: List<ShopSnapshot>,
    onAdd: (shopType: String, itemId: String) -> Unit,
    onRemove: (shopType: String, itemId: String) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    AppCard(
        title = "Watchlist",
        persistKey = "watchlist",
        trailing = {
            // Add button
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(AccentDim)
                    .clickable { showAddDialog = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add watchlist item",
                    tint = TextPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        },
    ) {
        if (watchlist.isEmpty()) {
            Text(
                text = "No items — tap + to add.\nApp will auto-buy when shop restocks.",
                color = TextMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                watchlist.forEach { item ->
                    WatchlistChip(
                        item = item,
                        onRemove = { onRemove(item.shopType, item.itemId) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Auto-buy all stock on restock",
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    if (showAddDialog) {
        AddWatchlistDialog(
            shops = shops,
            existingWatchlist = watchlist,
            onAdd = { shopType, itemId ->
                onAdd(shopType, itemId)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun WatchlistChip(
    item: WatchlistItem,
    onRemove: () -> Unit,
) {
    val displayName = MgApi.itemDisplayName(item.itemId)
    val spriteCategory = when (item.shopType) {
        "seed" -> "plants"
        "tool" -> "items"
        "egg" -> "eggs"
        else -> "items"
    }
    val spriteUrl = MgApi.findItem(item.itemId)?.sprite?.let {
        MgApi.spriteUrl(spriteCategory, it.removeSuffix(".png"))
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .border(1.dp, SurfaceBorder, RoundedCornerShape(20.dp))
            .background(SurfaceDark, RoundedCornerShape(20.dp))
            .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        if (spriteUrl != null) {
            SpriteImage(
                url = spriteUrl,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = displayName,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(2.dp))
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .clickable { onRemove() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove",
                tint = TextMuted,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun AddWatchlistDialog(
    shops: List<ShopSnapshot>,
    existingWatchlist: List<WatchlistItem>,
    onAdd: (shopType: String, itemId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Build list of available items not already in watchlist
    val availableItems = remember(shops, existingWatchlist) {
        val existing = existingWatchlist.map { it.shopType to it.itemId }.toSet()
        shops
            .filter { it.type in WATCHLIST_SHOP_TYPES }
            .flatMap { shop ->
                shop.itemNames.mapNotNull { itemId ->
                    if ((shop.type to itemId) in existing) null
                    else Triple(shop.type, itemId, MgApi.itemDisplayName(itemId))
                }
            }
            .sortedWith(compareBy({ it.first }, { it.third }))
    }

    var selectedShopType by remember { mutableStateOf<String?>(null) }
    var selectedItemId by remember { mutableStateOf<String?>(null) }
    var shopDropdownExpanded by remember { mutableStateOf(false) }
    var itemDropdownExpanded by remember { mutableStateOf(false) }

    val shopTypes = availableItems.map { it.first }.distinct()
    val itemsForType = availableItems.filter { it.first == selectedShopType }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Watchlist", color = TextPrimary) },
        containerColor = SurfaceDark,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Select an item to auto-buy when the shop restocks.",
                    color = TextMuted,
                    fontSize = 13.sp,
                )

                // Shop type picker
                Text("Shop type", color = TextMuted, fontSize = 12.sp)
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SurfaceBorder, RoundedCornerShape(8.dp))
                            .clickable { shopDropdownExpanded = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = selectedShopType?.replaceFirstChar { it.uppercase() } ?: "Select shop…",
                            color = if (selectedShopType != null) TextPrimary else TextMuted,
                            fontSize = 14.sp,
                        )
                    }
                    DropdownMenu(
                        expanded = shopDropdownExpanded,
                        onDismissRequest = { shopDropdownExpanded = false },
                    ) {
                        shopTypes.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    selectedShopType = type
                                    selectedItemId = null
                                    shopDropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                // Item picker
                if (selectedShopType != null) {
                    Text("Item", color = TextMuted, fontSize = 12.sp)
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, SurfaceBorder, RoundedCornerShape(8.dp))
                                .clickable { itemDropdownExpanded = true }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = selectedItemId?.let { MgApi.itemDisplayName(it) } ?: "Select item…",
                                color = if (selectedItemId != null) TextPrimary else TextMuted,
                                fontSize = 14.sp,
                            )
                        }
                        DropdownMenu(
                            expanded = itemDropdownExpanded,
                            onDismissRequest = { itemDropdownExpanded = false },
                        ) {
                            if (itemsForType.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No items available", color = TextMuted) },
                                    onClick = { itemDropdownExpanded = false },
                                )
                            } else {
                                itemsForType.forEach { (_, itemId, displayName) ->
                                    DropdownMenuItem(
                                        text = { Text(displayName) },
                                        onClick = {
                                            selectedItemId = itemId
                                            itemDropdownExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val type = selectedShopType
                    val item = selectedItemId
                    if (type != null && item != null) onAdd(type, item)
                },
                enabled = selectedShopType != null && selectedItemId != null,
            ) {
                Text("Add", color = if (selectedShopType != null && selectedItemId != null) Accent else TextMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        },
    )
}
