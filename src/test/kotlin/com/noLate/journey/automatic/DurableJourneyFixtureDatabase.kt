package com.noLate.journey.automatic

import com.noLate.journey.domain.AutomaticJourneyPreparation
import com.noLate.journey.domain.JourneyActivityLane
import com.noLate.journey.infrastructure.AutomaticJourneyPreparationRepository
import com.noLate.journey.infrastructure.JourneyActivityLaneRepository
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.*
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

/** TEST SOURCE ONLY. Two entities, isolated H2 file, no application context/resources/prod DB. */
class DurableJourneyFixtureDatabase(url: String, username: String = "sa", password: String = "",
    schemaAction: String = "update", dialect: String = "org.hibernate.dialect.H2Dialect") : AutoCloseable {
    private val factory = LocalContainerEntityManagerFactoryBean().apply {
        dataSource = DriverManagerDataSource(url, username, password)
        jpaVendorAdapter = HibernateJpaVendorAdapter()
        setManagedTypes(PersistenceManagedTypes.of(AutomaticJourneyPreparation::class.java.name, JourneyActivityLane::class.java.name))
        setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to schemaAction, "hibernate.show_sql" to false,
            "hibernate.dialect" to dialect))
        afterPropertiesSet()
    }
    val manager = JpaTransactionManager(requireNotNull(factory.`object`))
    private val repositories = JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(requireNotNull(factory.`object`)))
    val preparations = repositories.getRepository(AutomaticJourneyPreparationRepository::class.java)
    val lanes = repositories.getRepository(JourneyActivityLaneRepository::class.java)
    @Suppress("UNCHECKED_CAST") fun <T> tx(action: () -> T): T = TransactionTemplate(manager).execute { action() } as T
    override fun close() = factory.destroy()
    companion object {
        fun fromEnvironment(): Pair<DurableJourneyFixtureDatabase, String>? {
            val directory = System.getenv("NOLATE_JOURNEY_DURABLE_DIR") ?: return null
            val path = Path.of(directory)
            require(path.isAbsolute && path.parent == Path.of("/private/tmp") && path.fileName.toString().startsWith("nolate-journey-durable-")
                && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
                && Files.getPosixFilePermissions(path) == PosixFilePermissions.fromString("rwx------")) { "Invalid isolated durable directory" }
            val keyPath = path.resolve("fixture-activity.key")
            if (!Files.exists(keyPath, LinkOption.NOFOLLOW_LINKS)) {
                val value = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
                Files.writeString(keyPath, value, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                Files.setPosixFilePermissions(keyPath, PosixFilePermissions.fromString("rw-------"))
            }
            require(!Files.isSymbolicLink(keyPath) && Files.getPosixFilePermissions(keyPath) == PosixFilePermissions.fromString("rw-------"))
            val key = Files.readString(keyPath)
            require(runCatching { Base64.getDecoder().decode(key).size == 32 }.getOrDefault(false))
            return DurableJourneyFixtureDatabase("jdbc:h2:file:${path.resolve("journey")};MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE") to key
        }
    }
}
