import com.noLate.NoLateApplication;
import com.noLate.eta.application.port.TransitJourneyProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Local operator launcher: normal auth/workers, explicit no-DDL startup and redacted readiness. */
class StartLocalBackend {
    public static void main(String[] args) {
        try {
            if (args.length != 1 || !args[0].startsWith("/private/tmp/nolate-local-restart."))
                throw new IllegalArgumentException("Fresh private evidence directory required");
            var context = new SpringApplication(NoLateApplication.class).run(
                "--spring.config.location=file:src/main/resources/application.yml",
                "--server.port=5522", "--spring.jpa.hibernate.ddl-auto=validate", "--spring.sql.init.mode=never",
                "--logging.level.root=WARN", "--logging.level.com.noLate=WARN",
                "--logging.level.com.noLate.global.logging.P6SpySqlErrorEventListener=OFF",
                "--spring.main.banner-mode=off");
            var env = context.getEnvironment();
            var providers = context.getBeansOfType(TransitJourneyProvider.class).values().stream()
                .map(p -> p.getProviderId()).sorted().toList();
            if (!providers.contains("odsay")) throw new IllegalStateException("ODsay provider not loaded");
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("startedAt", Instant.now().toString());
            evidence.put("pid", ProcessHandle.current().pid()); evidence.put("port", 5522);
            evidence.put("odsayEnabled", env.getProperty("eta.transit.odsay.enabled", Boolean.class, false));
            evidence.put("odsayKeyConfigured", !env.getProperty("eta.transit.odsay.api-key", "").isBlank());
            evidence.put("journeyProviders", providers);
            evidence.put("automaticSchedulingEnabled", env.getProperty("spring.task.scheduling.enabled", Boolean.class, true));
            evidence.put("schemaMode", env.getProperty("spring.jpa.hibernate.ddl-auto"));
            evidence.put("sqlInitMode", env.getProperty("spring.sql.init.mode"));
            Files.writeString(Path.of(args[0], "backend-ready.json"), new ObjectMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(evidence), StandardOpenOption.CREATE_NEW);
            System.out.println("LOCAL_BACKEND_READY realOdsay=true port=5522");
        } catch (Exception error) {
            System.err.println("LOCAL_BACKEND_FAILED kind=" + error.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
