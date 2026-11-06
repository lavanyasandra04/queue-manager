package com.queuemanager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserService(JdbcTemplate jdbc, JwtService jwt) {
        this.jdbc = jdbc;
        this.jwt = jwt;
    }

    public Map<String, Object> register(String rawUsername, String password) {
        String username = rawUsername.trim().toLowerCase();
        if (username.length() < 3) throw new ApiException(400, "Username must be at least 3 characters");
        if (password.length() < 6) throw new ApiException(400, "Password must be at least 6 characters");
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
        if (existing != null && existing > 0) throw new ApiException(409, "That username is taken");

        Map<String, Object> cols = new HashMap<>();
        cols.put("username", username);
        cols.put("password_hash", encoder.encode(password));
        long id;
        try {
            id = new SimpleJdbcInsert(jdbc).withTableName("users").usingGeneratedKeyColumns("id")
                    .executeAndReturnKey(cols).longValue();
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "That username is taken");
        }
        return Map.of("token", jwt.sign(id, username), "username", username);
    }

    public Map<String, Object> login(String rawUsername, String password) {
        String username = rawUsername.trim().toLowerCase();
        List<Object[]> rows = jdbc.query(
                "SELECT id, password_hash FROM users WHERE username = ?",
                (rs, i) -> new Object[] {rs.getLong("id"), rs.getString("password_hash")},
                username);
        if (rows.isEmpty() || !encoder.matches(password, (String) rows.get(0)[1])) {
            throw new ApiException(401, "Wrong username or password");
        }
        long id = (Long) rows.get(0)[0];
        return Map.of("token", jwt.sign(id, username), "username", username);
    }
}
