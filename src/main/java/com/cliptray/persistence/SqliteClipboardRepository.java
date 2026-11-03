package com.cliptray.persistence;

import com.cliptray.domain.ClipboardEntry;
import com.cliptray.domain.ClipboardRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class SqliteClipboardRepository implements ClipboardRepository {
    private final DatabaseManager databaseManager;
    private final Connection connection;

    public SqliteClipboardRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        this.connection = databaseManager.connection();
    }

    @Override
    public synchronized void saveOrTouchLatest(String content, long timestamp) throws SQLException {
        if (content == null || content.isBlank()) {
            return;
        }

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT id, content FROM clipboard_history ORDER BY id DESC LIMIT 1");
                 ResultSet result = query.executeQuery()) {
                if (result.next() && content.equals(result.getString("content"))) {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE clipboard_history SET \"timestamp\" = ? WHERE id = ?")) {
                        update.setLong(1, timestamp);
                        update.setLong(2, result.getLong("id"));
                        update.executeUpdate();
                    }
                } else {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO clipboard_history (content, \"timestamp\") VALUES (?, ?)")) {
                        insert.setString(1, content);
                        insert.setLong(2, timestamp);
                        insert.executeUpdate();
                    }
                }
            }
            connection.commit();
        } catch (SQLException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackException) {
                exception.addSuppressed(rollbackException);
            }
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    @Override
    public synchronized List<ClipboardEntry> findRecent(int limit) throws SQLException {
        String sql = """
                SELECT id, content, "timestamp"
                FROM clipboard_history
                ORDER BY id DESC
                LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, boundedLimit(limit));
            try (ResultSet result = statement.executeQuery()) {
                return readEntries(result);
            }
        }
    }

    @Override
    public synchronized List<ClipboardEntry> search(String keyword, int limit) throws SQLException {
        if (keyword == null || keyword.isBlank()) {
            return findRecent(limit);
        }

        String escapedKeyword = keyword.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        String sql = """
                SELECT id, content, "timestamp"
                FROM clipboard_history
                WHERE content LIKE ? ESCAPE '\\'
                ORDER BY id DESC
                LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "%" + escapedKeyword + "%");
            statement.setInt(2, boundedLimit(limit));
            try (ResultSet result = statement.executeQuery()) {
                return readEntries(result);
            }
        }
    }

    @Override
    public synchronized void delete(long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM clipboard_history WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        databaseManager.close();
    }

    private static int boundedLimit(int limit) {
        return Math.max(1, Math.min(limit, 500));
    }

    private static List<ClipboardEntry> readEntries(ResultSet result) throws SQLException {
        List<ClipboardEntry> entries = new ArrayList<>();
        while (result.next()) {
            entries.add(new ClipboardEntry(
                    result.getLong("id"),
                    result.getString("content"),
                    result.getLong("timestamp")));
        }
        return entries;
    }
}
