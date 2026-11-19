package com.questtick.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 游戏选择页「米游币打卡」开关的凭证判断。
 *
 * 打卡需要的是 SToken 本身，形态为 v2_ 开头；mid、stuid、ltoken、网页 Cookie 都不作为
 * 开关的判断依据。
 */
class MysCoinStokenTest {
    @Test
    fun `v2 token is accepted`() {
        assertTrue(isMysCoinStoken("v2_abcdefghijklmnop=="))
        assertTrue(isMysCoinStoken("v2_abcdefghijklmnop="))
        assertTrue(isMysCoinStoken("v2_abcdefghijklmnop"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertTrue(isMysCoinStoken("  v2_abcdefghijklmnop  "))
        assertTrue(isMysCoinStoken("\nv2_abcdefghijklmnop\n"))
    }

    @Test
    fun `token without v2 prefix is rejected`() {
        assertFalse(isMysCoinStoken("abcdefghijklmnop="))
        assertFalse(isMysCoinStoken("v1_abcdefghijklmnop="))
        assertFalse(isMysCoinStoken("stoken_v2=abcdefghijklmnop"))
    }

    @Test
    fun `prefix check is case insensitive`() {
        assertTrue(isMysCoinStoken("V2_abcdefghijklmnop"))
    }

    @Test
    fun `blank or prefix only values are rejected`() {
        assertFalse(isMysCoinStoken(""))
        assertFalse(isMysCoinStoken("   "))
        assertFalse(isMysCoinStoken("v2_"))
    }

    @Test
    fun `whole cookie pasted into stoken field is rejected`() {
        assertFalse(isMysCoinStoken("stuid=1; stoken=v2_x; mid=m"))
    }
}
