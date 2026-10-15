import com.noLate.NoLateApplication;
import com.noLate.notification.application.PushClient;
import com.noLate.notification.application.service.NotificationTokenService;
import com.noLate.notification.application.useCase.NotificationUseCase;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Explicitly authorized, single-recipient operator probe. Never an ETA/sound accuracy test. */
class PhysicalIphonePushProbe {
    private static final long MEMBER = 282L;
    private static final String EXPECTED_DB_UUID = "86b39c6e-b64a-11ef-b7a1-3cf86282001e";

    public static void main(String[] args) {
        try {
            if (args.length != 1 || !Set.of("inspect", "prepare", "send-once").contains(args[0]))
                throw new IllegalArgumentException("Explicit inspect, prepare or send-once required");
            var properties = new Properties();
            try (var reader = Files.newBufferedReader(Path.of("env.local.properties"))) {
                properties.load(reader);
            }
            String url = "jdbc:mysql://" + required(properties, "MY_SQL_END_POINT") + ":" +
                properties.getProperty("MY_SQL_PORT", "3306") + "/" + required(properties, "MY_SQL_DB_NAME") +
                "?connectTimeout=3000&socketTimeout=5000";
            try (var connection = DriverManager.getConnection(url, required(properties, "MY_SQL_DB_USERNAME"),
                    required(properties, "MY_SQL_DB_PASSWORD"))) {
                connection.setReadOnly(true);
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT @@server_uuid")) {
                    if (!rows.next() || !EXPECTED_DB_UUID.equals(rows.getString(1)))
                        throw new IllegalStateException("Operating DB identity mismatch");
                }
                try (var statement = connection.prepareStatement("SELECT login_type, deleted FROM member WHERE id=?")) {
                    statement.setLong(1, MEMBER);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next() || rows.getBoolean(2) || !"APPLE".equals(rows.getString(1)))
                            throw new IllegalStateException("Recipient identity mismatch");
                    }
                }
                int count = 0;
                try (var statement = connection.prepareStatement(
                        "SELECT id, platform, delivery_ack_capability_version, updated_at FROM push_device_token " +
                        "WHERE member_id=? AND retirement_requested=0")) {
                    statement.setLong(1, MEMBER);
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            count++;
                            System.out.printf("ELIGIBLE tokenRecordId=%d platform=%s ackCapability=%s updatedAt=%s%n",
                                rows.getLong(1), rows.getString(2), rows.getObject(3), rows.getObject(4));
                            if (!"IOS".equals(rows.getString(2)))
                                throw new IllegalStateException("Unexpected recipient platform");
                        }
                    }
                }
                System.out.println("INSPECT memberId=" + MEMBER + " eligibleDevices=" + count);
                if (count != 1) throw new IllegalStateException("Exactly one eligible iOS device required");
            }
            if (args[0].equals("inspect")) return;

            var application = new SpringApplication(NoLateApplication.class);
            application.addInitializers(context -> context.addBeanFactoryPostProcessor(factory -> {
                var registry = (BeanDefinitionRegistry) factory;
                if (!registry.containsBeanDefinition("schedulePushJobBackfill"))
                    throw new IllegalStateException("Missing startup scope guard");
                registry.removeBeanDefinition("schedulePushJobBackfill");
            }));
            try (var context = application.run(
                    "--spring.config.location=file:src/main/resources/application.yml",
                    "--spring.profiles.active=local", "--server.address=127.0.0.1", "--server.port=0",
                    "--spring.task.scheduling.enabled=false", "--schedule.push.enabled=false",
                    "--notification.push-outbox.enabled=false", "--spring.jpa.hibernate.ddl-auto=validate",
                    "--spring.sql.init.mode=never", "--firebase.enabled=true", "--logging.level.root=ERROR",
                    "--logging.level.com.noLate=ERROR", "--logging.level.org.hibernate.SQL=OFF",
                    "--logging.level.com.noLate.global.logging.P6SpySqlErrorEventListener=OFF",
                    "--spring.main.banner-mode=off")) {
                if (!context.getBean(PushClient.class).getClass().getSimpleName().equals("FirebasePushClient"))
                    throw new IllegalStateException("Real Firebase client required");
                var tokens = context.getBean(NotificationTokenService.class).getTokensByMember(MEMBER);
                if (tokens.size() != 1 || !tokens.get(0).getPlatform().name().equals("IOS"))
                    throw new IllegalStateException("Recipient device changed; stopped before send");
                if (args[0].equals("prepare")) {
                    System.out.println("PREPARED realFirebase=true eligibleIosDevices=1 automaticJobs=false schemaMutation=false sent=0");
                    return;
                }
                // CREATE_NEW prevents an ambiguous provider outcome from becoming an accidental resend.
                var marker = Path.of("/private/tmp/nolate-physical-push-282-20261012.attempted");
                Files.writeString(marker, "Authorized one push; attempted at " + Instant.now(), StandardOpenOption.CREATE_NEW);
                var result = context.getBean(NotificationUseCase.class).sendToMember(
                    MEMBER, "NoLate 실기기 수신 확인 1012", "테스트 알림 1건입니다. 일정과 출발 알람은 변경하지 않았습니다.",
                    Map.of("type", "PUSH_SCENARIO_TOKEN_CHECK"), null, false);
                System.out.printf("PROBE_RESULT at=%s requested=%d attempted=%d sent=%d failed=%d ambiguous=%d deferred=%d%n",
                    Instant.now(), result.getRequestedCount(), result.getAttemptedCount(), result.getSentCount(),
                    result.getFailedCount(), result.getAmbiguousCount(), result.getDeferredCount());
            }
        } catch (Exception error) {
            System.err.println("PROBE_STOPPED type=" + error.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required configuration absent: " + key);
        return value;
    }
}
