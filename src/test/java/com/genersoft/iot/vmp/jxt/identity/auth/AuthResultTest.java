package com.genersoft.iot.vmp.jxt.identity.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthResultTest {

    @Test
    void enumHasExactlyThreeValues() {
        AuthResult[] values = AuthResult.values();
        assertEquals(3, values.length);
        assertArrayEquals(new AuthResult[]{AuthResult.SUCCESS, AuthResult.FAIL, AuthResult.SKIP}, values);
    }

    @Test
    void valueOfWorks() {
        assertEquals(AuthResult.SUCCESS, AuthResult.valueOf("SUCCESS"));
        assertEquals(AuthResult.FAIL, AuthResult.valueOf("FAIL"));
        assertEquals(AuthResult.SKIP, AuthResult.valueOf("SKIP"));
    }
}
