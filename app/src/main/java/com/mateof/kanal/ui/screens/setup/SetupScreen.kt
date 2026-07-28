package com.mateof.kanal.ui.screens.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mateof.kanal.R
import com.mateof.kanal.data.model.SourceType
import com.mateof.kanal.ui.components.ButtonTone
import com.mateof.kanal.ui.components.KanalButton
import com.mateof.kanal.ui.components.KanalChip
import com.mateof.kanal.ui.components.KanalTextField
import com.mateof.kanal.ui.components.StepProgress
import com.mateof.kanal.ui.theme.KanalColors
import com.mateof.kanal.ui.theme.Spacing

@Composable
fun SetupScreen(
    sourceId: String,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    val vm: SetupViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(sourceId) { vm.load(sourceId) }
    LaunchedEffect(state.finished) { if (state.finished) onDone() }

    Row(Modifier.fillMaxSize()) {
        // Left: identity and a short explanation, so the form is not naked.
        Column(
            modifier = Modifier
                .width(360.dp)
                .fillMaxHeight()
                .padding(start = Spacing.screenHorizontal, top = 56.dp, end = 24.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_kanal_mark),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(72.dp)
            )
            Spacer(Modifier.height(20.dp))
            Text(
                if (state.isEditing) "Editar fuente" else "Añade tu primera fuente",
                style = MaterialTheme.typography.headlineMedium,
                color = KanalColors.OnBackground
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "Kanal se conecta a un panel Xtream Codes (incluido Dispatcharr) o a " +
                    "una lista M3U. La guía de programación se descarga en formato XMLTV.",
                style = MaterialTheme.typography.bodyMedium,
                color = KanalColors.OnSurfaceMuted
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "Consejo: pega la URL del servidor tal cual (http://host:puerto). Si trae " +
                    "/player_api.php o parámetros, Kanal los recorta solo.",
                style = MaterialTheme.typography.bodySmall,
                color = KanalColors.OnSurfaceFaint
            )
        }

        // Right: the form.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 40.dp, vertical = 56.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KanalChip(
                    label = "Xtream Codes",
                    selected = state.type == SourceType.XTREAM,
                    onClick = { vm.setType(SourceType.XTREAM) }
                )
                KanalChip(
                    label = "Lista M3U",
                    selected = state.type == SourceType.M3U,
                    onClick = { vm.setType(SourceType.M3U) }
                )
            }

            KanalTextField(
                value = state.name,
                onValueChange = vm::setName,
                label = "Nombre",
                placeholder = "Mi proveedor"
            )

            if (state.type == SourceType.XTREAM) {
                KanalTextField(
                    value = state.url,
                    onValueChange = vm::setUrl,
                    label = "URL del servidor",
                    placeholder = "http://midominio.com:8080"
                )
                KanalTextField(
                    value = state.username,
                    onValueChange = vm::setUsername,
                    label = "Usuario"
                )
                KanalTextField(
                    value = state.password,
                    onValueChange = vm::setPassword,
                    label = "Contraseña",
                    isPassword = true
                )
                KanalTextField(
                    value = state.epgUrl,
                    onValueChange = vm::setEpgUrl,
                    label = "URL de la guía (opcional)",
                    supportingText = "Si se deja vacía se usa xmltv.php del propio servidor."
                )
            } else {
                KanalTextField(
                    value = state.url,
                    onValueChange = vm::setUrl,
                    label = "URL de la lista M3U",
                    placeholder = "http://…/lista.m3u"
                )
                KanalTextField(
                    value = state.epgUrl,
                    onValueChange = vm::setEpgUrl,
                    label = "URL de la guía XMLTV (opcional)",
                    supportingText = "Si la lista anuncia url-tvg, Kanal la coge de ahí."
                )
            }

            KanalTextField(
                value = state.userAgent,
                onValueChange = vm::setUserAgent,
                label = "User-Agent (opcional)",
                supportingText = "Algunos proveedores sólo responden a un agente concreto."
            )

            if (state.message.isNotBlank()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.messageIsError) {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = KanalColors.Error,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = KanalColors.Accent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.messageIsError) KanalColors.Error else KanalColors.Accent
                        )
                    }
                }
            }

            if (state.busy) {
                StepProgress(state.busyLabel, state.progress)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                KanalButton(
                    text = "Probar conexión",
                    onClick = vm::test,
                    enabled = !state.busy && state.canSave
                )
                KanalButton(
                    text = if (state.isEditing) "Guardar y sincronizar" else "Guardar y empezar",
                    onClick = vm::save,
                    icon = Icons.Outlined.Save,
                    tone = ButtonTone.Primary,
                    enabled = !state.busy && state.canSave
                )
                KanalButton(text = "Cancelar", onClick = onCancel, enabled = !state.busy)
            }
        }
    }
}
