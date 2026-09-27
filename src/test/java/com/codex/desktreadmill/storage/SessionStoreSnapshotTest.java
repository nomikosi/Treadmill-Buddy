package com.codex.desktreadmill.storage;

import com.codex.desktreadmill.model.SessionData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreSnapshotTest {
    @TempDir Path directory;

    private static SessionData walk(String id, long seconds) {
        SessionData session = new SessionData();
        session.id = id;
        session.name = id;
        session.elapsedSeconds = seconds;
        return session;
    }

    @Test
    void savingAfterAConcurrentReplacementDuringInitialReadPreservesAllChanges() throws Exception {
        Path file = directory.resolve("sessions.json");
        SessionStore writer = new SessionStore(file);
        assertTrue(writer.saveSessions(List.of(walk("extended", 60), walk("deleted", 60))));
        SessionStore reader = new SessionStore(file);
        duringNextDecode(reader, () -> {
            assertTrue(writer.deleteSession("deleted"));
            assertTrue(writer.saveSessions(List.of(walk("extended", 600), walk("new", 60))));
        });

        reader.getSessions();
        assertTrue(reader.saveSession(walk("local", 60)));

        SessionStore reopened = new SessionStore(file);
        assertNull(reopened.findSession("deleted"));
        assertEquals(600, reopened.findSession("extended").elapsedSeconds);
        assertNotNull(reopened.findSession("new"));
        assertNotNull(reopened.findSession("local"));
    }

    @Test
    void replacementDuringReloadIsStillVisibleToTheNextRefresh() throws Exception {
        Path file = directory.resolve("sessions.json");
        SessionStore writer = new SessionStore(file);
        assertTrue(writer.saveSession(walk("original", 60)));
        SessionStore reader = new SessionStore(file);
        reader.getSessions();
        assertTrue(writer.saveSession(walk("trigger", 60)));
        duringNextDecode(reader, () -> assertTrue(writer.saveSession(walk("concurrent", 60))));

        assertTrue(reader.reload());
        assertTrue(reader.reload(), "an old read must not be stamped with the replacement's metadata");
        assertNotNull(reader.findSession("concurrent"));
        assertFalse(reader.reload());
    }

    @Test
    void saveMergesEvenWhenAReplacementHasTheSameTimestampAndSize() throws Exception {
        Path file = directory.resolve("sessions.json");
        SessionStore writer = new SessionStore(file);
        assertTrue(writer.saveSession(walk("original", 60)));
        SessionStore reader = new SessionStore(file);
        reader.getSessions();
        var timestamp = Files.getLastModifiedTime(file);
        long size = Files.size(file);
        assertTrue(writer.saveSession(walk("original", 90)));
        assertEquals(size, Files.size(file));
        Files.setLastModifiedTime(file, timestamp);

        assertTrue(reader.saveSession(walk("local", 60)));
        assertEquals(90, new SessionStore(file).findSession("original").elapsedSeconds);
    }

    @Test
    void readsAnswerWhileABackgroundWriteIsBusyWithTheDisk() throws Exception {
        Path file = directory.resolve("sessions.json");
        assertTrue(new SessionStore(file).saveSession(walk("existing", 60)));
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (SessionStore store = new SessionStore(file, new ScheduledThreadPoolExecutor(1))) {
            store.getSessions();
            stallNext(store, true, writing, release);
            CompletableFuture<Boolean> written = store.saveSessionLater(walk("autosaved", 30));
            assertTrue(writing.await(5, TimeUnit.SECONDS), "the background write is reading the file");
            // The UI thread asks while the write sits in file I/O: it must not wait for the disk.
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                assertNotNull(store.findSession("autosaved"));
                assertEquals(2, store.getSessions().size());
            });
            release.countDown();
            assertTrue(written.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void aSaveStagedWhileAWriteRunsStaysPendingForTheNextOne() throws Exception {
        Path file = directory.resolve("sessions.json");
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (SessionStore store = new SessionStore(file, new ScheduledThreadPoolExecutor(1))) {
            store.getSessions();
            stallNext(store, false, writing, release);
            CompletableFuture<Boolean> first = store.saveSessionLater(walk("first", 60));
            assertTrue(writing.await(5, TimeUnit.SECONDS), "the first write took its snapshot and is encoding it");
            CompletableFuture<Boolean> second = assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> store.saveSessionLater(walk("second", 60)));
            release.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertTrue(second.get(5, TimeUnit.SECONDS), "a follow-up write carries the later save");
            SessionStore onDisk = new SessionStore(file);
            assertNotNull(onDisk.findSession("first"));
            assertNotNull(onDisk.findSession("second"), "the first write must not count the later save as written");
        }
    }

    /** Stalls the store's next decode (a read of the file) or encode (a write of it) until released. */
    private static void stallNext(SessionStore store, boolean decode, CountDownLatch entered, CountDownLatch release)
            throws Exception {
        AtomicBoolean pending = new AtomicBoolean(true);
        Gson plain = new Gson();
        Runnable stall = () -> {
            if (pending.getAndSet(false)) {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        GsonBuilder builder = new GsonBuilder();
        if (decode) {
            builder.registerTypeAdapter(SessionData.class, (JsonDeserializer<SessionData>) (json, type, context) -> {
                stall.run();
                return plain.fromJson(json, SessionData.class);
            });
        } else {
            builder.registerTypeAdapter(SessionData.class, (JsonSerializer<SessionData>) (source, type, context) -> {
                stall.run();
                return plain.toJsonTree(source);
            });
        }
        Field gson = SessionStore.class.getDeclaredField("gson");
        gson.setAccessible(true);
        gson.set(store, builder.create());
    }

    /** Interleave another store's real writes after bytes were read, before decoding finishes. */
    private static void duringNextDecode(SessionStore store, Runnable concurrentWrite) throws Exception {
        AtomicBoolean pending = new AtomicBoolean(true);
        Gson delegate = new Gson();
        Gson gated = new GsonBuilder().registerTypeAdapter(SessionData.class,
                (JsonDeserializer<SessionData>) (json, type, context) -> {
                    if (pending.getAndSet(false)) {
                        concurrentWrite.run();
                    }
                    return delegate.fromJson(json, SessionData.class);
                }).create();
        Field gson = SessionStore.class.getDeclaredField("gson");
        gson.setAccessible(true);
        gson.set(store, gated);
    }
}
