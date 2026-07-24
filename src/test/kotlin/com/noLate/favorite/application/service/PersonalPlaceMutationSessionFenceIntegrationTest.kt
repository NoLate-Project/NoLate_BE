package com.noLate.favorite.application.service

import com.noLate.favorite.infrastructure.FavoritePlaceCategoryRepository
import com.noLate.favorite.infrastructure.FavoritePlaceRepository
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.application.service.MemberSessionFenceService
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.routehistory.application.service.RecentRoutePlaceService
import com.noLate.routehistory.infrastructure.RecentRoutePlaceRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:personal-place-session-fence;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "schedule.push.enabled=false",
        "notification.push-outbox.enabled=false",
    ],
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PersonalPlaceMutationSessionFenceIntegrationTest @Autowired constructor(
    private val favoritePlaceService: FavoritePlaceService,
    private val recentRoutePlaceService: RecentRoutePlaceService,
    private val sessionFenceService: MemberSessionFenceService,
    private val memberRepository: MemberRepository,
    private val favoritePlaceRepository: FavoritePlaceRepository,
    private val favoriteCategoryRepository: FavoritePlaceCategoryRepository,
    private val recentRoutePlaceRepository: RecentRoutePlaceRepository,
) {
    @Test
    fun `favorite mutation authenticated as g1 cannot write after logout commits g2`() {
        val memberId = createMember("favorite-stale", sessionGeneration = 1L)

        val failure = invokeAfterLogout(memberId) {
            favoritePlaceService.createPlace(
                memberId = memberId,
                presentedSessionGeneration = 1L,
                categoryId = null,
                label = "stale favorite",
                placeName = "stale place",
                address = "stale address",
                lat = 37.5,
                lng = 127.0,
                provider = "TEST",
                providerPlaceId = "stale-favorite",
                defaultOrigin = true,
                sortOrder = null,
            )
        }

        assertInvalidToken(failure)
        assertTrue(favoritePlaceRepository.findAllByMemberId(memberId).isEmpty())
        assertTrue(favoriteCategoryRepository.findAllByMemberId(memberId).isEmpty())
        assertEquals(2L, memberRepository.findById(memberId).orElseThrow().sessionGeneration)
    }

    @Test
    fun `recent route mutation authenticated as g1 cannot write after logout commits g2`() {
        val memberId = createMember("recent-stale", sessionGeneration = 1L)

        val failure = invokeAfterLogout(memberId) {
            recentRoutePlaceService.saveRecentPlace(
                memberId = memberId,
                presentedSessionGeneration = 1L,
                label = "stale recent",
                placeName = "stale place",
                address = "stale address",
                lat = 37.5,
                lng = 127.0,
                provider = "TEST",
                providerPlaceId = "stale-recent",
            )
        }

        assertInvalidToken(failure)
        assertTrue(recentRoutePlaceRepository.findAllByMemberId(memberId).isEmpty())
        assertEquals(2L, memberRepository.findById(memberId).orElseThrow().sessionGeneration)
    }

    @Test
    fun `current generation keeps both personal place mutation families writable`() {
        val memberId = createMember("personal-current", sessionGeneration = 7L)

        favoritePlaceService.createCategory(
            memberId = memberId,
            presentedSessionGeneration = 7L,
            name = "자주 가는 곳",
            color = null,
            iconKey = null,
            sortOrder = null,
        )
        recentRoutePlaceService.saveRecentPlace(
            memberId = memberId,
            presentedSessionGeneration = 7L,
            label = "최근 목적지",
            placeName = "목적지",
            address = "서울",
            lat = 37.5,
            lng = 127.0,
            provider = "TEST",
            providerPlaceId = "current-recent",
        )

        assertEquals(1, favoriteCategoryRepository.findAllByMemberId(memberId).size)
        assertEquals(1, recentRoutePlaceRepository.findAllByMemberId(memberId).size)
        assertEquals(7L, memberRepository.findById(memberId).orElseThrow().sessionGeneration)
    }

    private fun invokeAfterLogout(
        memberId: Long,
        mutation: () -> Unit,
    ): Throwable? {
        val securityFilterPassed = CountDownLatch(1)
        val resumeMutation = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit {
            securityFilterPassed.countDown()
            check(resumeMutation.await(10, TimeUnit.SECONDS))
            failure.set(runCatching(mutation).exceptionOrNull())
        }
        try {
            assertTrue(securityFilterPassed.await(10, TimeUnit.SECONDS))
            sessionFenceService.invalidateSessionsAndLogout(memberId)
            resumeMutation.countDown()
            future.get(10, TimeUnit.SECONDS)
            return failure.get()
        } finally {
            resumeMutation.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun createMember(
        key: String,
        sessionGeneration: Long,
    ): Long = requireNotNull(
        memberRepository.saveAndFlush(
            Member(
                name = key,
                password = "Password1!",
                email = "$key@nolate.test",
                loginType = LoginType.COMMON,
                sessionGeneration = sessionGeneration,
            ),
        ).id,
    )

    private fun assertInvalidToken(failure: Throwable?) {
        assertTrue(failure is BusinessException, failure?.stackTraceToString())
        assertEquals(ErrorCode.INVALID_TOKEN, (failure as BusinessException).errorCode)
    }
}
