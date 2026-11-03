package com.cliptray.domain;

import java.sql.SQLException;
import java.util.List;

public interface ClipboardRepository extends AutoCloseable {
    void saveOrTouchLatest(String content, long timestamp) throws SQLException;

    List<ClipboardEntry> findRecent(int limit) throws SQLException;

    List<ClipboardEntry> search(String keyword, int limit) throws SQLException;

    void delete(long id) throws SQLException;

    @Override
    void close() throws SQLException;
}
