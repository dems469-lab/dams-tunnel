package com.v2ray.ang.reconfig.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.reconfig.engine.ReconfigMethod
import com.v2ray.ang.reconfig.engine.ReconfigTargetError
import com.v2ray.ang.reconfig.engine.ReconfigTargetValidator
import com.v2ray.ang.ui.compose.DamsTheme

/**
 * État de la reconfiguration lancée depuis « Serveurs gratuits ».
 *
 * Le résultat provient du traitement par lots : chaque configuration est
 * traitée séparément et les originaux ne sont jamais modifiés.
 */
internal data class FreeReconfigureState(
    val running: Boolean = false,
    val processed: Int = 0,
    val total: Int = 0,
    val prepared: Int = 0,
    val duplicates: Int = 0,
    val ignored: Int = 0,
    val done: Boolean = false,
    val cancelled: Boolean = false,
) {
    val ratio: Float get() = if (total <= 0) 0f else (processed.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Feuille de configuration de la reconfiguration, ouverte depuis la liste des
 * serveurs gratuits.
 *
 * Aucune requête réseau n'est effectuée ici : seule l'adresse de connexion des
 * profils choisis est réécrite localement.
 */
@Composable
internal fun ReconfigureSheet(
    selectedCount: Int,
    state: FreeReconfigureState,
    onDismiss: () -> Unit,
    onRun: (ReconfigMethod, String) -> Unit,
    onCancelRun: () -> Unit,
    onOpenReconfiguration: () -> Unit,
    onReset: () -> Unit,
) {
    val theme = DamsTheme
    var method by rememberSaveable { mutableStateOf(ReconfigMethod.MAXIT_MOBILE) }
    var customTarget by rememberSaveable { mutableStateOf("") }
    val isCustom = method == ReconfigMethod.CUSTOM
    val rawTarget = if (isCustom) customTarget else method.defaultTarget
    val validation = remember(rawTarget) { ReconfigTargetValidator.validate(rawTarget) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.reconfig_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            color = theme.texteFort,
        )
        Text(
            stringResource(R.string.reconfig_sheet_selected, selectedCount),
            style = MaterialTheme.typography.bodySmall,
            color = theme.texteDoux,
        )

        when {
            state.running -> {
                Text(
                    stringResource(R.string.reconfig_sheet_progress, state.processed, state.total),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.accent,
                )
                LinearProgressIndicator(
                    progress = { state.ratio },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = theme.accent,
                    trackColor = theme.bordure.copy(alpha = 0.6f),
                )
                OutlinedButton(
                    onClick = onCancelRun,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Text(stringResource(R.string.reconfig_progress_cancel))
                }
            }

            state.done -> {
                Text(
                    stringResource(
                        R.string.reconfig_sheet_done,
                        state.prepared,
                        state.duplicates + state.ignored,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.texteFort,
                )
                if (state.duplicates > 0) {
                    Text(
                        stringResource(R.string.reconfig_sheet_ineligible, state.duplicates),
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.avertissement,
                    )
                }
                if (state.cancelled) {
                    Text(
                        stringResource(R.string.reconfig_op_cancelled),
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.avertissement,
                    )
                }
                Button(
                    onClick = onOpenReconfiguration,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Text(stringResource(R.string.reconfig_sheet_open))
                }
                TextButton(
                    onClick = {
                        onReset()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.reconfig_close))
                }
            }

            else -> {
                Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReconfigMethod.entries.forEach { candidate ->
                        MethodRow(
                            method = candidate,
                            selected = method == candidate,
                            onSelect = {
                                method = candidate
                                if (candidate != ReconfigMethod.CUSTOM) customTarget = ""
                            },
                        )
                    }
                }

                if (isCustom) {
                    OutlinedTextField(
                        value = customTarget,
                        onValueChange = { customTarget = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.reconfig_sheet_target)) },
                        placeholder = { Text(stringResource(R.string.reconfig_sheet_target_hint)) },
                        isError = customTarget.isNotBlank() && !validation.isValid,
                        supportingText = {
                            val errorRes = targetErrorRes(validation.error)
                            if (customTarget.isNotBlank() && errorRes != null) {
                                Text(stringResource(errorRes), color = theme.erreur)
                            }
                        },
                    )
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = theme.fond.copy(alpha = 0.5f),
                        ),
                        border = BorderStroke(1.dp, theme.bordure),
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                stringResource(R.string.reconfig_sheet_target),
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.texteDoux,
                            )
                            Text(
                                method.defaultTarget,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = theme.accent,
                            )
                        }
                    }
                }

                Text(
                    stringResource(R.string.reconfig_sheet_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.texteDoux,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.reconfig_cancel))
                    }
                    Button(
                        onClick = { validation.target?.let { onRun(method, it) } },
                        enabled = selectedCount > 0 && validation.isValid,
                        modifier = Modifier.weight(1f).height(46.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text(stringResource(R.string.reconfig_sheet_run, selectedCount), maxLines = 1)
                    }
                }

                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun MethodRow(
    method: ReconfigMethod,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val theme = DamsTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.size(36.dp))
        Text(
            methodLabel(method),
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) theme.accent else theme.texteFort,
        )
    }
}

@Composable
private fun methodLabel(method: ReconfigMethod): String = stringResource(
    when (method) {
        ReconfigMethod.MAXIT_MOBILE -> R.string.reconfig_method_maxit
        ReconfigMethod.API_CONNECT -> R.string.reconfig_method_api_connect
        ReconfigMethod.API_GATEWAY -> R.string.reconfig_method_api_gateway
        ReconfigMethod.CUSTOM -> R.string.reconfig_method_custom
    },
)

private fun targetErrorRes(error: ReconfigTargetError?): Int? = when (error) {
    ReconfigTargetError.EMPTY -> R.string.reconfig_sheet_error_empty
    ReconfigTargetError.TOO_LONG -> R.string.reconfig_sheet_error_long
    ReconfigTargetError.INVALID_CHARACTERS -> R.string.reconfig_sheet_error_chars
    ReconfigTargetError.INVALID_DOMAIN -> R.string.reconfig_sheet_error_domain
    ReconfigTargetError.IPV6_NOT_SUPPORTED -> R.string.reconfig_sheet_error_ipv6
    null -> null
}
