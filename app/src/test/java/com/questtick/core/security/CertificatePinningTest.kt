package com.questtick.core.security

import com.questtick.net.TrustedUrlPolicy
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 证书绑定的回归测试。
 *
 * 历史上业务域名新增后没有同步登记 Pin，导致绝区零签到链路（act-nap-api.mihoyo.com）长期处于
 * 完全未绑定的状态；同时两个「主 Pin」指向的 leaf 证书早已轮换，只是靠中间 / 根证书 Pin 才没有暴露。
 * 这里把「业务主机必须要么绑定、要么显式豁免」以及「Pin 本身必须合法」固化成断言，防止同类缺口复现。
 */
class CertificatePinningTest {
    private val configs = CertificateConfig.officialApiPins
    private val pinnedHosts = configs.map { it.host }.toSet()

    @Test
    fun everyBusinessHostIsPinnedOrExplicitlyExempted() {
        val undecided =
            TrustedUrlPolicy.businessRequestHosts - pinnedHosts -
                CertificateConfig.unpinnedBusinessHosts.keys

        assertEquals(
            "以下业务主机既未绑定证书、也未登记豁免：${undecided.sorted()}",
            emptySet<String>(),
            undecided,
        )
    }

    @Test
    fun everyExemptionStatesAReason() {
        CertificateConfig.unpinnedBusinessHosts.forEach { (host, reason) ->
            assertTrue("$host 的豁免必须写明原因", reason.isNotBlank())
        }
    }

    @Test
    fun everyHostKeepsThreePinsForRotation() {
        configs.forEach { config ->
            assertEquals(
                "${config.host} 需要 leaf / intermediate / root 三个 Pin 才能覆盖换证",
                3,
                config.pins.size,
            )
        }
    }

    @Test
    fun everyPinIsAValidSpkiSha256() {
        configs.forEach { config ->
            config.pins.forEach { pin ->
                assertTrue(
                    "${config.host} 的 Pin 必须以 ${CertificateConfig.PIN_PREFIX} 开头：$pin",
                    pin.startsWith(CertificateConfig.PIN_PREFIX),
                )
                val decoded = Base64.getDecoder().decode(pin.removePrefix(CertificateConfig.PIN_PREFIX))
                assertEquals(
                    "${config.host} 的 Pin 必须是 32 字节的 SPKI SHA-256：$pin",
                    32,
                    decoded.size,
                )
            }
        }
    }

    @Test
    fun pinsAreUniquePerHost() {
        configs.forEach { config ->
            assertEquals(
                "${config.host} 存在重复 Pin",
                config.pins.size,
                config.pins.toSet().size,
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun pinningManagerRejectsHostWithoutRotationFallback() {
        PinningManager.provideCertificatePinner(
            listOf(
                PinConfig(
                    host = "api-takumi.mihoyo.com",
                    pins = listOf("${CertificateConfig.PIN_PREFIX}AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
                ),
            ),
        )
    }
}
