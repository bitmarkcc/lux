package acr.browser.lightning.settings.screens

import acr.browser.lightning.R
import acr.browser.lightning.preference.UserPreferencesDataStore
import acr.browser.lightning.resources.ResourceProvider
import acr.browser.lightning.settings.SettingsBottomSheetInputState
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
