package com.vitz.music

import com.vitz.music.auth.InviteCodes
import com.vitz.music.auth.Passwords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordsTest {

    @Test
    fun `совпадающий пароль проходит проверку`() {
        val encoded = Passwords.hash("правильный лошадь батарейка скрепка")
        assertTrue(Passwords.verify("правильный лошадь батарейка скрепка", encoded))
    }

    @Test
    fun `чужой пароль не проходит`() {
        val encoded = Passwords.hash("s3cret-password")
        assertFalse(Passwords.verify("s3cret-passwore", encoded))
        assertFalse(Passwords.verify("", encoded))
    }

    @Test
    fun `две соли дают разные хеши одного пароля`() {
        val a = Passwords.hash("одинаковый")
        val b = Passwords.hash("одинаковый")
        assertTrue(a != b, "Соль обязана быть случайной")
        assertTrue(Passwords.verify("одинаковый", a) && Passwords.verify("одинаковый", b))
    }

    @Test
    fun `испорченная строка хеша не роняет проверку`() {
        assertFalse(Passwords.verify("пароль", "мусор"))
        assertFalse(Passwords.verify("пароль", "argon2id\$v=19\$m=x,t=y,p=z\$..\$.."))
    }
}

class InviteCodesTest {

    @Test
    fun `код читаемый и без похожих символов`() {
        repeat(50) {
            val code = InviteCodes.generate()
            assertEquals(14, code.length, "Формат XXXX-XXXX-XXXX")
            assertTrue(code.none { it in "01OIL" }, "В коде $code есть символ, который путают на слух")
        }
    }
}
