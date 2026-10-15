import com.noLate.NoLateApplication;
import com.noLate.schedule.application.service.SchedulePushJobWorker;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import javax.sql.DataSource;
import java.time.Instant;
import java.nio.file.*;

/** Real saved-schedule Worker entry point; no fixture or forced due-time mutation. */
class RunRealEtaComparison {
    public static void main(String[] args) {
        if (args.length != 2 || !args[0].matches("365|366|367") ||
            !args[1].startsWith("/private/tmp/nolate-real-naver-requery-")) {
            throw new IllegalArgumentException("Explicit synthetic schedule/output required");
        }
        long id = Long.parseLong(args[0]);
        String title = switch ((int) id) {
            case 365 -> "ETA E2E NAVER 1007 02";
            case 366 -> "ETA E2E NAVER 1007 03";
            case 367 -> "ETA E2E NAVER 1007 04";
            default -> throw new IllegalArgumentException("Unsupported synthetic schedule");
        };
        var application = new SpringApplication(NoLateApplication.class);
        // A normal application startup includes global repair initializers. This scoped tool
        // removes only those startup-only beans before instantiation, never production code.
        application.addInitializers(context -> context.addBeanFactoryPostProcessor(factory -> {
            var registry = (BeanDefinitionRegistry) factory;
            for (String name : new String[]{"scheduleCalendarCacheRevisionBootstrapInitializer", "schedulePushJobBackfill"}) {
                if (!registry.containsBeanDefinition(name)) throw new IllegalStateException("Startup scope guard missing");
                registry.removeBeanDefinition(name);
            }
        }));
        // Separate ephemeral loopback API; no global scheduler, schema changes or public probe API.
        try (var context = application.run("--server.address=127.0.0.1", "--server.port=0",
                "--spring.task.scheduling.enabled=false", "--schedule.push.enabled=true",
                "--spring.jpa.hibernate.ddl-auto=validate", "--spring.sql.init.mode=never",
                "--logging.level.root=WARN", "--logging.level.com.noLate=WARN",
                "--logging.level.com.noLate.global.logging.P6SpySqlErrorEventListener=OFF", "--spring.main.banner-mode=off")) {
            try (var connection = context.getBean(DataSource.class).getConnection()) {
                connection.setReadOnly(true);
                try (var statement = connection.prepareStatement(
                    "SELECT title, start_at > UTC_TIMESTAMP() AS future FROM schedules WHERE member_id=296 AND id=? AND deleted=0")) {
                    statement.setLong(1, id);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next() || !title.equals(rows.getString("title")) || !rows.getBoolean("future") || rows.next())
                            throw new IllegalStateException("Synthetic future schedule scope mismatch");
                    }
                }
            }
            var worker = context.getBean(SchedulePushJobWorker.class);
            var deadline = Instant.now().plusSeconds(300);
            int processed = 0;
            // Poll only this due job; preserve the persisted cadence and cross-server claim fence.
            while (processed == 0 && Instant.now().isBefore(deadline)) {
                processed = worker.runDueJobForSchedule(296, id, Instant.now());
                if (processed == 0) Thread.sleep(500);
            }
            var evidence = "{\"classification\":\"REAL_SCOPED_WORKER_NOT_FIXTURE\",\"memberId\":296,\"scheduleId\":" + id +
                ",\"processed\":" + processed + ",\"observedAt\":\"" + Instant.now() + "\"}";
            Files.writeString(Path.of(args[1]), evidence, StandardOpenOption.CREATE_NEW);
            System.out.println(evidence);
            if (processed != 1) throw new IllegalStateException("No due job claimed within bounded observation");
        } catch (Exception error) {
            System.err.println("Scoped real Worker failed: " + error.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
