package com.openandroidintelligence.mobile

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.CapabilityProviderSelector
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.kernel.HostEnvelope
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.NativePluginLoader
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.kernel.PhoneLimits
import com.openandroidintelligence.kernel.PluginKernel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SMS access must lead to the real author-scoped plugin grant, never a standalone capability bit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsHonestyCopyTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theSmsEntryOpensPluginManagementInsteadOfOfferingAnIneffectiveGrant() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    SettingsScreen(environment = settingsEnvironment(), runtime = null, onBack = {})
                }
            }
        }

        // 短信读取不提供独立开关：它只存在于授权管理子页，且必须把用户送到真正的
        // 插件安装/授权链路，而不是给一个点了也不生效的授权位。
        compose.onNodeWithText("读取与发送短信").assertDoesNotExist()
        compose.onNodeWithText("授权管理").performScrollTo().performClick()
        compose.onNodeWithText("短信读取").performScrollTo().performClick()
        compose.onNodeWithText("未安装任何插件").assertExists()
    }

    @Test
    fun openingSmsSettingsDoesNotGrantAccessToAnUnselectedPlugin() {
        val environment = settingsEnvironment()
        compose.setContent {
            MaterialTheme {
                Surface {
                    SettingsScreen(environment = environment, runtime = null, onBack = {})
                }
            }
        }

        compose.onNodeWithText("授权管理").performScrollTo().performClick()
        compose.onNodeWithText("短信读取").performScrollTo().performClick()
        assertTrue(environment.pairingGrants.state.value!!.granted.isEmpty())
    }

    /**
     * 授权管理子页只做一件事：把手机能力授权给当前 Gateway。
     * 通知类条目（推送、气泡、实时活动、读取范围）已归入主览的「通知管理」区，
     * 因此不应在这个子页里重复出现——两处入口意味着改文案时必然漏掉一处。
     */
    @Test
    fun theAuthorisationSubScreenKeepsNotificationEntriesOutOfTheGrantList() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    SettingsScreen(environment = settingsEnvironment(), runtime = null, onBack = {})
                }
            }
        }

        compose.onNodeWithText("授权管理").performScrollTo().performClick()

        compose.onNodeWithText("短信读取").performScrollTo().assertExists()
        compose.onNodeWithText("屏幕上下文分析与圈选").performScrollTo().assertExists()
        compose.onNodeWithText("通知读取范围").assertDoesNotExist()
        compose.onNodeWithText("系统通知推送").assertDoesNotExist()
    }

    /** 主览不再展示本机自增序号、本机合成标识这类对用户没有意义的技术元数据。 */
    @Test
    fun theAuthorisationSubScreenOmitsLocalBookkeepingMetadata() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    SettingsScreen(environment = settingsEnvironment(), runtime = null, onBack = {})
                }
            }
        }

        compose.onNodeWithText("授权管理").performScrollTo().performClick()

        compose.onNodeWithText("配对元数据").assertDoesNotExist()
        compose.onNodeWithText("授权变更序号（本机）").assertDoesNotExist()
        compose.onNodeWithText("配对身份标识（本机合成）").assertDoesNotExist()
    }

    /**
     * 授权按账号分开保存：切到别的账号不会带走授权，切回来时原来的授权仍然有效。
     * `PairingGrantStateHolder.bind()` 会加载该账号已持久化的授权，因此文案不能把
     * 「切换账号」与「解除配对」混为一谈——否则用户会误以为切换就作废了授权。
     */
    @Test
    fun theAuthorisationNotesDoNotClaimSwitchingAccountsRevokesExistingGrants() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    SettingsScreen(environment = settingsEnvironment(), runtime = null, onBack = {})
                }
            }
        }

        compose.onNodeWithText("授权管理").performScrollTo().performClick()

        compose.onNodeWithText("授权按账号分开保存", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("切换账号或解除配对后，需要重新授权").assertDoesNotExist()
    }

    private fun settingsEnvironment(): PlatformSettingsEnvironment {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val trustMode = DeveloperTrustMode()
        val auditStore = AndroidAuditStore(InMemoryAuditSink())
        val pairingGrants = PairingGrantStateHolder(
            store = InMemoryPairingGrantStore(),
            audit = auditStore,
        )
        pairingGrants.bind(
            com.openandroidintelligence.kernel.PairingGrantBinding(
                gatewayId = "https://gateway.example.com",
                accountId = "test-account",
                installationId = "test-install",
            ),
        )
        val kernel = PluginKernel(
            hostEnvelope = HostEnvelope(primitives = emptySet()),
            phoneLimits = PhoneLimits(primitives = emptySet()),
            runtimes = emptyMap(),
            audit = auditStore,
            trustMode = trustMode,
            nativeLoader = NativePluginLoader(trustMode),
            providerSelector = CapabilityProviderSelector(phoneDefaults = emptyMap()),
            grants = { pairingId -> pairingGrants.currentKernelGrant(pairingId) },
        )
        return PlatformSettingsEnvironment(
            trustMode = trustMode,
            audit = auditStore,
            auditSink = InMemoryAuditSink(),
            allowDeveloperTrustMode = true,
            kernel = kernel,
            pairingGrants = pairingGrants,
            appearance = AppearancePreferences(context),
        )
    }
}
