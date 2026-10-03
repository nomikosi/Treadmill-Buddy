package com.codex.desktreadmill.storage;

import com.codex.desktreadmill.model.SessionData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void olderReloadCannotUndoABackgroundMerge(boolean finishWriteFirst) throws Exception {
        Path file = directory.resolve("sessions.json");
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        try (ExecutorService reloadExecutor = Executors.newSingleThreadExecutor();
             SessionStore otherIde = new SessionStore(file);
             SessionStore store = new SessionStore(file, new ScheduledThreadPoolExecutor(1))) {
            try {
                assertTrue(otherIde.saveSessions(List.of(walk("extended", 60), walk("removed", 60))));
                store.getSessions();
                assertTrue(otherIde.saveSessions(List.of(walk("extended", 90), walk("trigger", 60))));
                stallNextReadAndWrite(store, reading, releaseRead, writing, releaseWrite);
                CompletableFuture<Boolean> reloaded = CompletableFuture.supplyAsync(store::reload, reloadExecutor);
                assertTrue(reading.await(5, TimeUnit.SECONDS), "reload captured the older file");

                assertTrue(otherIde.deleteSession("removed"));
                assertTrue(otherIde.saveSessions(List.of(walk("extended", 600), walk("new", 60))));
                CompletableFuture<Boolean> written = store.saveSessionLater(walk("local", 60));
                assertTrue(writing.await(5, TimeUnit.SECONDS), "the writer merged the newer file");
                assertEquals(600, store.findSession("extended").elapsedSeconds);
                if (finishWriteFirst) {
                    releaseWrite.countDown();
                    assertTrue(written.get(5, TimeUnit.SECONDS));
                }

                releaseRead.countDown();
                assertTrue(reloaded.get(5, TimeUnit.SECONDS), "reload must not wait for the writer");
                assertEquals(600, store.findSession("extended").elapsedSeconds);
                assertNotNull(store.findSession("new"));
                assertNull(store.findSession("removed"));
                assertNotNull(store.findSession("local"));
                releaseWrite.countDown();
                assertTrue(written.get(5, TimeUnit.SECONDS));
                assertFalse(store.reload());

                // Resuming the cached workout must continue from the newest saved progress.
                SessionData resumed = store.findSession("extended");
                resumed.elapsedSeconds++;
                assertTrue(store.saveSession(resumed));
                try (SessionStore reopened = new SessionStore(file)) {
                    assertEquals(601, reopened.findSession("extended").elapsedSeconds);
                    assertNotNull(reopened.findSession("new"));
                    assertNull(reopened.findSession("removed"));
                    assertNotNull(reopened.findSession("local"));
                }
            } finally {
                releaseRead.countDown();
                releaseWrite.countDown();
            }
        }
    }

    @Test
    void olderReloadCannotUndoANewerReload() throws Exception {
        Path file = directory.resolve("sessions.json");
        try (SessionStore writer = new SessionStore(file); SessionStore reader = new SessionStore(file)) {
            assertTrue(writer.saveSession(walk("extended", 60)));
            reader.getSessions();
            assertTrue(writer.saveSessions(List.of(walk("extended", 90), walk("trigger", 60))));
            duringNextDecode(reader, () -> {
                assertTrue(writer.saveSessions(List.of(walk("extended", 600), walk("new", 60))));
                assertTrue(reader.reload());
            });

            assertTrue(reader.reload());
            assertEquals(600, reader.findSession("extended").elapsedSeconds);
            assertNotNull(reader.findSession("new"));
            assertFalse(reader.reload());
        }
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

    /** Holds an older reload while a background writer reads and snapshots a newer file. */
    private static void stallNextReadAndWrite(SessionStore store, CountDownLatch reading,
                                             CountDownLatch releaseRead, CountDownLatch writing,
                                             CountDownLatch releaseWrite) throws Exception {
        AtomicBoolean firstRead = new AtomicBoolean(true);
        AtomicBoolean firstWrite = new AtomicBoolean(true);
        TypeAdapter<SessionData> delegate = new Gson().getAdapter(SessionData.class);
        TypeAdapter<SessionData> adapter = new TypeAdapter<>() {
            @Override
            public SessionData read(JsonReader in) throws IOException {
                stallOnce(firstRead, reading, releaseRead);
                return delegate.read(in);
            }

            @Override
            public void write(JsonWriter out, SessionData value) throws IOException {
                stallOnce(firstWrite, writing, releaseWrite);
                delegate.write(out, value);
            }
        };
        Field gson = SessionStore.class.getDeclaredField("gson");
        gson.setAccessible(true);
        gson.set(store, new GsonBuilder().registerTypeAdapter(SessionData.class, adapter).create());
    }

    private static void stallOnce(AtomicBoolean pending, CountDownLatch entered, CountDownLatch release) {
        if (pending.getAndSet(false)) {
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS), "the stalled operation was not released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
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
