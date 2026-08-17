package com.questtick.core.security

/** 单个域名的证书 Pin 配置。pins 使用 OkHttp 要求的 sha256/BASE64 格式。 */
data class PinConfig(
    val host: String,
    val pins: List<String>,
)

/**
 * 米游社核心 API 的证书绑定配置。
 *
 * 目前仅对签到核心 API 启用 Pinning；Passport / SDK 登录链路域名未绑定，避免证书轮换时
 * 直接导致扫码登录不可用。若后续补充登录域名 Pin，需同步准备备用 Pin 与可回滚策略。
 *
 * 每个域名至少保留 3 个 Pin：
 * 1. 当前 leaf 证书 Pin：安全性最高。
 * 2. 当前中间证书 Pin：leaf 换证时仍可用。
 * 3. 根证书 Pin：中间证书轮换时作为容灾兜底。
 *
 * GitHub Release / 自动更新域名不在本 Pin 列表中，因此即使复用统一传输客户端也不受这里的 Pinning 约束。
 */
object CertificateConfig {
    const val PIN_PREFIX = "sha256/"

    val officialApiPins: List<PinConfig> =
        listOf(
            PinConfig(
                host = "api-takumi.mihoyo.com",
                pins =
                    listOf(
                        // 主 Pin：*.mihoyo…
                        "sha256/pAO/pFciwBUc0bBm1Or+hl52qcE3eFULDTn6rhNPVL0=",
                        // 备用 Pin 1…
                        "sha256/udvVO5HfT3B83McRDgEhj2WVTnsk+Sb9NBCszsRWAqY=",
                        // 备用 Pin 2：DigiCert Global Root G2 root。
                        "sha256/i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY=",
                    ),
            ),
            PinConfig(
                host = "bbs-api.miyoushe.com",
                pins =
                    listOf(
                        // 主 Pin：*.miyoushe…
                        "sha256/yDpsG9ICEcC5c5HkTD8W473tY9kj1dNmZIIHG7CXtoU=",
                        // 备用 Pin 1…
                        "sha256/udvVO5HfT3B83McRDgEhj2WVTnsk+Sb9NBCszsRWAqY=",
                        // 备用 Pin 2：DigiCert Global Root G2 root。
                        "sha256/i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY=",
                    ),
            ),
        )
}
