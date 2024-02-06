package com.leohearts.alternativeUnlockHook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.leohearts.alternativeUnlockHook.ui.theme.AlternativeUnlockXposedTheme
import java.util.Properties

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Runtime.getRuntime().exec("su -c 'id > /data/local/tmp/qwq'")
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

fun sudo(cmd: String): Process {
    return Runtime.getRuntime().exec(listOf<String>("su", "-c", cmd).toTypedArray())
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBase( modifier: Modifier = Modifier) {
    Scaffold (
        topBar = { TopAppBar(
            title = { Text("Alternative Unlock Settings: " + String(sudo("whoami").inputStream.readAllBytes())) },
            actions = {}
        ) },
        content = { innerPadding ->
            LazyColumn (contentPadding = innerPadding
            ){
                item {
                    var config = Properties();
                    config.load(sudo("cat /data/local/tmp/alternativePass.properties").inputStream)

                    var openDialog = remember { mutableStateOf(false) }
                    var setTitle = rememberSaveable { mutableStateOf("") }
                    var setKey = rememberSaveable { mutableStateOf("") }
                    Surface (onClick = {
                        openDialog.value = true
                        setTitle.value = "Fake Password"
                        setKey.value = "fakePassword"
                        }){
                        ListItem(
                            headlineContent = { Text("Fake Password") },
                            supportingContent = { Text(config.getProperty("fakePassword", "")) },
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.Face,
                                    contentDescription = "Localized description",
                                )
                            }
                        )
                    }

                    Surface (onClick = {
                        openDialog.value = true
                        setTitle.value = "Real Password"
                        setKey.value = "realPassword"
                    }){
                        ListItem(
                            headlineContent = { Text("Real Password") },
                            supportingContent = { Text(config.getProperty("realPassword", "")) },
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.Star,
                                    contentDescription = "Localized description",
                                )
                            }
                        )
                    }

                    Divider()

                    if (openDialog.value) {
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
                                var value = rememberSaveable { mutableStateOf(config.getProperty(setKey.value, "")) }
                                OutlinedTextField(
                                    label = { Text("Fake Password") },
                                    value = value.value,
                                    onValueChange = {
                                        config.setProperty(setKey.value, it)
                                        value.value = it
                                    })
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        config.store(sudo("cat > /data/local/tmp/alternativePass.properties").outputStream, "")
                                        sudo("chown system:system /data/local/tmp/alternativePass.properties")
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
    )
}


@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    AlternativeUnlockXposedTheme {
        SettingsBase()
    }
}