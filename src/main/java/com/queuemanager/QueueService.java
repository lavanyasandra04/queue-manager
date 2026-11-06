package com.queuemanager;

import com.queuemanager.Models.ArrivalPoint;
import com.queuemanager.Models.QueueInfo;
import com.queuemanager.Models.Token;
import com.queuemanager.Models.TrendPoint;
import com.queuemanager.Models.WaitPoint;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * All queue / token logic. Every method takes the logged-in user's id and only
 * touches that manager's own queues.
 *
 * Token lifecycle: waiting -> serving -> served, or waiting -> cancelled.
 */
@Service
public class QueueService {
    private static final String COLS =
            "id, queue_id, token_no, name, status, pos, created_at, started_at, completed_at, cancelled_at";

    private static final RowMapper<Token> TOKEN = (rs, i) -> new Token(
            rs.getLong("id"),
            rs.getLong("queue_id"),
            rs.getInt("token_no"),
            rs.getString("name"),
            rs.getString("status"),
            rs.getInt("pos"),
            rs.getLong("created_at"),
            nullableLong(rs, "started_at"),
            nullableLong(rs, "completed_at"),
            nullableLong(rs, "cancelled_at"));

    private final JdbcTemplate jdbc;

    public QueueService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private long insert(String table, Map<String, Object> cols) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns("id")
                .executeAndReturnKey(cols).longValue();
    }

    // ---------- lookups (with ownership checks) ----------

    private QueueInfo ownedQueue(long queueId, long userId) {
        List<QueueInfo> found = jdbc.query(
                "SELECT id, name, created_at FROM queues WHERE id = ? AND user_id = ?",
                (rs, i) -> new QueueInfo(rs.getLong("id"), rs.getString("name"), rs.getLong("created_at"), null),
                queueId, userId);
        if (found.isEmpty()) throw new ApiException(404, "Queue not found");
        return found.get(0);
    }

    private Token findToken(long tokenId) {
        List<Token> found = jdbc.query("SELECT " + COLS + " FROM tokens WHERE id = ?", TOKEN, tokenId);
        return found.isEmpty() ? null : found.get(0);
    }

    private Token ownedToken(long tokenId, long userId) {
        Token t = findToken(tokenId);
        Integer mine = t == null ? 0 : jdbc.queryForObject(
                "SELECT COUNT(*) FROM queues WHERE id = ? AND user_id = ?", Integer.class, t.queueId(), userId);
        if (t == null || mine == null || mine == 0) throw new ApiException(404, "Token not found");
        return t;
    }

    private List<Token> tokensWhere(long queueId, String condition) {
        return jdbc.query("SELECT " + COLS + " FROM tokens WHERE queue_id = ? AND " + condition, TOKEN, queueId);
    }

    // ---------- queues ----------

    public List<QueueInfo> listQueues(long userId) {
        return jdbc.query(
                "SELECT q.id, q.name, q.created_at, "
                        + "(SELECT COUNT(*) FROM tokens t WHERE t.queue_id = q.id AND t.status = 'waiting') AS waiting "
                        + "FROM queues q WHERE q.user_id = ? ORDER BY q.created_at DESC, q.id DESC",
                (rs, i) -> new QueueInfo(rs.getLong("id"), rs.getString("name"), rs.getLong("created_at"),
                        rs.getLong("waiting")),
                userId);
    }

    public QueueInfo createQueue(long userId, String rawName) {
        String name = rawName.trim();
        if (name.isEmpty()) throw new ApiException(400, "Queue name is required");
        if (name.length() > 60) throw new ApiException(400, "Queue name must be 60 characters or fewer");
        long now = System.currentTimeMillis();
        Map<String, Object> cols = new HashMap<>();
        cols.put("user_id", userId);
        cols.put("name", name);
        cols.put("created_at", now);
        long id = insert("queues", cols);
        return new QueueInfo(id, name, now, null);
    }

    public void deleteQueue(long queueId, long userId) {
        ownedQueue(queueId, userId);
        jdbc.update("DELETE FROM queues WHERE id = ?", queueId); // tokens are removed by ON DELETE CASCADE
    }

    public Map<String, Object> queueState(long queueId, long userId) {
        QueueInfo queue = ownedQueue(queueId, userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queue", queue);
        out.put("now", System.currentTimeMillis());
        out.put("waiting", tokensWhere(queueId, "status = 'waiting' ORDER BY pos"));
        out.put("serving", tokensWhere(queueId, "status = 'serving' ORDER BY started_at"));
        out.put("history", tokensWhere(queueId,
                "status IN ('served','cancelled') ORDER BY COALESCE(completed_at, cancelled_at) DESC LIMIT 10"));
        return out;
    }

    // ---------- tokens ----------

    /** Add a person: creates the next token number and puts it at the end of the queue. */
    @Transactional
    public synchronized Token addToken(long queueId, long userId, String rawName) {
        ownedQueue(queueId, userId);
        String name = rawName.trim();
        if (name.isEmpty()) throw new ApiException(400, "Enter a name for the person");
        if (name.length() > 60) throw new ApiException(400, "Name must be 60 characters or fewer");

        Integer number = jdbc.queryForObject(
                "SELECT COALESCE(MAX(token_no), 0) + 1 FROM tokens WHERE queue_id = ?", Integer.class, queueId);
        Integer position = jdbc.queryForObject(
                "SELECT COALESCE(MAX(pos), 0) + 1 FROM tokens WHERE queue_id = ?", Integer.class, queueId);

        Map<String, Object> cols = new HashMap<>();
        cols.put("queue_id", queueId);
        cols.put("token_no", number);
        cols.put("name", name);
        cols.put("status", "waiting");
        cols.put("pos", position);
        cols.put("created_at", System.currentTimeMillis());
        return findToken(insert("tokens", cols));
    }

    /** Swap a waiting token with its neighbour above or below. */
    @Transactional
    public void moveToken(long tokenId, long userId, String direction) {
        if (!"up".equals(direction) && !"down".equals(direction)) {
            throw new ApiException(400, "direction must be \"up\" or \"down\"");
        }
        Token t = ownedToken(tokenId, userId);
        if (!"waiting".equals(t.status())) throw new ApiException(400, "Only waiting tokens can be moved");

        List<long[]> waiting = jdbc.query(
                "SELECT id, pos FROM tokens WHERE queue_id = ? AND status = 'waiting' ORDER BY pos",
                (rs, n) -> new long[] {rs.getLong("id"), rs.getLong("pos")},
                t.queueId());
        int i = -1;
        for (int k = 0; k < waiting.size(); k++) {
            if (waiting.get(k)[0] == t.id()) i = k;
        }
        int j = "up".equals(direction) ? i - 1 : i + 1;
        if (i < 0 || j < 0 || j >= waiting.size()) {
            throw new ApiException(400, "up".equals(direction) ? "Already at the top" : "Already at the bottom");
        }
        jdbc.update("UPDATE tokens SET pos = ? WHERE id = ?", waiting.get(j)[1], waiting.get(i)[0]);
        jdbc.update("UPDATE tokens SET pos = ? WHERE id = ?", waiting.get(i)[1], waiting.get(j)[0]);
    }

    public void cancelToken(long tokenId, long userId) {
        Token t = ownedToken(tokenId, userId);
        if (!"waiting".equals(t.status())) throw new ApiException(400, "Only waiting tokens can be cancelled");
        jdbc.update("UPDATE tokens SET status = 'cancelled', cancelled_at = ? WHERE id = ?",
                System.currentTimeMillis(), tokenId);
    }

    public void completeToken(long tokenId, long userId) {
        Token t = ownedToken(tokenId, userId);
        if (!"serving".equals(t.status())) throw new ApiException(400, "Token is not being served");
        jdbc.update("UPDATE tokens SET status = 'served', completed_at = ? WHERE id = ?",
                System.currentTimeMillis(), tokenId);
    }

    /**
     * Assign the token at the top of the queue for service.
     * Any token currently being served is marked as completed first.
     */
    @Transactional
    public synchronized Token serveNext(long queueId, long userId) {
        ownedQueue(queueId, userId);
        List<Token> top = tokensWhere(queueId, "status = 'waiting' ORDER BY pos LIMIT 1");
        if (top.isEmpty()) throw new ApiException(400, "Nobody is waiting in this queue");
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE tokens SET status = 'served', completed_at = ? WHERE queue_id = ? AND status = 'serving'",
                now, queueId);
        jdbc.update("UPDATE tokens SET status = 'serving', started_at = ? WHERE id = ?", now, top.get(0).id());
        return findToken(top.get(0).id());
    }

    // ---------- analytics ----------

    private static Double average(List<Long> values) {
        if (values.isEmpty()) return null;
        long sum = 0;
        for (long v : values) sum += v;
        return (double) sum / values.size();
    }

    public Map<String, Object> analytics(long queueId, long userId) {
        ownedQueue(queueId, userId);
        List<Token> tokens = jdbc.query(
                "SELECT " + COLS + " FROM tokens WHERE queue_id = ? ORDER BY created_at, id", TOKEN, queueId);
        long now = System.currentTimeMillis();

        List<Token> started = new ArrayList<>();
        List<Long> waits = new ArrayList<>();
        List<Long> services = new ArrayList<>();
        long waiting = 0, serving = 0, served = 0, cancelled = 0;
        Long oldestWaiting = null;
        for (Token t : tokens) {
            if (t.startedAt() != null) {
                started.add(t);
                waits.add(t.startedAt() - t.createdAt());
            }
            switch (t.status()) {
                case "waiting" -> {
                    waiting++;
                    if (oldestWaiting == null || t.createdAt() < oldestWaiting) oldestWaiting = t.createdAt();
                }
                case "serving" -> serving++;
                case "served" -> {
                    served++;
                    services.add(t.completedAt() - t.startedAt());
                }
                case "cancelled" -> cancelled++;
                default -> { }
            }
        }

        // Queue length over time: +1 when a token joins, -1 when it is called or cancelled.
        List<long[]> events = new ArrayList<>();
        for (Token t : tokens) {
            events.add(new long[] {t.createdAt(), 1});
            Long left = t.startedAt() != null ? t.startedAt() : t.cancelledAt();
            if (left != null) events.add(new long[] {left, -1});
        }
        events.sort(Comparator.comparingLong(e -> e[0]));
        List<TrendPoint> trend = new ArrayList<>();
        int length = 0;
        for (long[] e : events) {
            length += (int) e[1];
            trend.add(new TrendPoint(e[0], length));
        }
        List<TrendPoint> lastTrend = trend.subList(Math.max(0, trend.size() - 60), trend.size());

        List<WaitPoint> waitPerToken = new ArrayList<>();
        for (Token t : started.subList(Math.max(0, started.size() - 20), started.size())) {
            waitPerToken.add(new WaitPoint(t.number(), t.startedAt() - t.createdAt()));
        }

        // Arrivals per hour, last 12 hours.
        final long hour = 3_600_000L;
        long base = (now / hour) * hour;
        List<ArrivalPoint> arrivals = new ArrayList<>();
        for (int i = 11; i >= 0; i--) {
            long start = base - i * hour;
            long count = 0;
            for (Token t : tokens) {
                if (t.createdAt() >= start && t.createdAt() < start + hour) count++;
            }
            arrivals.add(new ArrivalPoint(start, count));
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("waiting", waiting);
        totals.put("serving", serving);
        totals.put("served", served);
        totals.put("cancelled", cancelled);
        totals.put("total", tokens.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", totals);
        out.put("avgWaitMs", average(waits));
        out.put("maxWaitMs", waits.isEmpty() ? null : java.util.Collections.max(waits));
        out.put("avgServiceMs", average(services));
        out.put("longestCurrentWaitMs", oldestWaiting == null ? 0L : now - oldestWaiting);
        out.put("lengthTrend", new ArrayList<>(lastTrend));
        out.put("waitPerToken", waitPerToken);
        out.put("arrivals", arrivals);
        return out;
    }
}
