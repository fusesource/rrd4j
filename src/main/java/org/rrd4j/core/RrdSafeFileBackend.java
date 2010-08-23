package org.rrd4j.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Backend which is used to store RRD data to ordinary files on the disk, using locking. This backend
 * is SAFE: it locks the underlying RRD file during update/fetch operations, and caches only static
 * parts of a RRD file in memory. Therefore, this backend is safe to be used when RRD files should
 * be shared between several JVMs at the same time. However, this backend is a little bit slow
 * since it does not use fast java.nio.* package (it's still based on the RandomAccessFile class).
 */
public class RrdSafeFileBackend extends RrdFileBackend {
    private static final Counters counters = new Counters();

    private FileLock lock;

    /**
     * Creates RrdFileBackend object for the given file path, backed by RandomAccessFile object.
     *
     * @param path Path to a file
     * @throws IOException Thrown in case of I/O error
     */
    public RrdSafeFileBackend(String path, long lockWaitTime, long lockRetryPeriod)
            throws IOException {
        super(path, false);
        try {
            lockFile(lockWaitTime, lockRetryPeriod);
        }
        catch (IOException ioe) {
            super.close();
            throw ioe;
        }
    }

    private void lockFile(long lockWaitTime, long lockRetryPeriod) throws IOException {
        long entryTime = System.currentTimeMillis();
        FileChannel channel = file.getChannel();
        lock = channel.tryLock(0, Long.MAX_VALUE, false);
        if (lock != null) {
            counters.registerQuickLock();
            return;
        }
        do {
            try {
                Thread.sleep(lockRetryPeriod);
            }
            catch (InterruptedException e) {
                // NOP
            }
            lock = channel.tryLock(0, Long.MAX_VALUE, false);
            if (lock != null) {
                counters.registerDelayedLock();
                return;
            }
        }
        while (System.currentTimeMillis() - entryTime <= lockWaitTime);
        counters.registerError();
        throw new IOException("Could not obtain exclusive lock on file: " + getPath() +
                "] after " + lockWaitTime + " milliseconds");
    }

    public void close() throws IOException {
        try {
            if (lock != null) {
                lock.release();
                lock = null;
                counters.registerUnlock();
            }
        }
        finally {
            super.close();
        }
    }

    /**
     * Defines the caching policy for this backend.
     *
     * @return <code>false</code>
     */
    protected boolean isCachingAllowed() {
        return false;
    }

    public static String getLockInfo() {
        return counters.getInfo();
    }

    static class Counters {
        final AtomicLong locks = new AtomicLong(0);
        final AtomicLong quickLocks = new AtomicLong(0);
        final AtomicLong unlocks = new AtomicLong(0);
        final AtomicLong locked = new AtomicLong(0);
        final AtomicLong errors = new AtomicLong(0);

        void registerQuickLock() {
            locks.getAndIncrement();
            quickLocks.getAndIncrement();
            locked.getAndIncrement();
        }

        void registerDelayedLock() {
            locks.getAndIncrement();
            locked.getAndIncrement();
        }

        void registerUnlock() {
            unlocks.getAndIncrement();
            locked.getAndDecrement();
        }

        void registerError() {
            errors.getAndIncrement();
        }

        String getInfo() {
            return "LOCKS=" + locks + ", " + "UNLOCKS=" + unlocks + ", " +
                    "DELAYED_LOCKS=" + (locks.get() - quickLocks.get()) + ", " + "LOCKED=" + locked + ", " +
                    "ERRORS=" + errors;
        }
    }
}
