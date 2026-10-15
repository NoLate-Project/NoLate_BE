import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Read-only evidence for newly authorized synthetic map-comparison schedules.
 * No credentials, authentication records, other members, or write statements.
 */
class ReadRealEtaComparison {
    static String quote(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
    static String rows(Connection c, String sql, String title) throws Exception {
        try (var q = c.prepareStatement(sql)) {
            q.setString(1, title);
            try (var r = q.executeQuery()) {
                var out = new ArrayList<String>();
                while (r.next()) {
                    var fields = new ArrayList<String>();
                    for (int n = 1; n <= r.getMetaData().getColumnCount(); n++)
                        fields.add(quote(r.getMetaData().getColumnLabel(n)) + ":" + quote(r.getString(n)));
                    out.add("{" + String.join(",", fields) + "}");
                }
                return "[" + String.join(",", out) + "]";
            }
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].matches("ETA E2E NAVER 1007 0[1-4]") ||
            !args[1].startsWith("/private/tmp/nolate-real-naver-")) throw new IllegalArgumentException("Scoped title/output required");
        var settings = new Properties();
        try (var input = Files.newBufferedReader(Path.of("env.local.properties"))) { settings.load(input); }
        var url = "jdbc:mysql://" + settings.getProperty("MY_SQL_END_POINT") + ":" + settings.getProperty("MY_SQL_PORT", "3306") +
            "/" + settings.getProperty("MY_SQL_DB_NAME") + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=3000&socketTimeout=5000";
        try (var c = DriverManager.getConnection(url, settings.getProperty("MY_SQL_DB_USERNAME"), settings.getProperty("MY_SQL_DB_PASSWORD"))) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try {
                String scope = " JOIN schedules s ON s.id = x.schedule_id WHERE s.member_id = 296 AND s.title = ?";
                String schedules = rows(c, "SELECT id, member_id, title, start_at, end_at, deleted FROM schedules WHERE member_id = 296 AND title = ?", args[0]);
                String route = rows(c, "SELECT x.* FROM schedule_routes x" + scope, args[0]);
                String personal = rows(c, "SELECT x.* FROM schedule_travel_plans x" + scope + " AND x.member_id = 296", args[0]);
                String job = rows(c, "SELECT x.* FROM schedule_push_job x" + scope + " AND x.member_id = 296", args[0]);
                String history = rows(c, "SELECT x.* FROM schedule_eta_evaluation_history x" + scope + " AND x.member_id = 296 ORDER BY x.id DESC LIMIT 3", args[0]);
                String evidence = "{\"observedAt\":" + quote(java.time.Instant.now().toString()) + ",\"dbSessionZone\":\"UTC\",\"schedules\":" + schedules +
                    ",\"routes\":" + route + ",\"personalPlans\":" + personal + ",\"jobs\":" + job + ",\"history\":" + history + "}";
                Files.writeString(Path.of(args[1]), evidence, StandardOpenOption.CREATE_NEW);
                System.out.println("Scoped member-296 read-only evidence saved: " + args[1]);
            } finally { c.rollback(); }
        } catch (Exception error) {
            // JDBC exceptions can contain endpoint/user details; do not surface the original.
            System.err.println("Scoped evidence read failed: " + error.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
