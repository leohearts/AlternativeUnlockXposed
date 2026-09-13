@file:Suppress("SpellCheckingInspection")

package com.leohearts.alternativeunlockhook

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.leohearts.alternativeunlockhook.ui.theme.AlternativeUnlockXposedTheme
import com.leohearts.alternativeunlockhook.ui.theme.CardPosition
import com.leohearts.alternativeunlockhook.ui.theme.GroupedListSpacing
import com.leohearts.alternativeunlockhook.ui.theme.GroupedRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Properties

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            AlternativeUnlockXposedTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
                ) {
                    SettingsBase()
                }
            }
            migrateOldConfig()
        }
    }
}

fun sudoFileExists(path: String): Boolean {
    val process = RootShell.sudo("cat $path") ?: return false
    return try {
        BufferedReader(InputStreamReader(process.inputStream)).readLine().chars().count() > 0
    } catch (_: Exception) {
        false
    }
}


fun setPermission() {
    RootShell.sudo("chown `stat /data/data/com.android.systemui/ -c %u`:0 ${HookClass.CONFIG_PATH}; chmod 770 ${HookClass.CONFIG_PATH}")
}

@SuppressLint("SdCardPath")
fun migrateOldConfig() {
    if ((sudoFileExists("/data/data/com.android.systemui/alternativePass.properties")) and (!sudoFileExists(
            HookClass.CONFIG_PATH
        ))
    ) {
        Log.w(HookClass.TAG, "migrateOldConfig: migrating from old config file")
        RootShell.sudo("mv /data/data/com.android.systemui/alternativePass.properties ${HookClass.CONFIG_PATH}")
        setPermission()
    }
}

@Composable
fun SmallHeading(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp),
        fontSize = 2.5.em,
        fontWeight = FontWeight.Medium
    )
}

fun Properties.getBooleanProperty(key: String, defaultValue: Boolean = false): Boolean =
    getProperty(key, defaultValue.toString()).toBoolean()

fun Properties.setBooleanProperty(key: String, value: Boolean) {
    setProperty(key, value.toString())
}

fun saveConfig(
    context: Context,
    config: Properties,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState
) {
    val process = RootShell.sudo("cat > ${HookClass.CONFIG_PATH}")
    if (process != null) {
        config.store(process.outputStream, "")
        setPermission()
        scope.launch {
            snackbarHostState.showSnackbar(context.getString(R.string.saved_to_config))
        }
    } else {
        Log.e(HookClass.TAG, "saveConfig: failed to start su process")
        scope.launch {
            snackbarHostState.showSnackbar(context.getString(R.string.save_failed_message))
        }
    }
}

fun refreshingSave(
    context: Context,
    config: Properties,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    onSaved: () -> Unit
) {
    saveConfig(context, config, scope, snackbarHostState)
    onSaved()
}

@Composable
fun GroupedWrapper(
    modifier: Modifier = Modifier,
    position: CardPosition = CardPosition.Solo,
    icon: Painter,
    title: String,
    description: String,
    monospace: Boolean = false,
    onClick: (() -> Unit),
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    GroupedRow(
        modifier = modifier,
        position = position,
        onClick = { onClick() },
        onLongClick = onLongClick,
    ) {
        Icon(icon, contentDescription = null)
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = if (trailing != null) Modifier.weight(1f) else Modifier,
        ) {
            Text(title)
            if (description.isEmpty()) {
                Text(
                    stringResource(R.string.value_empty),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (monospace) {
                        FontFamily(Typeface(android.graphics.Typeface.MONOSPACE))
                    } else {
                        FontFamily.Default
                    },
                )

            }
        }
        trailing?.invoke(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBase(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }, topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            actions = {},
            modifier = modifier.padding(vertical = 10.dp)
        )
    }) { innerPadding ->
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxSize()
                .padding(innerPadding)
                .padding(start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(GroupedListSpacing)
        ) {
            var refreshTrigger by remember { mutableIntStateOf(0) }
            val (config, noRoot) = remember(refreshTrigger) {
                val props = Properties()
                val process = RootShell.sudo("cat ${HookClass.CONFIG_PATH}")
                if (process != null) {
                    props.load(process.inputStream)
                }
                props to (process == null)
            }
            if (noRoot) {
                GroupedRow(position = CardPosition.Solo) {
                    Text(stringResource(R.string.no_root_message))
                }
            }

            val openDialog = remember { mutableStateOf(false) }
            val setTitle = rememberSaveable { mutableStateOf("") }
            val setKey = rememberSaveable { mutableStateOf("") }
            val setHint = rememberSaveable { mutableStateOf("") }
            var dynamicLoadchecked by remember {
                mutableStateOf(
                    config.getBooleanProperty("dynamicLoad")
                )
            }
            var hideUIPassword by remember {
                mutableStateOf(
                    config.getBooleanProperty("hideUIPassword")
                )
            }
            SmallHeading(stringResource(R.string.section_password))
            GroupedWrapper(
                title = stringResource(R.string.fake_password),
                description = config.getProperty("fakePassword", stringResource(R.string.not_set)),
                icon = painterResource(R.drawable.mask_filled),
                position = CardPosition.Leading,
                onClick = {
                    openDialog.value = true
                    setTitle.value = context.getString(R.string.fake_password)
                    setKey.value = "fakePassword"
                    setHint.value = ""
                },
                onLongClick = {
                    config.remove("fakePassword")
                    refreshingSave(context, config, scope, snackbarHostState) { refreshTrigger++ }
                },
            )
            GroupedWrapper(
                title = stringResource(R.string.real_password),
                description = if (hideUIPassword) stringResource(R.string.masked_password) else config.getProperty(
                    "realPassword", stringResource(R.string.not_set)
                ),
                icon = rememberVectorPainter(Icons.Rounded.Lock),
                position = CardPosition.Trailing,
                onClick = {
                    openDialog.value = true
                    setTitle.value = context.getString(R.string.real_password)
                    setKey.value = "realPassword"
                    setHint.value = ""
                },
                onLongClick = {
                    config.remove("realPassword")
                    refreshingSave(context, config, scope, snackbarHostState) { refreshTrigger++ }
                },
            )

            SmallHeading(text = stringResource(R.string.section_action))
            GroupedWrapper(
                title = stringResource(R.string.action_type_title),
                description = config.getProperty("actionType", "sh"),
                icon = rememberVectorPainter(Icons.Rounded.AdminPanelSettings),
                position = CardPosition.Leading,
                monospace = true,
                onClick = {
                    openDialog.value = true
                    setTitle.value = context.getString(R.string.action_type_title)
                    setKey.value = "actionType"
                    setHint.value = context.getString(R.string.action_type_hint)
                },
                onLongClick = {
                    config.remove("actionType")
                    refreshingSave(context, config, scope, snackbarHostState) { refreshTrigger++ }
                },
            )
            GroupedWrapper(
                title = stringResource(R.string.command_title),
                description = config.getProperty("actionCommand", "whoami"),
                icon = rememberVectorPainter(Icons.Rounded.Terminal),
                position = CardPosition.Trailing,
                monospace = true,
                onClick = {
                    openDialog.value = true
                    setTitle.value = context.getString(R.string.command_title)
                    setKey.value = "actionCommand"
                    setHint.value = context.getString(R.string.command_hint)
                },
                onLongClick = {
                    config.remove("actionCommand")
                    refreshingSave(context, config, scope, snackbarHostState) { refreshTrigger++ }
                },
            )

            SmallHeading(text = stringResource(R.string.section_debug))
            GroupedWrapper(
                title = stringResource(R.string.dynamic_load_title),
                description = if (!dynamicLoadchecked) stringResource(R.string.dynamic_load_manual) else stringResource(
                    R.string.dynamic_load_always
                ),
                icon = rememberVectorPainter(Icons.Rounded.Refresh),
                position = CardPosition.Leading,
                onClick = {
                    dynamicLoadchecked = !dynamicLoadchecked
                    config.setBooleanProperty("dynamicLoad", dynamicLoadchecked)
                    refreshingSave(context, config, scope, snackbarHostState) { refreshTrigger++ }
                },
                onLongClick = {
                    dynamicLoadchecked = false
                    config.setBooleanProperty("dynamicLoad", false)
                    refreshingSave(context, config, scope, snackbarHostState) { refreshTrigger++ }
                },
                trailing = {
                    Switch(
                        checked = dynamicLoadchecked, onCheckedChange = {
                            dynamicLoadchecked = it
                            config.setBooleanProperty("dynamicLoad", it)
                            refreshingSave(
                                context, config, scope, snackbarHostState
                            ) { refreshTrigger++ }
                        })
                },
            )
            GroupedWrapper(
                title = stringResource(R.string.restart_systemui_title),
                description = stringResource(R.string.restart_command),
                icon = rememberVectorPainter(Icons.Rounded.Close),
                position = CardPosition.Trailing,
                monospace = true,
                onClick = {
                    RootShell.sudo("killall com.android.systemui")
                    scope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.restarting_systemui))
                    }
                },
            )

            SmallHeading(text = stringResource(R.string.section_interface))
            GroupedWrapper(
                title = stringResource(R.string.hide_ui_password_title),
                description = stringResource(R.string.hide_ui_password_desc),
                icon = rememberVectorPainter(if (!hideUIPassword) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff),
                position = CardPosition.Solo,
                onClick = {
                    hideUIPassword = !hideUIPassword
                    config.setBooleanProperty("hideUIPassword", hideUIPassword)
                    refreshingSave(context, config, scope, snackbarHostState) { refreshTrigger++ }
                },
                onLongClick = {
                    hideUIPassword = false
                    config.setBooleanProperty("hideUIPassword", false)
                    refreshingSave(context, config, scope, snackbarHostState) { refreshTrigger++ }
                },
                trailing = {
                    Switch(
                        checked = hideUIPassword, onCheckedChange = {
                            hideUIPassword = it
                            config.setBooleanProperty("hideUIPassword", it)
                            refreshingSave(
                                context, config, scope, snackbarHostState
                            ) { refreshTrigger++ }
                        })
                },
            )

            if (openDialog.value) {
                AlertDialog(onDismissRequest = {
                    openDialog.value = false
                }, title = {
                    Text(text = setTitle.value)
                }, text = {
                    val value = rememberSaveable {
                        mutableStateOf(
                            config.getProperty(
                                setKey.value, ""
                            )
                        )
                    }
                    LazyColumn {
                        item {
                            OutlinedTextField(
                                label = { Text(setTitle.value) },
                                value = value.value,
                                onValueChange = {
                                    config.setProperty(setKey.value, it)
                                    value.value = it
                                },
                            )
                            Text(setHint.value)
                        }
                    }
                }, confirmButton = {
                    TextButton(
                        onClick = {
                            refreshingSave(
                                context, config, scope, snackbarHostState
                            ) { refreshTrigger++ }
                            openDialog.value = false
                        }) {
                        Text(stringResource(R.string.confirm))
                    }
                }, dismissButton = {
                    TextButton(
                        onClick = {
                            openDialog.value = false
                        }) {
                        Text(stringResource(R.string.cancel))
                    }
                })
            }
        }
    }
}
