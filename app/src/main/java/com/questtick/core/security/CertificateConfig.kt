package com.questtick.core.security

/*
 * 米游社业务 API 的证书绑定配置：集中登记各业务主机的可信证书 Pin，并维护一份「明确不绑定」的豁免清单。
 */

/** 单个域名的证书 Pin 配置。pins 使用 OkHttp 要求的 sha256/BASE64 格式。 */
data class PinConfig(
    val host: String,
    val pins: List<String>,
)

/**
 * 米游社业务 API 的证书绑定配置。
 *
 * 每个域名固定绑定 3 个 Pin，任意一个匹配即通过校验（OkHttp 语义）：
 * 1. leaf（叶子证书）：安全性最高，可拦截任何针对该域名的错误签发。
 * 2. intermediate（中间证书）：leaf 换证后仍然可用，覆盖米哈游正常的年度换证。
 * 3. root（根证书）：中间证书轮换时作为容灾兜底。
 *
 * 米哈游的 mihoyo.com 与 miyoushe.com 业务主机各自共用一张通配 leaf 证书，因此同系域名复用同一组 Pin。
 *
 * 每个 Pin 常量都标注了对应证书的主体，可直接用
 * `openssl s_client -connect <host>:443 -showcerts` 采集后逐条复核。换证时必须重新采集，
 * 并同时更新 leaf / intermediate / root，只替换 leaf 会让绑定在下次换证时再次失效。
 *
 * GitHub Release / 自动更新域名不在本 Pin 列表中，因此即使复用统一传输客户端也不受这里的 Pinning 约束。
 */
object CertificateConfig {
    const val PIN_PREFIX = "sha256/"

    // —— 以下 Pin 于 2026-09-11 通过 openssl s_client -showcerts 实测采集（SPKI SHA-256）——

    /** leaf：CN=*.mihoyo.com / O=上海米哈游影铁科技有限公司，由 GeoTrust G2 TLS CN RSA4096 SHA256 2022 CA1 签发。 */
    private const val PIN_MIHOYO_WILDCARD_LEAF =
        "sha256/WDVHOvFouW2H7/bD7vjofglgbm/GK6hQZXFG/ACj5pw="

    /** leaf：CN=*.miyoushe.com / O=上海米哈游姬米花科技有限公司，由 GeoTrust G2 TLS CN RSA4096 SHA256 2022 CA1 签发。 */
    private const val PIN_MIYOUSHE_WILDCARD_LEAF =
        "sha256/NMzTfS1wbXqiREheEBR5Ep6LDMUGaBnL0vY1fckpxgc="

    /** intermediate：CN=GeoTrust G2 TLS CN RSA4096 SHA256 2022 CA1 / O=DigiCert, Inc.。 */
    private const val PIN_GEOTRUST_G2_TLS_CA1 =
        "sha256/udvVO5HfT3B83McRDgEhj2WVTnsk+Sb9NBCszsRWAqY="

    /** root：CN=DigiCert Global Root G2 / O=DigiCert Inc。 */
    private const val PIN_DIGICERT_GLOBAL_ROOT_G2 =
        "sha256/i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY="

    /** mihoyo.com 系业务主机共用的 Pin 组合：通配 leaf + 中间 + 根。 */
    private val MIHOYO_PINS =
        listOf(
            PIN_MIHOYO_WILDCARD_LEAF,
            PIN_GEOTRUST_G2_TLS_CA1,
            PIN_DIGICERT_GLOBAL_ROOT_G2,
        )

    /** miyoushe.com 系业务主机共用的 Pin 组合：通配 leaf + 中间 + 根。 */
    private val MIYOUSHE_PINS =
        listOf(
            PIN_MIYOUSHE_WILDCARD_LEAF,
            PIN_GEOTRUST_G2_TLS_CA1,
            PIN_DIGICERT_GLOBAL_ROOT_G2,
        )

    val officialApiPins: List<PinConfig> =
        listOf(
            // 米游社签到主链路
            PinConfig(host = "api-takumi.mihoyo.com", pins = MIHOYO_PINS),
            PinConfig(host = "bbs-api.miyoushe.com", pins = MIYOUSHE_PINS),
            // 绝区零签到实际走的域名，此前完全没有绑定，是本次补齐的重点
            PinConfig(host = "act-nap-api.mihoyo.com", pins = MIHOYO_PINS),
            // 云游戏签到
            PinConfig(host = "api-cloudgame.mihoyo.com", pins = MIHOYO_PINS),
            PinConfig(host = "cg-hkrpg-api.mihoyo.com", pins = MIHOYO_PINS),
            // 游戏 SDK 与版本信息
            PinConfig(host = "hk4e-sdk.mihoyo.com", pins = MIHOYO_PINS),
            PinConfig(host = "hkrpg-sdk.mihoyo.com", pins = MIHOYO_PINS),
            PinConfig(host = "ys.mihoyo.com", pins = MIHOYO_PINS),
            PinConfig(host = "sr.mihoyo.com", pins = MIHOYO_PINS),
            // Passport 登录链路：与上面这些域名共用同一张通配 leaf 证书，绑定不会降低可用性
            PinConfig(host = "passport-api.mihoyo.com", pins = MIHOYO_PINS),
        )

    /**
     * 明确不做证书绑定的业务主机，以及豁免原因。
     *
     * 新增业务主机时，要么在 [officialApiPins] 中登记 Pin，要么在这里登记豁免原因，
     * 否则 CertificatePinningTest 会失败，避免再出现「悄悄绕过 Pinning」的主机。
     */
    internal val unpinnedBusinessHosts: Map<String, String> =
        mapOf(
            "itunes.apple.com" to
                "Apple 自有域名与 CA，证书轮换不受本项目控制；该域名仅用于查询商店版本号，不承载任何凭证",
        )
}
