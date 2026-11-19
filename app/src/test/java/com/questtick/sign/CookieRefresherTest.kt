package com.questtick.sign

import com.questtick.net.FakeHttpTransport
import com.questtick.net.HttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieRefresherTest {
    @Test
    fun `refresh builds cookie from both token endpoints`() =
        runTest {
            val urls = mutableListOf<String>()
            val transport =
                FakeHttpTransport { request ->
                    urls += request.url
                    when {
                        request.url.contains("getCookieAccountInfoBySToken") -> {
                            HttpResponse.text(200, """{"retcode":0,"data":{"cookie_token":"cookie-token"}}""")
                        }

                        request.url.contains("getLTokenBySToken") -> {
                            HttpResponse.text(200, """{"retcode":0,"data":{"ltoken":"ltoken"}}""")
                        }

                        else -> {
                            error("unexpected request: ${request.url}")
                        }
                    }
                }

            val refreshed = CookieRefresher.refresh("10001", "stoken", "mid", transport)

            assertEquals("cookie-token", refreshed?.cookieToken)
            assertEquals("ltoken", refreshed?.ltoken)
            assertTrue(refreshed?.fullCookie.orEmpty().contains("cookie_token=cookie-token"))
            assertTrue(refreshed?.fullCookie.orEmpty().contains("ltoken=ltoken"))
            assertEquals(2, urls.size)
        }
}
