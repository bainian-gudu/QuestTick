package com.questtick.sign

import com.questtick.net.HttpFailure
import com.questtick.net.HttpFailureKind
import com.questtick.net.HttpTransportException
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorTextDetailTest {
    @Test
    fun `detail preserves original exception cause chain`() {
        val root = IOException("original socket failure")
        val wrapped = IllegalStateException("feature failed", root)

        val detail = ErrorText.detailOf(wrapped)

        assertTrue(detail.contains("java.lang.IllegalStateException: feature failed"))
        assertTrue(detail.contains("java.io.IOException: original socket failure"))
        assertTrue(detail.contains("Caused by:"))
        assertFalse(detail.contains("Stack trace:"))
    }

    @Test
    fun `debug detail includes stack trace`() {
        val detail = ErrorText.detailOf(IllegalArgumentException("bad input"), includeStackTrace = true)

        assertTrue(detail.contains("java.lang.IllegalArgumentException: bad input"))
        assertTrue(detail.contains("Stack trace:"))
    }

    @Test
    fun `http transport wrapper retains original cause`() {
        val original = IOException("connection reset by peer")
        val wrapped =
            HttpTransportException(
                HttpFailure(
                    kind = HttpFailureKind.CONNECTION_INTERRUPTED,
                    errorCode = "transport-reset",
                    retryable = true,
                    outcomeUnknown = false,
                ),
                original,
            )

        assertSame(original, wrapped.cause)
        assertTrue(ErrorText.detailOf(wrapped).contains("connection reset by peer"))
    }
}
