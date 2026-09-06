package com.codex.desktreadmill.settings;

import com.codex.desktreadmill.model.DailyActivity;
import com.codex.desktreadmill.model.SessionData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreRetryTest {
    @TempDir Path directory;

    private SessionData walk(String id) {
        SessionData session = new SessionData();
        session.id = id;
        session.name = id;
        return session;
    }

    @Test
    void heldLockPreventsWritesAndRetainsPendingChanges() throws Exception {
        Path file = directory.resolve("sessions.json");
        SessionStore store = new SessionStore(file);
        assertTrue(store.saveSession(walk("existing")));
        String original = Files.readString(file);
        try (var channel = FileChannel.open(directory.resolve("sessions.json.lock"), StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(store.saveSession(walk("pending")));
                assertFalse(store.deleteSession("existing"));
            });
            assertEquals(original, Files.readString(file));
            assertNotNull(store.findSession("pending"));
            assertNull(store.findSession("existing"));
        }
        assertTrue(store.flushPendingWrites());
        SessionStore reopened = new SessionStore(file);
        assertNull(reopened.findSession("existing"));
        assertNotNull(reopened.findSession("pending"));
    }

    @Test
    void lockOpenFailureDoesNotWriteUnlocked() throws Exception {
        Path file = directory.resolve("sessions.json");
        Path lockPath = directory.resolve("sessions.json.lock");
        Files.createDirectory(lockPath);
        SessionStore store = new SessionStore(file);
        assertFalse(store.saveSession(walk("pending")));
        assertFalse(Files.exists(file));
        Files.delete(lockPath);
        assertTrue(store.flushPendingWrites());
        assertNotNull(new SessionStore(file).findSession("pending"));
    }

    @Test
    void interruptedWriterKeepsChangesPending() {
        SessionStore store = new SessionStore(directory.resolve("sessions.json"));
        Thread.currentThread().interrupt();
        try {
            assertFalse(store.saveSession(walk("pending")));
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(Files.exists(directory.resolve("sessions.json")));
        } finally {
            Thread.interrupted();
        }
        assertTrue(store.flushPendingWrites());
    }

    @Test
    void backgroundRetryMergesLatestPendingChangesWithTheLockOwnersWrite() throws Exception {
        Path file = directory.resolve("sessions.json");
        CountDownLatch retried = new CountDownLatch(1);
        AtomicBoolean retriedOnEdt = new AtomicBoolean(true);
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1) {
            @Override
            protected void afterExecute(Runnable runnable, Throwable error) {
                SessionStore onDisk = new SessionStore(file);
                SessionData pending = onDisk.findSession("pending");
                if (pending != null && "latest edit".equals(pending.name)
                        && onDisk.findSession("deleted") == null && onDisk.findSession("other-ide") != null) {
                    retriedOnEdt.set(SwingUtilities.isEventDispatchThread());
                    retried.countDown();
                }
            }
        };
        try (SessionStore store = new SessionStore(file, executor)) {
            store.saveSession(walk("deleted"));
            try (var channel = FileChannel.open(directory.resolve("sessions.json.lock"), StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                assertFalse(store.saveSession(walk("pending")));
                SessionData edited = walk("pending");
                edited.name = "latest edit";
                assertFalse(store.saveSession(edited));
                assertFalse(store.deleteSession("deleted"));
                // Simulate the other lock owner committing before it releases.
                Files.writeString(file, "[{\"id\":\"deleted\"},{\"id\":\"other-ide\"}]");
            }
            assertTrue(retried.await(5, TimeUnit.SECONDS), "a failed write must retry without another UI action");
            assertFalse(retriedOnEdt.get());
            SessionStore reopened = new SessionStore(file);
            assertEquals("latest edit", reopened.findSession("pending").name);
            assertNull(reopened.findSession("deleted"));
            assertNotNull(reopened.findSession("other-ide"));
        }
        assertTrue(executor.isShutdown());
    }

    @Test
    void closingTheStoreCancelsRetries() throws Exception {
        Path file = directory.resolve("sessions.json");
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        SessionStore store = new SessionStore(file, executor);
        try (var channel = FileChannel.open(directory.resolve("sessions.json.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertFalse(store.saveSession(walk("pending")));
            store.close();
            assertTrue(executor.isShutdown());
            assertFalse(Files.exists(file));
        } finally {
            store.close();
        }
    }

    @Test
    void aSeparateProcessHoldingTheLockPreventsAnyWrite() throws Exception {
        Path file = directory.resolve("sessions.json");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // The IDE test classloader has no CodeSource URL. Copy the helper's
        // class resource into an isolated classpath for the plain child JVM.
        Path classes = directory.resolve("child-classes");
        String classResource = LockHolder.class.getName().replace('.', '/') + ".class";
        Path classFile = classes.resolve(classResource);
        Files.createDirectories(classFile.getParent());
        try (var bytecode = LockHolder.class.getResourceAsStream("/" + classResource)) {
            assertNotNull(bytecode);
            Files.copy(bytecode, classFile);
        }
        Process holder = new ProcessBuilder(javaExecutable, "-cp", classes.toString(), LockHolder.class.getName(),
                directory.resolve("sessions.json.lock").toString()).redirectErrorStream(true).start();
        try {
            var ready = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return holder.inputReader().readLine();
                } catch (java.io.IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            });
            assertEquals("locked", ready.get(5, TimeUnit.SECONDS));
            SessionStore store = new SessionStore(file);
            assertFalse(store.saveSession(walk("pending")));
            assertFalse(Files.exists(file));
            holder.getOutputStream().write(1);
            holder.getOutputStream().flush();
            assertTrue(holder.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, holder.exitValue());
            assertTrue(store.flushPendingWrites());
            assertNotNull(new SessionStore(file).findSession("pending"));
        } finally {
            holder.destroyForcibly();
            holder.waitFor(5, TimeUnit.SECONDS);
        }
    }

    /** Small child JVM with no dependency on the IDE or JUnit runtime. */
    public static class LockHolder {
        public static void main(String[] args) throws Exception {
            try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                System.out.println("locked");
                System.out.flush();
                System.in.read();
            }
        }
    }

    @Test
    void dailyActivityAndFractionsSurviveJsonStorageAndCopiesAreIndependent() {
        Path file = directory.resolve("sessions.json");
        SessionStore store = new SessionStore(file);
        SessionData session = walk("dated");
        session.timerRemainderMillis = 750;
        session.activityDays.add(new DailyActivity(1_700_000_000_000L, 600, 0.5, 700, 30));
        assertTrue(store.saveSession(session));
        session.activityDays.getFirst().distanceKm = 999;
        SessionData read = new SessionStore(file).findSession("dated");
        assertEquals(750, read.timerRemainderMillis);
        assertEquals(0.5, read.activityDays.getFirst().distanceKm);
        SessionData copy = read.copy();
        copy.activityDays.getFirst().steps = 0;
        assertEquals(700, read.activityDays.getFirst().steps);
    }

    @Test
    void nullAndNonFiniteDailyFieldsAreSanitizedOnRead() throws Exception {
        Path file = directory.resolve("sessions.json");
        Files.writeString(file, "[{\"id\":\"null-days\",\"activityDays\":null},"
                + "{\"id\":\"bad-day\",\"timerRemainderMillis\":10000,\"activityDays\":[null,"
                + "{\"distanceKm\":NaN,\"calories\":Infinity}]}]");
        SessionStore store = new SessionStore(file);
        assertTrue(store.findSession("null-days").activityDays.isEmpty());
        SessionData read = store.findSession("bad-day");
        assertEquals(999, read.timerRemainderMillis);
        assertEquals(1, read.activityDays.size());
        assertEquals(0, read.activityDays.getFirst().distanceKm);
        assertEquals(0, read.activityDays.getFirst().calories);
        assertTrue(store.saveSession(read));
    }
}
