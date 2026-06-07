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
import acr.browser.lightning.settings.framework.ToggleState
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import javax.inject.Inject

class ProxySettingsScreen @Inject constructor(
    private val resourceProvider: ResourceProvider,
    private val userPreferencesDataStore: UserPreferencesDataStore,
) {
    val key = "proxy"

    fun createSettingsFrameworkState(): SettingsFrameworkState = SettingsFrameworkState(
        title = resourceProvider.stringResource(R.string.settings_proxy),
        content = listOf(
            ToggleState(
                title = resourceProvider.stringResource(R.string.proxy_enabled),
                isChecked = { userPreferencesDataStore.proxyEnabled.get() },
                onToggle = {
                    userPreferencesDataStore.proxyEnabled.set(it)
                    null
                }
            ),
            ClickableState(
                title = resourceProvider.stringResource(R.string.proxy_host),
                summary = { userPreferencesDataStore.proxyHost.get() },
                onClick = ClickableOnClick.Input(
                    produceState = {
                        SettingsBottomSheetInputState(
                            title = resourceProvider.stringResource(R.string.proxy_host),
                            hint = "127.0.0.1",
                            currentValue = userPreferencesDataStore.proxyHost.get()
                        )
                    },
                    onValueUpdated = { value ->
                        ClickableOnClick.Action {
                            userPreferencesDataStore.proxyHost.set(value.trim())
                        }
                    }
                )
            ),
            ClickableState(
                title = resourceProvider.stringResource(R.string.proxy_port),
                summary = { userPreferencesDataStore.proxyPort.get().toString() },
                onClick = ClickableOnClick.Input(
                    produceState = {
                        SettingsBottomSheetInputState(
                            title = resourceProvider.stringResource(R.string.proxy_port),
                            hint = "9050",
                            currentValue = userPreferencesDataStore.proxyPort.get().toString()
                        )
                    },
                    onValueUpdated = { value ->
                        ClickableOnClick.Action {
                            value.trim().toIntOrNull()?.let {
                                userPreferencesDataStore.proxyPort.set(it)
                            }
                        }
                    }
                )
            )
        )
    )
}

@Composable
fun ProxySettingsScreen(
    proxySettingsScreen: ProxySettingsScreen,
    onUp: () -> Unit
) {
    SettingsFrameworkScreen(
        viewModel(
            key = proxySettingsScreen.key,
            factory = SettingsFrameworkPresenter.Factory(
                settingsFrameworkState = {
                    proxySettingsScreen.createSettingsFrameworkState()
                }
            )
        ),
        onUp
    )
}
