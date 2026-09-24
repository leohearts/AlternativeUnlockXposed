package com.leohearts.alternativeUnlockHook

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.leohearts.alternativeUnlockHook.ui.theme.AlternativeUnlockXposedTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.Properties
import java.util.concurrent.TimeUnit

val TAG: String = "alternativeUnlockHook"
val CONFIG_PATH: String = "/data/local/tmp/alternativePass.properties"
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Runtime.getRuntime().exec("su -c 'id > /data/local/tmp/qwq'")
            migrateOldConfig()
            AlternativeUnlockXposedTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsBase()
                }
            }
        }
    }
}

fun sudoFileExists(path: String): Boolean {
    try {
        return BufferedReader(InputStreamReader(sudo("cat " + path).inputStream)).readLine()
            .chars().count() > 0
    }
    catch (e: Exception) {
        return false;
    }
}

fun migrateOldConfig() {
    if (
        (sudoFileExists("/data/data/com.android.systemui/alternativePass.properties"))
        and
        (! sudoFileExists(CONFIG_PATH))

    ) {
        Log.w(TAG, "migrateOldConfig: migrating from old config file")
        sudo("mv /data/data/com.android.systemui/alternativePass.properties ${CONFIG_PATH}")
        setPermission()
    }
}

fun sudo(cmd: String): Process {
    return Runtime.getRuntime().exec(listOf<String>("su", "-c", cmd).toTypedArray())
}

@Composable
fun smallTitle(text: String): Unit {
    return Text(text, modifier = Modifier.padding(horizontal = 16.dp), fontSize = 2.5.em, fontWeight = FontWeight.Medium)
}

@Composable
fun listDivider(): Unit {
    return Divider(modifier = Modifier
        .padding(horizontal = 16.dp)
        .padding(vertical = 24.dp))
}
fun setPermission() {
//    sudo("chown system:system /data/data/com.android.systemui/alternativePass.properties;setenforce 0;chcon u:object_r:platform_app:s0 /data/data/com.android.systemui/alternativePass.properties;setenforce 1")
    sudo("chown `stat /data/data/com.android.systemui/ -c %u`:0 ${CONFIG_PATH}; chmod 770 ${CONFIG_PATH}")
}
// Load the config exactly once per settings screen open. A missing file (first run) yields
// empty properties, which is fine; a real read failure (su denied, timeout, ...) throws, so
// callers can refuse to edit and never overwrite the existing file with defaults.
fun loadConfig(): Result<Properties> {
    return runCatching {
        val p = sudo("cat ${CONFIG_PATH}")
        val content = p.inputStream.readBytes()
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw IOException("timed out waiting for su (grant dialog pending?)")
        }
        if (p.exitValue() != 0 && !err.contains("No such file")) {
            throw IOException("su cat failed (exit ${p.exitValue()}): $err")
        }
        Properties().apply { load(content.inputStream()) }
    }
}

fun saveConfig(config: Properties, scope: CoroutineScope, snackbarHostState: SnackbarHostState) {
    scope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val p = sudo("cat > ${CONFIG_PATH}")
                config.store(p.outputStream, "")
                // closing the stream sends EOF so cat finishes the file and exits
                p.outputStream.close()
                if (!p.waitFor(30, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    false
                } else {
                    if (p.exitValue() == 0) setPermission()
                    p.exitValue() == 0
                }
            }.getOrDefault(false)
        }
        snackbarHostState.showSnackbar(
            if (ok) "Saved to config file" else "Failed to save config file"
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBase( modifier: Modifier = Modifier) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // null = loading; failure = read error. Editing UI only renders on success, so a failed
    // su call can never be mistaken for an empty config and later overwrite the real file.
    var configResult by remember { mutableStateOf<Result<Properties>?>(null) }
    LaunchedEffect(Unit) {
        configResult = withContext(Dispatchers.IO) { loadConfig() }
    }
    Scaffold (
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(
            title = { Text("Alternative Unlock Settings")},
            actions = {},
            modifier = modifier.padding(vertical = 10.dp)
        ) }
    ) { innerPadding ->
        val loaded = configResult
        if (loaded == null) {
            Text("Loading config...", modifier = Modifier.padding(innerPadding).padding(16.dp))
        } else if (loaded.isFailure) {
            Text(
                "Failed to load the config file (${loaded.exceptionOrNull()?.message}). Editing is disabled so your existing config will not be overwritten.",
                modifier = Modifier.padding(innerPadding).padding(16.dp),
                color = MaterialTheme.colorScheme.error
            )
        } else {
            val config = loaded.getOrThrow()
            LazyColumn(
                contentPadding = innerPadding
            ) {
            item {

                val openDialog = remember { mutableStateOf(false) }
                val setTitle = rememberSaveable { mutableStateOf("") }
                val setKey = rememberSaveable { mutableStateOf("") }
                val setHint = rememberSaveable { mutableStateOf("") }

                var dynamicLoadchecked by remember {
                    mutableStateOf(
                        config.getProperty(
                            "dynamicLoad",
                            "false"
                        )
                    )
                }
                var hideUIPassword by remember {
                    mutableStateOf(
                        config.getProperty(
                            "hideUIPassword",
                            "false"
                        )
                    )
                }
                var useRegex by remember {
                    mutableStateOf(
                        config.getProperty(
                            "useRegex",
                            "false"
                        )
                    )
                }
                var skipRealPassword by remember {
                    mutableStateOf(
                        config.getProperty(
                            "skipRealPassword",
                            "true"
                        )
                    )
                }
                var pamStyle by remember {
                    mutableStateOf(
                        config.getProperty(
                            "pamStyle",
                            "false"
                        )
                    )
                }

                smallTitle("Password")
                Surface(onClick = {
                    openDialog.value = true
                    setTitle.value = "Fake Password"
                    setKey.value = "fakePassword"
                    setHint.value = "Supports regex when 'Use regex' is enabled. Example: .+ matches any input"
                }) {
                    ListItem(
                        headlineContent = { Text("Fake Password") },
                        supportingContent = { Text(config.getProperty("fakePassword", "Not set")) },
                        leadingContent = {
                            Icon(
                                Icons.Rounded.Face,
                                contentDescription = "Localized description",
                            )
                        }
                    )
                }

                Surface(onClick = {
                    useRegex = if (useRegex == "false") "true" else "false"
                    config.setProperty("useRegex", useRegex)
                    saveConfig(config, scope, snackbarHostState)
                }) {
                    ListItem(
                        headlineContent = { Text("Use regex") },
                        supportingContent = { Text("Match the fake password as a regular expression (full match), so .+ matches any input. Keep off if your fake password contains regex special characters.") },
                        leadingContent = {
                            Icon(
                                Icons.Rounded.Settings,
                                contentDescription = "Localized description",
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = (useRegex == "true"),
                                onCheckedChange = {
                                    useRegex = if (it) "true" else "false"
                                    config.setProperty("useRegex", useRegex)
                                    saveConfig(config, scope, snackbarHostState)
                                }
                            )
                        }
                    )
                }

                Surface(onClick = {
                    openDialog.value = true
                    setTitle.value = "Real Password"
                    setKey.value = "realPassword"
                    setHint.value = ""
                }) {
                    ListItem(
                        headlineContent = { Text("Real Password") },
                        supportingContent = { Text(if (hideUIPassword == "true") "******"  else config.getProperty("realPassword", "Not set")) },
                        leadingContent = {
                            Icon(
                                Icons.Rounded.Lock,
                                contentDescription = "Localized description",
                            )
                        }
                    )
                }

                Surface(onClick = {
                    skipRealPassword = if (skipRealPassword == "false") "true" else "false"
                    config.setProperty("skipRealPassword", skipRealPassword)
                    saveConfig(config, scope, snackbarHostState)
                }) {
                    ListItem(
                        headlineContent = { Text("Skip real password") },
                        supportingContent = { Text("Never run the command when the entered credential equals the real password, so the real password can't leak into AU_INPUT or lock you out. Recommended when the fake password regex matches everything.") },
                        leadingContent = {
                            Icon(
                                Icons.Rounded.Lock,
                                contentDescription = "Localized description",
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = (skipRealPassword == "true"),
                                onCheckedChange = {
                                    skipRealPassword = if (it) "true" else "false"
                                    config.setProperty("skipRealPassword", skipRealPassword)
                                    saveConfig(config, scope, snackbarHostState)
                                }
                            )
                        }
                    )
                }

                listDivider()

                smallTitle(text = "Action")
                Surface(onClick = {
                    openDialog.value = true
                    setTitle.value = "When fake password provided"
                    setKey.value = "actionType"
                    setHint.value =
                        "Available options: \nsh: run command with SystemUI permission (platform_app)\nsudo: run command with root"
                }) {
                    ListItem(
                        headlineContent = { Text("When fake password provided") },
                        supportingContent = { Text(config.getProperty("actionType", "sh")) },
                        leadingContent = {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Localized description",
                            )
                        }
                    )
                }

                Surface(onClick = {
                    openDialog.value = true
                    setTitle.value = "Command"
                    setKey.value = "actionCommand"
                    setHint.value = "Command to execute. With 'PAM style unlock' enabled, the entered credential is passed via the AU_INPUT environment variable; exit code 0 unlocks, anything else doesn't."
                }) {
                    ListItem(
                        headlineContent = { Text("Command") },
                        supportingContent = { Text(config.getProperty("actionCommand",  "whoami")) },
                        leadingContent = {
                            Icon(
                                Icons.Rounded.ArrowForward,
                                contentDescription = "Localized description",
                            )
                        }
                    )
                }

                Surface(onClick = {
                    pamStyle = if (pamStyle == "false") "true" else "false"
                    config.setProperty("pamStyle", pamStyle)
                    saveConfig(config, scope, snackbarHostState)
                }) {
                    ListItem(
                        headlineContent = { Text("PAM style unlock") },
                        supportingContent = { Text("Pass the entered credential to the command via the AU_INPUT environment variable; exit code 0 unlocks, anything else doesn't. When off, the command runs fire-and-forget like older versions.") },
                        leadingContent = {
                            Icon(
                                Icons.Rounded.Settings,
                                contentDescription = "Localized description",
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = (pamStyle == "true"),
                                onCheckedChange = {
                                    pamStyle = if (it) "true" else "false"
                                    config.setProperty("pamStyle", pamStyle)
                                    saveConfig(config, scope, snackbarHostState)
                                }
                            )
                        }
                    )
                }

                if (pamStyle == "true") { // only usable in PAM mode
                    Surface(onClick = {
                        openDialog.value = true
                        setTitle.value = "Command timeout"
                        setKey.value = "commandTimeout"
                        setHint.value = "Seconds to wait for the command's exit code before giving up (giving up counts as not unlocking). Only used when PAM style unlock is enabled."
                    }) {
                        ListItem(
                            headlineContent = { Text("Command timeout (seconds)") },
                            supportingContent = { Text(config.getProperty("commandTimeout", "5")) },
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.Settings,
                                    contentDescription = "Localized description",
                                )
                            }
                        )
                    }
                }

                listDivider()
                smallTitle(text = "Debugging")

                Surface(onClick = {
                    dynamicLoadchecked = if (dynamicLoadchecked == "false") "true" else "false"
                    config.setProperty("dynamicLoad", dynamicLoadchecked)
                    saveConfig(config, scope, snackbarHostState)
                }) {
                    ListItem(
                        headlineContent = { Text("Dynamic config load") },
                        supportingContent = { Text("Load config every time your phone unlocks. Otherwise, you'll need to restart SystemUI to apply changes.") },
                        leadingContent = {
                            Icon(
                                Icons.Rounded.Settings,
                                contentDescription = "Localized description",
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = (dynamicLoadchecked == "true"),
                                onCheckedChange = {
                                    dynamicLoadchecked = if (it) "true" else "false"
                                    config.setProperty("dynamicLoad", dynamicLoadchecked)
                                    saveConfig(config, scope, snackbarHostState)
                                }
                            )
                        }
                    )
                }
                Surface(onClick = {
                    sudo("killall com.android.systemui")
                }) {
                    ListItem(
                        headlineContent = { Text("Restart SystemUI") },
                        supportingContent = { Text("Run killall com.android.systemui") },
                        leadingContent = {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = "Localized description",
                            )
                        }
                    )
                }
                listDivider()
                smallTitle(text = "Interface")

                Surface(onClick = {
                    hideUIPassword = if (hideUIPassword == "false") "true" else "false"
                    config.setProperty("hideUIPassword", hideUIPassword)
                    saveConfig(config, scope, snackbarHostState)
                }) {
                    ListItem(
                        headlineContent = { Text("Hide real password from UI") },
                        supportingContent = { Text("Add some protection to shoulder surfing") },
                        leadingContent = {
                            Icon(
                                Icons.Rounded.Settings,
                                contentDescription = "icon",
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = (hideUIPassword == "true"),
                                onCheckedChange = {
                                    hideUIPassword = if (it) "true" else "false"
                                    config.setProperty("hideUIPassword", hideUIPassword)
                                    saveConfig(config, scope, snackbarHostState)
                                }
                            )
                        }
                    )
                }

                // config part end
                listDivider()

                if (openDialog.value) {
                    // keyed by setKey so reopening the dialog for another option starts with that option's value
                    val value = rememberSaveable(setKey.value) {
                        mutableStateOf(config.getProperty(setKey.value, ""))
                    }
                    if (setKey.value == "actionCommand") {
                        CommandEditDialog(
                            title = setTitle.value,
                            hint = setHint.value,
                            initial = value.value,
                            onConfirm = { newValue ->
                                // empty means unset: remove the key so built-in defaults apply,
                                // instead of writing an empty string that would override them
                                if (newValue.isEmpty()) config.remove(setKey.value)
                                else config.setProperty(setKey.value, newValue)
                                saveConfig(config, scope, snackbarHostState)
                                openDialog.value = false
                            },
                            onDismiss = { openDialog.value = false }
                        )
                    } else {
                    AlertDialog(
                        onDismissRequest = {
                            // Dismiss the dialog when the user clicks outside the dialog or on the back
                            // button. If you want to disable that functionality, simply use an empty
                            // onDismissRequest.
                            openDialog.value = false
                        },
                        title = {
                            Text(text = setTitle.value)
                        },
                        text = {
                            LazyColumn {
                                item {
                                    OutlinedTextField(
                                        label = { Text(setTitle.value) },
                                        value = value.value,
                                        onValueChange = {
                                            value.value = it
                                        })
                                    Text(setHint.value)
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    // empty means unset: remove the key so built-in defaults apply,
                                    // instead of writing an empty string that would override them
                                    if (value.value.isEmpty()) config.remove(setKey.value)
                                    else config.setProperty(setKey.value, value.value)
                                    saveConfig(config, scope, snackbarHostState)
                                    openDialog.value = false
                                }
                            ) {
                                Text("Confirm")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    openDialog.value = false
                                }
                            ) {
                                Text("Cancel")
                            }
                        }
                    )
                    }
                }
            }
            }
        }
    }
}


@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    AlternativeUnlockXposedTheme {
        SettingsBase()
    }
}