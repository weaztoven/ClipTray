package com.cliptray.persistence;

import com.cliptray.domain.ClipboardEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteClipboardRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    private SqliteClipboardRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        repository = new SqliteClipboardRepository(
                new DatabaseManager(temporaryDirectory.resolve("history.db")));
    }

    @AfterEach
    void tearDown() throws Exception {
        repository.close();
    }

    @Test
    void touchesOnlyTheMostRecentMatchingEntry() throws Exception {
        repository.saveOrTouchLatest("alpha", 100);
        repository.saveOrTouchLatest("alpha", 200);
        repository.saveOrTouchLatest("beta", 300);
        repository.saveOrTouchLatest("alpha", 400);

        List<ClipboardEntry> entries = repository.findRecent(50);

        assertEquals(3, entries.size());
        assertEquals("alpha", entries.get(0).content());
        assertEquals(400, entries.get(0).timestamp());
        assertEquals("beta", entries.get(1).content());
        assertEquals("alpha", entries.get(2).content());
        assertEquals(200, entries.get(2).timestamp());
    }

    @Test
    void searchesLiteralKeywordsAndDeletesById() throws Exception {
        repository.saveOrTouchLatest("100% ready", 100);
        repository.saveOrTouchLatest("100x ready", 200);

        List<ClipboardEntry> matches = repository.search("%", 50);
        assertEquals(1, matches.size());
        assertEquals("100% ready", matches.get(0).content());

        repository.delete(matches.get(0).id());
        assertEquals(1, repository.findRecent(50).size());
        assertEquals("100x ready", repository.findRecent(50).get(0).content());
    }

    @Test
    void serializesConcurrentDeduplicationUpdates() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<? extends Future<?>> saves = java.util.stream.IntStream.range(0, 64)
                    .mapToObj(index -> executor.submit(() -> {
                        repository.saveOrTouchLatest("same clipboard text", index);
                        return null;
                    }))
                    .toList();
            for (Future<?> save : saves) {
                save.get();
            }

            List<ClipboardEntry> entries = repository.findRecent(50);
            assertEquals(1, entries.size());
            assertEquals("same clipboard text", entries.get(0).content());
        } finally {
            executor.shutdownNow();
        }
    }
}
