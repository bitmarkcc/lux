package acr.browser.lightning.settings.screens

import acr.browser.lightning.R
import acr.browser.lightning.preference.UserPreferencesDataStore
import acr.browser.lightning.resources.ResourceProvider
import acr.browser.lightning.settings.SettingsBottomSheetChooserState
import acr.browser.lightning.settings.SettingsBottomSheetInputState
import acr.browser.lightning.settings.SettingsDialogConfirmationState
import acr.browser.lightning.settings.framework.ClickableOnClick
import acr.browser.lightning.settings.framework.ClickableState
import acr.browser.lightning.settings.framework.SettingsFrameworkPresenter
import acr.browser.lightning.settings.framework.SettingsFrameworkScreen
import acr.browser.lightning.settings.framework.SettingsFrameworkState
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import javax.inject.Inject

class CertimarkSettingsScreen @Inject constructor(
    private val resourceProvider: ResourceProvider,
    private val userPreferencesDataStore: UserPreferencesDataStore,
) {
    val key = "certimark"

    fun createSettingsFrameworkState(): SettingsFrameworkState = SettingsFrameworkState(
        title = resourceProvider.stringResource(R.string.settings_certimark),
        content = listOf(
            ClickableState(
                title = resourceProvider.stringResource(R.string.certimark_api_url),
                summary = { userPreferencesDataStore.certimarkApiUrl.get() },
                onClick = ClickableOnClick.Input(
                    produceState = {
                        SettingsBottomSheetInputState(
                            title = resourceProvider.stringResource(R.string.certimark_api_url),
                            hint = "https://certimark.cc",
                            currentValue = userPreferencesDataStore.certimarkApiUrl.get()
                        )
                    },
                    onValueUpdated = { value ->
                        ClickableOnClick.Action {
                            userPreferencesDataStore.certimarkApiUrl.set(value)
                        }
                    }
                )
            ),
            ClickableState(
                title = resourceProvider.stringResource(R.string.certimark_api_key_pin),
                summary = {
                    val pin = userPreferencesDataStore.certimarkApiKeyPin.get()
                    if (pin.isEmpty()) {
                        resourceProvider.stringResource(R.string.certimark_api_key_pin_empty)
                    } else if (pin.length > 16) {
                        "${pin.take(8)}...${pin.takeLast(8)}"
                    } else {
                        pin
                    }
                },
                onClick = ClickableOnClick.Input(
                    produceState = {
                        SettingsBottomSheetInputState(
                            title = resourceProvider.stringResource(R.string.certimark_api_key_pin),
                            hint = resourceProvider.stringResource(R.string.certimark_api_key_pin_hint),
                            currentValue = userPreferencesDataStore.certimarkApiKeyPin.get()
                        )
                    },
                    onValueUpdated = { value ->
                        ClickableOnClick.Action {
                            userPreferencesDataStore.certimarkApiKeyPin.set(value.lowercase().trim())
                        }
                    }
                )
            ),
            ClickableState(
                title = resourceProvider.stringResource(R.string.certimark_trusted_signers),
                summary = {
                    val signers = userPreferencesDataStore.trustedSigners.get()
                    if (signers.isEmpty()) {
                        resourceProvider.stringResource(R.string.certimark_no_trusted_signers)
                    } else {
                        "${signers.size} keys"
                    }
                },
                onClick = ClickableOnClick.ItemSelector(
                    produceState = {
                        val signers = userPreferencesDataStore.trustedSigners.get().toList()
                        val displayValues = if (signers.isEmpty()) {
                            listOf(resourceProvider.stringResource(R.string.certimark_no_trusted_signers))
                        } else {
                            signers.map { key ->
                                if (key.length > 16) "${key.take(8)}...${key.takeLast(8)}" else key
                            }
                        }
                        SettingsBottomSheetChooserState(
                            title = resourceProvider.stringResource(R.string.certimark_trusted_signers),
                            values = displayValues,
                            selected = -1
                        )
                    },
                    onSelected = { index ->
                        val signers = userPreferencesDataStore.trustedSigners.get().toList()
                        if (index in signers.indices) {
                            val key = signers[index]
                            val shortKey = if (key.length > 16) "${key.take(8)}...${key.takeLast(8)}" else key
                            ClickableOnClick.Confirmation(
                                produceState = {
                                    SettingsDialogConfirmationState(
                                        title = "Remove signing key?",
                                        message = "Remove trusted signing key $shortKey?",
                                        positiveAction = "Remove",
                                        negativeAction = "Cancel"
                                    )
                                },
                                onConfirmed = { confirmed ->
                                    ClickableOnClick.Action {
                                        if (confirmed) {
                                            val current = userPreferencesDataStore.trustedSigners.get().toMutableSet()
                                            current.remove(key)
                                            userPreferencesDataStore.trustedSigners.set(current)
                                        }
                                    }
                                }
                            )
                        } else {
                            ClickableOnClick.None
                        }
                    }
                )
            ),
            ClickableState(
                title = resourceProvider.stringResource(R.string.certimark_trusted_certs_title),
                summary = {
                    val certs = userPreferencesDataStore.trustedCerts.get()
                    if (certs.isEmpty()) {
                        resourceProvider.stringResource(R.string.certimark_no_trusted_certs)
                    } else {
                        "${certs.size} certificates"
                    }
                },
                onClick = ClickableOnClick.ItemSelector(
                    produceState = {
                        val certs = userPreferencesDataStore.trustedCerts.get().toList()
                        val displayValues = if (certs.isEmpty()) {
                            listOf(resourceProvider.stringResource(R.string.certimark_no_trusted_certs))
                        } else {
                            certs.map { entry ->
                                val parts = entry.split(":", limit = 2)
                                val domain = parts[0]
                                val hash = parts.getOrElse(1) { "" }
                                val shortHash = if (hash.length > 16) "${hash.take(8)}...${hash.takeLast(8)}" else hash
                                "$domain — $shortHash"
                            }
                        }
                        SettingsBottomSheetChooserState(
                            title = resourceProvider.stringResource(R.string.certimark_trusted_certs_title),
                            values = displayValues,
                            selected = -1
                        )
                    },
                    onSelected = { index ->
                        val certs = userPreferencesDataStore.trustedCerts.get().toList()
                        if (index in certs.indices) {
                            val entry = certs[index]
                            val parts = entry.split(":", limit = 2)
                            val domain = parts[0]
                            val hash = parts.getOrElse(1) { "" }
                            val shortHash = if (hash.length > 16) "${hash.take(8)}...${hash.takeLast(8)}" else hash
                            ClickableOnClick.Confirmation(
                                produceState = {
                                    SettingsDialogConfirmationState(
                                        title = "Remove trusted certificate?",
                                        message = "Remove trusted certificate for $domain ($shortHash)?",
                                        positiveAction = "Remove",
                                        negativeAction = "Cancel"
                                    )
                                },
                                onConfirmed = { confirmed ->
                                    ClickableOnClick.Action {
                                        if (confirmed) {
                                            val current = userPreferencesDataStore.trustedCerts.get().toMutableSet()
                                            current.remove(entry)
                                            userPreferencesDataStore.trustedCerts.set(current)
                                        }
                                    }
                                }
                            )
                        } else {
                            ClickableOnClick.None
                        }
                    }
                )
            )
        )
    )
}

@Composable
fun CertimarkSettingsScreen(
    certimarkSettingsScreen: CertimarkSettingsScreen,
    onUp: () -> Unit
) {
    SettingsFrameworkScreen(
        viewModel(
            key = certimarkSettingsScreen.key,
            factory = SettingsFrameworkPresenter.Factory(
                settingsFrameworkState = {
                    certimarkSettingsScreen.createSettingsFrameworkState()
                }
            )
        ),
        onUp
    )
}
