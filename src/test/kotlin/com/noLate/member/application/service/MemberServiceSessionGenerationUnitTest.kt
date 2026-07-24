package com.noLate.member.application.service

import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class MemberServiceSessionGenerationUnitTest {
    private val repository = mock<MemberRepository>()
    private val service = MemberService(repository)

    @Test
    fun `same second old generation is rejected while current generation is accepted`() {
        val memberId = 71L
        val sameSecond = Instant.parse("2026-07-24T03:00:00Z")
        val member = Member(
            id = memberId,
            name = "member",
            password = "Password1!",
            email = "same-second-principal@example.com",
            loginType = LoginType.COMMON,
            tokensValidAfter = sameSecond,
            sessionGeneration = 4,
        )
        whenever(repository.findByIdAndDeletedFalse(memberId)).thenReturn(member)

        assertNull(service.getPrincipalById(memberId, sameSecond, 3))

        val current = service.getPrincipalById(memberId, sameSecond, 4)
        assertNotNull(current)
        assertEquals(4L, current.accessTokenSessionGeneration)
        assertEquals(sameSecond, current.accessTokenIssuedAt)
    }
}
