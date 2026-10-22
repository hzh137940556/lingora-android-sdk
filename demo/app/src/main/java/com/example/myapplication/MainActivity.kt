package com.example.myapplication

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.annotation.ArrayRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.ui.theme.MyApplicationTheme
import io.lingora.android.Lingora
import io.lingora.android.LingoraLoadState
import io.lingora.android.LingoraSource
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val lingora = Lingora.instance
        lifecycleScope.launch { lingora.preload() }

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TranslationTestPage(
                        lingora = lingora,
                        onRefresh = { lifecycleScope.launch { lingora.refresh() } },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun TranslationTestPage(
    lingora: Lingora,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by lingora.loadState.collectAsState()
    val currentLocale by lingora.locale.collectAsState()
    val availableLocales by lingora.availableLocaleTags.collectAsState()
    val welcome = lingoraStringResource(lingora, R.string.welcome)
    val welcomeName = lingoraStringResource(lingora, R.string.welcome_name, "Android")
    val localNotice = lingoraStringResource(lingora, R.string.local_notice)
    val commonNotice = lingoraStringResource(
        lingora,
        R.string.common_notice,
        namespace = "common",
    )
    val quantity = lingoraPluralStringResource(lingora, R.plurals.items_count, 3)
    val weekdays = lingoraStringArrayResource(lingora, R.array.weekdays)
    val months = lingoraStringArrayResource(lingora, R.array.en_month)
    var selectedTranslation by remember { mutableStateOf<TranslationExample?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            lingoraStringResource(lingora, R.string.demo_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(lingoraStringResource(lingora, R.string.demo_description))
        StatusCard(lingora = lingora, state = state, onRefresh = onRefresh)
        Text(
            lingoraStringResource(
                lingora,
                R.string.current_locale,
                currentLocale.toLanguageTag(),
            )
        )
        Text(lingoraStringResource(lingora, R.string.available_locales))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            availableLocales.forEach { languageTag ->
                val isSelected = currentLocale.toLanguageTag()
                    .equals(languageTag, ignoreCase = true)
                OutlinedButton(
                    onClick = { lingora.setLocale(languageTag) },
                    modifier = Modifier.semantics { selected = isSelected },
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    ),
                    colors = if (isSelected) {
                        ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                ) {
                    Text(languageTag)
                }
            }
        }
        Text(lingoraStringResource(lingora, R.string.tap_key_hint))
        Text(lingoraStringResource(lingora, R.string.namespace_app))
        TranslationButton("welcome", welcome) { selectedTranslation = it }
        TranslationButton("welcome_name", welcomeName) { selectedTranslation = it }
        TranslationButton("local_notice", localNotice) { selectedTranslation = it }
        TranslationButton("items_count", quantity) { selectedTranslation = it }
        TranslationButton("weekdays", weekdays.joinToString()) { selectedTranslation = it }
        TranslationButton("en_month", months.joinToString()) { selectedTranslation = it }
        Text(lingoraStringResource(lingora, R.string.namespace_common))
        TranslationButton("common_notice", commonNotice) { selectedTranslation = it }
    }

    selectedTranslation?.let { translation ->
        AlertDialog(
            onDismissRequest = { selectedTranslation = null },
            title = { Text(translation.key) },
            text = { Text(translation.value) },
            confirmButton = {
                TextButton(onClick = { selectedTranslation = null }) {
                    Text(lingoraStringResource(lingora, R.string.close))
                }
            },
        )
    }
}

@Composable
private fun StatusCard(
    lingora: Lingora,
    state: LingoraLoadState,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val message = when (state) {
                LingoraLoadState.Idle -> lingoraStringResource(lingora, R.string.loading)
                LingoraLoadState.Loading -> lingoraStringResource(lingora, R.string.loading)
                is LingoraLoadState.Ready -> when (state.source) {
                    LingoraSource.NETWORK -> {
                        lingoraStringResource(lingora, R.string.source_network)
                    }
                    LingoraSource.CACHE -> lingoraStringResource(lingora, R.string.source_cache)
                }
                is LingoraLoadState.UsingApkFallback -> state.reason
            }
            Text(message)
            Button(onClick = onRefresh, enabled = state !is LingoraLoadState.Loading) {
                Text(lingoraStringResource(lingora, R.string.refresh))
            }
        }
    }
}

private data class TranslationExample(val key: String, val value: String)

@Composable
private fun TranslationButton(
    key: String,
    value: String,
    onClick: (TranslationExample) -> Unit,
) {
    Button(
        onClick = { onClick(TranslationExample(key, value)) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(key)
    }
}

@Preview(showBackground = true)
@Composable
private fun TranslationButtonPreview() {
    MyApplicationTheme {
        TranslationButton("welcome", "Welcome from the APK fallback") {}
    }
}

@Composable
private fun lingoraStringResource(
    lingora: Lingora,
    @StringRes id: Int,
    vararg formatArgs: Any,
    namespace: String = "app",
): String {
    val context = lingoraContext(lingora)
    return lingora.tFlow(
        context,
        id,
        *formatArgs,
        namespace = namespace,
    ).collectAsState(
        initial = context.getString(id, *formatArgs),
    ).value
}

@Composable
private fun lingoraPluralStringResource(
    lingora: Lingora,
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String {
    val resources = lingoraContext(lingora).resources
    return lingora.tPluralFlow(resources, id, quantity, *formatArgs).collectAsState(
        initial = resources.getQuantityString(id, quantity, quantity, *formatArgs),
    ).value
}

@Composable
private fun lingoraStringArrayResource(
    lingora: Lingora,
    @ArrayRes id: Int,
): Array<String> {
    val resources = lingoraContext(lingora).resources
    return lingora.tArrayFlow(resources, id).collectAsState(
        initial = resources.getStringArray(id).toList(),
    ).value.toTypedArray()
}

@Composable
private fun lingoraContext(lingora: Lingora): Context {
    val context = LocalContext.current
    val locale by lingora.locale.collectAsState()
    return remember(context, locale) {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
        }
        context.createConfigurationContext(configuration)
    }
}
