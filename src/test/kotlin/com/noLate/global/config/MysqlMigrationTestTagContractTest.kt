package com.noLate.global.config

import com.noLate.auth.apple.AppleTokenLifecycleMySqlMigrationTest
import com.noLate.journey.automatic.JourneyActivityMySqlTest
import com.noLate.notification.domain.DepartureAlarmFireEvidenceMySqlMigrationTest
import com.noLate.schedule.domain.PushEtaTrustMySqlMigrationTest
import com.noLate.schedule.domain.ScheduleRouteOptimisticLockMigrationContractTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/** Test discovery only: this contract must not start Docker or a database. */
class MysqlMigrationTestTagContractTest {
    @Test
    fun `MySQL 전용 CLI 테스트는 mysql 필터를 따른다`() {
        listOf(
            AppleTokenLifecycleMySqlMigrationTest::class.java,
            PushEtaTrustMySqlMigrationTest::class.java,
            DepartureAlarmFireEvidenceMySqlMigrationTest::class.java,
            JourneyActivityMySqlTest::class.java,
        ).forEach { type ->
            assertTrue(type.getAnnotationsByType(Tag::class.java).any { it.value == "mysql" }, type.name)
        }
    }

    @Test
    fun `혼합 클래스는 MySQL 실행만 분리하고 순수 SQL 계약 검사를 유지한다`() {
        val type = ScheduleRouteOptimisticLockMigrationContractTest::class.java
        assertFalse(type.getAnnotationsByType(Tag::class.java).any { it.value == "mysql" })
        val tests = type.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }
        val mysql = tests.single { it.name.startsWith("mysql 84") }
        val offline = tests.single { it.name.startsWith("migration is additive") }
        assertTrue(mysql.getAnnotationsByType(Tag::class.java).any { it.value == "mysql" })
        assertFalse(offline.getAnnotationsByType(Tag::class.java).any { it.value == "mysql" })
    }
}
