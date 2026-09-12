package com.questtick.sign

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DsTest {
    // ---------------------------------------------------------------- md5_v1

    @Test
    fun deterministicSignatureMatchesExpectedMd5() {
        val ds = Ds.generate(timestampSeconds = 1_700_000_000L, random = "abc123")

        assertEquals("1700000000,abc123,6e8f99d9056c2e8b01e661dce48f8bc7", ds)
    }

    @Test
    fun boundaryRandomLowerAndUpperValuesGenerate() {
        assertEquals("1,000000,91106f51d1dd9b2d732b8cedbfaaa44e", Ds.generate(1L, "000000"))
        assertEquals("1700000000,zzzzzz,f491efde9746424d3d693771a4e2323d", Ds.generate(1_700_000_000L, "zzzzzz"))
    }

    @Test
    fun generatedSignatureHasExpectedFormat() {
        val parts = Ds.generate().split(",")

        assertEquals(3, parts.size)
        assertTrue(parts[0].toLong() > 0)
        assertEquals(6, parts[1].length)
        assertTrue(parts[1].all { it in '0'..'9' || it in 'a'..'z' })
        assertEquals(32, parts[2].length)
        assertTrue(parts[2].all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroTimestampRejected() {
        Ds.generate(timestampSeconds = 0L, random = "abc123")
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeTimestampRejected() {
        Ds.generate(timestampSeconds = -1L, random = "abc123")
    }

    @Test(expected = IllegalArgumentException::class)
    fun shortRandomRejected() {
        Ds.generate(timestampSeconds = 1_700_000_000L, random = "abc12")
    }

    @Test(expected = IllegalArgumentException::class)
    fun longRandomRejected() {
        Ds.generate(timestampSeconds = 1_700_000_000L, random = "abc1234")
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRandomCharactersRejected() {
        Ds.generate(timestampSeconds = 1_700_000_000L, random = "ABC123")
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankSaltRejected() {
        Ds.generate(timestampSeconds = 1_700_000_000L, random = "abc123", salt = "")
    }

    @Test
    fun explicitSaltIsUsedAndDefaultMatchesLunaSalt() {
        val withDefault = Ds.generate(1_700_000_000L, "abc123")
        val withExplicit = Ds.generate(1_700_000_000L, "abc123", salt = Ds.LUNA_SALT)

        assertEquals(withDefault, withExplicit)
    }

    // ---------------------------------------------------------------- md5_v2

    @Test
    fun md5V2IncludesBodyAndQueryInSignature() {
        val body = "{\"gids\":\"2\"}"
        val query = "a=1&b=2"
        val ds =
            Ds.generate(
                timestampSeconds = 1_700_000_000L,
                random = "123456",
                body = body,
                query = query,
                algorithm = Ds.Algorithm.MD5_V2,
                salt = Ds.X6_SALT,
            )

        assertEquals("1700000000,123456,64d6360fad763601220407a4b949ad75", ds)
    }

    @Test
    fun md5V2ReplacesSpecialRandomValue() {
        val ds =
            Ds.generate(
                timestampSeconds = 1_700_000_000L,
                random = "100000",
                algorithm = Ds.Algorithm.MD5_V2,
                salt = Ds.X6_SALT,
            )

        // 社区规范：r = 100000 时必须替换为 642367，且签名也使用替换后的值。
        assertEquals("1700000000,642367,65b2e0357275248f58bcf9ebc05058a2", ds)
    }

    @Test
    fun md5V2AcceptsUpperRandomBoundary() {
        val ds =
            Ds.generate(
                timestampSeconds = 1_700_000_000L,
                random = "200000",
                algorithm = Ds.Algorithm.MD5_V2,
                salt = Ds.X6_SALT,
            )

        assertEquals("1700000000,200000,85a29eb7426eeddc72db9059093f1927", ds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun md5V2RejectsOutOfRangeRandom() {
        Ds.generate(
            timestampSeconds = 1_700_000_000L,
            random = "999999",
            algorithm = Ds.Algorithm.MD5_V2,
            salt = Ds.X6_SALT,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun md5V2RejectsNonNumericRandom() {
        Ds.generate(
            timestampSeconds = 1_700_000_000L,
            random = "abc123",
            algorithm = Ds.Algorithm.MD5_V2,
            salt = Ds.X6_SALT,
        )
    }

    @Test
    fun md5V2BodyChangeAltersSignature() {
        val base =
            Ds.generate(
                timestampSeconds = 1_700_000_000L,
                random = "123456",
                body = "{\"gids\":\"2\"}",
                algorithm = Ds.Algorithm.MD5_V2,
                salt = Ds.X6_SALT,
            )
        val changed =
            Ds.generate(
                timestampSeconds = 1_700_000_000L,
                random = "123456",
                body = "{\"gids\":\"5\"}",
                algorithm = Ds.Algorithm.MD5_V2,
                salt = Ds.X6_SALT,
            )

        assertTrue(base != changed)
    }

    @Test
    fun generateX6UsesThreeCommaSeparatedParts() {
        val parts = Ds.generateX6(body = "{}", query = "gids=2").split(",")

        assertEquals(3, parts.size)
        assertTrue(parts[0].toLong() > 0)
        assertTrue(parts[1].toInt() in 100001..200000)
        assertEquals(32, parts[2].length)
    }

    @Test
    fun generateWebUsesMd5V1Shape() {
        val parts = Ds.generateWeb().split(",")

        assertEquals(3, parts.size)
        assertEquals(6, parts[1].length)
        assertTrue(parts[1].all { it in '0'..'9' || it in 'a'..'z' })
    }

    // -------------------------------------------------- body / query 归一化

    @Test
    fun sortedQueryStringSortsByKey() {
        assertEquals("a=1&b=2&c=3", Ds.sortedQueryString("c=3&a=1&b=2"))
    }

    @Test
    fun sortedQueryStringKeepsSinglePairAndBlankInput() {
        assertEquals("act_id=e202311201442471", Ds.sortedQueryString("act_id=e202311201442471"))
        assertEquals("", Ds.sortedQueryString(""))
        assertEquals("", Ds.sortedQueryString("   "))
    }

    @Test
    fun sortedQueryStringDropsPairsWithoutSeparator() {
        assertEquals("a=1", Ds.sortedQueryString("a=1&broken"))
    }

    @Test
    fun sortedJsonStringOrdersKeysAlphabetically() {
        val json =
            JSONObject()
                .put("uid", "123")
                .put("act_id", "e202311201442471")
                .put("region", "cn_gf01")

        assertEquals("{\"act_id\":\"e202311201442471\",\"region\":\"cn_gf01\",\"uid\":\"123\"}", Ds.sortedJsonString(json))
    }

    @Test
    fun sortedJsonStringEscapesSpecialCharacters() {
        val json = JSONObject().put("k", "a\"b\\c\nd")

        assertEquals("{\"k\":\"a\\\"b\\\\c\\nd\"}", Ds.sortedJsonString(json))
    }

    @Test
    fun sortedJsonStringSerializesNestedStructures() {
        val nested = JSONObject().put("z", 1).put("a", 2)
        val array =
            org.json
                .JSONArray()
                .put(3)
                .put(2)
                .put(1)
        val json =
            JSONObject()
                .put("obj", nested)
                .put("arr", array)
                .put("flag", true)
                .put("nothing", JSONObject.NULL)

        assertEquals(
            "{\"arr\":[3,2,1],\"flag\":true,\"nothing\":null,\"obj\":{\"a\":2,\"z\":1}}",
            Ds.sortedJsonString(json),
        )
    }

    @Test
    fun sortedJsonStringHandlesEmptyNestedStructures() {
        val json =
            JSONObject()
                .put("obj", JSONObject())
                .put("arr", org.json.JSONArray())

        assertEquals("{\"arr\":[],\"obj\":{}}", Ds.sortedJsonString(json))
    }
}
