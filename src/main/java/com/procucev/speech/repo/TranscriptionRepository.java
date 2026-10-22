package com.procucev.speech.repo;

import com.procucev.speech.domain.Transcription;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

/** Plain-SQL data access (Spring JDBC). Table defined in schema.sql. */
@Repository
public class TranscriptionRepository {

    private static final String COLUMNS =
            "id, title, transcript, language, duration_seconds, created_at, updated_at";

    private static final RowMapper<Transcription> MAPPER = TranscriptionRepository::map;

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    public TranscriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc)
                .withTableName("transcriptions")
                .usingGeneratedKeyColumns("id");
    }

    private static Transcription map(ResultSet rs, int row) throws SQLException {
        Transcription t = new Transcription();
        t.setId(rs.getLong("id"));
        t.setTitle(rs.getString("title"));
        t.setText(rs.getString("transcript"));
        t.setLanguage(rs.getString("language"));
        int d = rs.getInt("duration_seconds");
        t.setDurationSeconds(rs.wasNull() ? null : d);
        t.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        t.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return t;
    }

    public List<Transcription> findPage(String q, int limit, int offset) {
        if (q == null || q.isBlank()) {
            return jdbc.query("SELECT " + COLUMNS + " FROM transcriptions "
                    + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", MAPPER, limit, offset);
        }
        String like = "%" + q.trim().toLowerCase() + "%";
        return jdbc.query("SELECT " + COLUMNS + " FROM transcriptions "
                + "WHERE LOWER(title) LIKE ? OR LOWER(transcript) LIKE ? "
                + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", MAPPER, like, like, limit, offset);
    }

    public long count(String q) {
        Long n;
        if (q == null || q.isBlank()) {
            n = jdbc.queryForObject("SELECT COUNT(*) FROM transcriptions", Long.class);
        } else {
            String like = "%" + q.trim().toLowerCase() + "%";
            n = jdbc.queryForObject("SELECT COUNT(*) FROM transcriptions "
                    + "WHERE LOWER(title) LIKE ? OR LOWER(transcript) LIKE ?", Long.class, like, like);
        }
        return n == null ? 0 : n;
    }

    public Optional<Transcription> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM transcriptions WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public long insert(String title, String text, String language, Integer durationSeconds) {
        Timestamp now = Timestamp.from(Instant.now());
        Map<String, Object> row = new HashMap<>();
        row.put("title", title);
        row.put("transcript", text);
        row.put("language", language);
        row.put("duration_seconds", durationSeconds);
        row.put("created_at", now);
        row.put("updated_at", now);
        return insert.executeAndReturnKey(row).longValue();
    }

    /** @return number of rows updated (0 if the id does not exist) */
    public int update(long id, String title, String text, String language) {
        return jdbc.update("UPDATE transcriptions SET title = ?, transcript = ?, language = ?, "
                        + "updated_at = ? WHERE id = ?",
                title, text, language, Timestamp.from(Instant.now()), id);
    }

    /** @return number of rows deleted (0 if the id does not exist) */
    public int delete(long id) {
        return jdbc.update("DELETE FROM transcriptions WHERE id = ?", id);
    }
}
