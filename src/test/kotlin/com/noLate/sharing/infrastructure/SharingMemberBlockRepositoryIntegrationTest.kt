package com.noLate.sharing.infrastructure

import com.noLate.sharing.domain.SharingMemberBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.TestPropertySource

@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:sharing-member-blocks;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ]
)
class SharingMemberBlockRepositoryIntegrationTest @Autowired constructor(
    private val repository: SharingMemberBlockRepository,
) {
    @Test
    fun `active block is symmetric for enforcement and directional for management`() {
        repository.saveAndFlush(
            SharingMemberBlock(blockerMemberId = 1L, blockedMemberId = 2L)
        )

        assertTrue(repository.existsActiveEitherDirection(1L, 2L))
        assertTrue(repository.existsActiveEitherDirection(2L, 1L))
        assertEquals(listOf(2L), repository.findBlockedCounterpartIds(1L, listOf(2L, 3L)))
        assertEquals(1, repository.findAllByBlockerMemberIdAndDeletedFalseOrderByIdDesc(1L).size)
        assertTrue(repository.findAllByBlockerMemberIdAndDeletedFalseOrderByIdDesc(2L).isEmpty())
    }

    @Test
    fun `account cleanup deletes blocks where the member is on either side`() {
        repository.saveAllAndFlush(
            listOf(
                SharingMemberBlock(blockerMemberId = 1L, blockedMemberId = 2L),
                SharingMemberBlock(blockerMemberId = 3L, blockedMemberId = 1L),
                SharingMemberBlock(blockerMemberId = 2L, blockedMemberId = 3L),
            )
        )

        repository.deleteAllByBlockerMemberIdOrBlockedMemberId(1L, 1L)
        repository.flush()

        assertFalse(repository.existsActiveEitherDirection(1L, 2L))
        assertFalse(repository.existsActiveEitherDirection(1L, 3L))
        assertTrue(repository.existsActiveEitherDirection(2L, 3L))
    }
}
