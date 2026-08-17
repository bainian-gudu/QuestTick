package com.questtick.core.security

import okhttp3.CertificatePinner

/** 统一创建 OkHttp CertificatePinner，避免网络层散落硬编码 Pin。 */
object PinningManager {
    fun provideCertificatePinner(configs: List<PinConfig> = CertificateConfig.officialApiPins): CertificatePinner {
        val builder = CertificatePinner.Builder()
        configs
            .filter { it.host.isNotBlank() && it.pins.isNotEmpty() }
            .forEach { config ->
                validate(config)
                builder.add(config.host, *config.pins.toTypedArray())
            }
        return builder.build()
    }

    private fun validate(config: PinConfig) {
        require(config.pins.size >= MIN_PINS_PER_HOST) {
            "${config.host} requires at least $MIN_PINS_PER_HOST pins for rotation fallback"
        }
        config.pins.forEach { pin ->
            require(pin.startsWith(CertificateConfig.PIN_PREFIX)) {
                "Invalid certificate pin for ${config.host}: $pin"
            }
        }
    }

    private const val MIN_PINS_PER_HOST = 3
}
