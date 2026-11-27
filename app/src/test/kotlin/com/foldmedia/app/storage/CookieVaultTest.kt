package com.foldmedia.app.storage

import com.foldmedia.app.model.PlatformType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieVaultTest {

    @Test
    fun rejectsAnonymousVisitorCookiesAsUnauthenticated() {
        assertFalse(
            CookieVault.hasAuthenticatedCookieKeys(
                "a1=195a8f; webId=ab12cd; gid=yjyY; webBuild=4.52.0",
                PlatformType.XIAOHONGSHU
            )
        )
        assertFalse(
            CookieVault.hasAuthenticatedCookieKeys(
                "ttwid=1%7Cabc; s_v_web_id=verify_123; passport_csrf_token=999",
                PlatformType.DOUYIN
            )
        )
        assertFalse(
            CookieVault.hasAuthenticatedCookieKeys(
                "guest_id=v1%3A1700; gt=1800",
                PlatformType.X_TWITTER
            )
        )
        assertFalse(
            CookieVault.hasAuthenticatedCookieKeys(
                "PREF=tz=Asia.Shanghai; VISITOR_INFO1_LIVE=xyz; YSC=abc",
                PlatformType.YOUTUBE
            )
        )
    }

    @Test
    fun acceptsRealSessionCookiesForEachPlatform() {
        assertTrue(
            CookieVault.hasAuthenticatedCookieKeys(
                "a1=195a8f; web_session=04006987a1b2c3d4; webId=ab12",
                PlatformType.XIAOHONGSHU
            )
        )
        assertTrue(
            CookieVault.hasAuthenticatedCookieKeys(
                "ttwid=1%7Cabc; sessionid=real_douyin_session_99; odin_tt=xyz",
                PlatformType.DOUYIN
            )
        )
        assertTrue(
            CookieVault.hasAuthenticatedCookieKeys(
                "guest_id=v1; auth_token=x99112233",
                PlatformType.X_TWITTER
            )
        )
        assertTrue(
            CookieVault.hasAuthenticatedCookieKeys(
                "YSC=abc; SAPISID=17000/AbCdEf",
                PlatformType.YOUTUBE
            )
        )
    }
}
