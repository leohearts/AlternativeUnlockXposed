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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
                SettingsBase()
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

class SnackbarQueue {
    val hostState = SnackbarHostState()
    private var pending by mutableStateOf<List<String>>(emptyList())
    fun enqueue(message: String) {
        pending = pending + message
    }

    @Composable
    fun Bind(scope: CoroutineScope) {
        LaunchedEffect(pending) {
            val next = pending.firstOrNull() ?: return@LaunchedEffect
            hostState.currentSnackbarData?.dismiss()
            scope.launch {
                hostState.showSnackbar(next)
                pending = pending.drop(1)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipableHost(hostState: SnackbarHostState) {
    SnackbarHost(hostState = hostState) { data ->
        val dismissState = rememberSwipeToDismissBoxState()
        LaunchedEffect(dismissState.currentValue) {
            if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
                data.dismiss()
            }
        }
        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = { },
            enableDismissFromStartToEnd = true,
            enableDismissFromEndToStart = true,
        ) {
            Snackbar(snackbarData = data)
        }
    }
}

fun Properties.getBooleanProperty(key: String, defaultValue: Boolean = false): Boolean =
    getProperty(key, defaultValue.toString()).toBoolean()

fun Properties.setBooleanProperty(key: String, value: Boolean) {
    setProperty(key, value.toString())
}

fun saveConfig(
    context: Context,
    config: Properties,
    enqueueSnackbar: (String) -> Unit,
) {
    val process = RootShell.sudo("cat > ${HookClass.CONFIG_PATH}")
    if (process != null) {
        config.store(process.outputStream, "")
        setPermission()
        enqueueSnackbar(context.getString(R.string.saved_to_config))
    } else {
        Log.e(HookClass.TAG, "saveConfig: failed to start su process")
        enqueueSnackbar(context.getString(R.string.save_failed_message))
    }
}

fun refreshingSave(
    context: Context, config: Properties, enqueueSnackbar: (String) -> Unit, onSaved: () -> Unit
) {
    saveConfig(context, config, enqueueSnackbar)
    onSaved()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SegmentWrapper(
    modifier: Modifier = Modifier,
    index: Int = 0,
    count: Int = 1,
    icon: Painter,
    title: String,
    description: String,
    monospace: Boolean = false,
    onClick: (() -> Unit),
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    SegmentedListItem(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        leadingContent = { Icon(icon, contentDescription = title) },
        trailingContent = trailing,
        supportingContent = {
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
        },
        content = { Text(title) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBase() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarQueue = remember { SnackbarQueue() }
    snackbarQueue.Bind(scope)
    Scaffold(snackbarHost = { SwipableHost(snackbarQueue.hostState) }, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, actions = {})
    }) { innerPadding ->
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxSize()
                .padding(innerPadding)
                .padding(start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
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
                SegmentedListItem(
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                    content = { Text(stringResource(R.string.no_root_message)) },
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
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

            val fakePasswordLabel = stringResource(R.string.fake_password)
            val realPasswordLabel = stringResource(R.string.real_password)
            val actionTypeTitleLabel = stringResource(R.string.action_type_title)
            val actionTypeHintLabel = stringResource(R.string.action_type_hint)
            val commandTitleLabel = stringResource(R.string.command_title)
            val commandHintLabel = stringResource(R.string.command_hint)
            val restartingSystemUILabel = stringResource(R.string.restarting_systemui)

            SmallHeading(stringResource(R.string.section_password))
            SegmentWrapper(
                title = stringResource(R.string.fake_password),
                description = config.getProperty("fakePassword", stringResource(R.string.not_set)),
                icon = painterResource(R.drawable.mask_filled),
                index = 0,
                count = 2,
                onClick = {
                    openDialog.value = true
                    setTitle.value = fakePasswordLabel
                    setKey.value = "fakePassword"
                    setHint.value = ""
                },
                onLongClick = {
                    config.remove("fakePassword")
                    refreshingSave(context, config, snackbarQueue::enqueue) { refreshTrigger++ }
                },
            )
            SegmentWrapper(
                title = stringResource(R.string.real_password),
                description = if (hideUIPassword) stringResource(R.string.masked_password) else config.getProperty(
                    "realPassword", stringResource(R.string.not_set)
                ),
                icon = rememberVectorPainter(Icons.Rounded.Person),
                index = 1,
                count = 2,
                onClick = {
                    openDialog.value = true
                    setTitle.value = realPasswordLabel
                    setKey.value = "realPassword"
                    setHint.value = ""
                },
                onLongClick = {
                    config.remove("realPassword")
                    refreshingSave(context, config, snackbarQueue::enqueue) { refreshTrigger++ }
                },
            )

            SmallHeading(text = stringResource(R.string.section_action))
            SegmentWrapper(
                title = stringResource(R.string.action_type_title),
                description = config.getProperty("actionType", "sh"),
                icon = rememberVectorPainter(Icons.Rounded.AdminPanelSettings),
                index = 0,
                count = 2,
                monospace = true,
                onClick = {
                    openDialog.value = true
                    setTitle.value = actionTypeTitleLabel
                    setKey.value = "actionType"
                    setHint.value = actionTypeHintLabel
                },
                onLongClick = {
                    config.remove("actionType")
                    refreshingSave(context, config, snackbarQueue::enqueue) { refreshTrigger++ }
                },
            )
            SegmentWrapper(
                title = stringResource(R.string.command_title),
                description = config.getProperty("actionCommand", "whoami"),
                icon = rememberVectorPainter(Icons.Rounded.Terminal),
                index = 1,
                count = 2,
                monospace = true,
                onClick = {
                    openDialog.value = true
                    setTitle.value = commandTitleLabel
                    setKey.value = "actionCommand"
                    setHint.value = commandHintLabel
                },
                onLongClick = {
                    config.remove("actionCommand")
                    refreshingSave(context, config, snackbarQueue::enqueue) { refreshTrigger++ }
                },
            )

            SmallHeading(text = stringResource(R.string.section_debug))
            SegmentWrapper(
                title = stringResource(R.string.dynamic_load_title),
                description = if (!dynamicLoadchecked) stringResource(R.string.dynamic_load_manual) else stringResource(
                    R.string.dynamic_load_always
                ),
                icon = rememberVectorPainter(Icons.Rounded.Refresh),
                index = 0,
                count = 2,
                onClick = {
                    dynamicLoadchecked = !dynamicLoadchecked
                    config.setBooleanProperty("dynamicLoad", dynamicLoadchecked)
                    refreshingSave(context, config, snackbarQueue::enqueue) { refreshTrigger++ }
                },
                onLongClick = {
                    dynamicLoadchecked = false
                    config.setBooleanProperty("dynamicLoad", false)
                    refreshingSave(context, config, snackbarQueue::enqueue) { refreshTrigger++ }
                },
                trailing = {
                    Switch(
                        checked = dynamicLoadchecked, onCheckedChange = {
                            dynamicLoadchecked = it
                            config.setBooleanProperty("dynamicLoad", it)
                            refreshingSave(
                                context, config, snackbarQueue::enqueue
                            ) { refreshTrigger++ }
                        })
                },
            )
            SegmentWrapper(
                title = stringResource(R.string.restart_systemui_title),
                description = stringResource(R.string.restart_command),
                icon = rememberVectorPainter(Icons.Rounded.Close),
                index = 1,
                count = 2,
                monospace = true,
                onClick = {
                    RootShell.sudo("killall com.android.systemui")
                    snackbarQueue.enqueue(restartingSystemUILabel)
                },
            )

            SmallHeading(text = stringResource(R.string.section_interface))
            SegmentWrapper(
                title = stringResource(R.string.hide_ui_password_title),
                description = stringResource(R.string.hide_ui_password_desc),
                icon = rememberVectorPainter(if (!hideUIPassword) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff),
                index = 0,
                count = 1,
                onClick = {
                    hideUIPassword = !hideUIPassword
                    config.setBooleanProperty("hideUIPassword", hideUIPassword)
                    refreshingSave(context, config, snackbarQueue::enqueue) { refreshTrigger++ }
                },
                onLongClick = {
                    hideUIPassword = false
                    config.setBooleanProperty("hideUIPassword", false)
                    refreshingSave(context, config, snackbarQueue::enqueue) { refreshTrigger++ }
                },
                trailing = {
                    Switch(
                        checked = hideUIPassword, onCheckedChange = {
                            hideUIPassword = it
                            config.setBooleanProperty("hideUIPassword", it)
                            refreshingSave(
                                context, config, snackbarQueue::enqueue
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
                                context, config, snackbarQueue::enqueue
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
