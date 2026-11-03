package com.cliptray.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseManager implements AutoCloseable {
    private final Connection connection;

    public DatabaseManager(Path databasePath) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
        try {
            initializeSchema();
        } catch (SQLException exception) {
            try {
                connection.close();
            } catch (SQLException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    Connection connection() {
        return connection;
    }

    private void initializeSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS clipboard_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        content TEXT NOT NULL,
                        "timestamp" INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_clipboard_history_timestamp
                    ON clipboard_history ("timestamp" DESC)
                    """);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
