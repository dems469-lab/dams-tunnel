package com.v2ray.ang.ui.compose

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.MaterialTheme

@Composable
fun <T> AppDropdownMenuItems(
    items: List<T>,
    labelRes: (T) -> Int,
    iconRes: (T) -> Int? = { null },
    onSelected: (T) -> Unit
) {
    items.forEach { item ->
        DropdownMenuItem(
            modifier = Modifier,
            leadingIcon = iconRes(item)?.let { icon ->
                { Icon(painterResource(icon), contentDescription = null) }
            },
            text = {
                Text(
                    text = stringResource(labelRes(item)),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                )
            },
            onClick = { onSelected(item) }
        )
    }
}
