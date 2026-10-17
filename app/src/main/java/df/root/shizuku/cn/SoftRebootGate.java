package df.root.shizuku.cn;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/** One manual run's reboot decision, shared with bootstrap through an eight-byte file. */
final class SoftRebootGate implements Closeable {
    static final String FILE_NAME = "soft_reboot_gate";
    static final int WAIT = 0, ALLOW = 1, CANCEL = 2;
    static final int PENDING = 0, READY = 1, REQUESTED = 2, FAILED = 3, TIMED_OUT = 4;
    private static final int AUTH_OFFSET = 4, RESULT_OFFSET = 5;
    private final RandomAccessFile file;
    private boolean closed;

    SoftRebootGate(File dataDirectory) throws IOException {
        File pending = File.createTempFile("soft-reboot-", ".tmp", dataDirectory);
        try {
            try (RandomAccessFile opened = new RandomAccessFile(pending, "rw")) {
                opened.write(new byte[]{'D', 'F', 'R', '1', WAIT, PENDING, 0, 0});
                opened.getFD().sync();
            }
            // Never truncate a previous run's inode while bootstrap may still map it.
            Files.move(pending.toPath(), new File(dataDirectory, FILE_NAME).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            file = new RandomAccessFile(new File(dataDirectory, FILE_NAME), "rw");
        } catch (IOException e) {
            Files.deleteIfExists(pending.toPath());
            throw e;
        }
    }

    static void clearForBoot(File dataDirectory) throws IOException {
        Files.deleteIfExists(new File(dataDirectory, FILE_NAME).toPath());
    }

    boolean authorize(boolean requested, int rootCode, boolean restored, boolean recoveryPending)
            throws IOException {
        if (closed) throw new IOException("reboot gate is closed");
        boolean allowed = requested && rootCode == 0 && restored && !recoveryPending;
        file.seek(AUTH_OFFSET);
        file.writeByte(allowed ? ALLOW : CANCEL);
        return allowed;
    }

    int awaitResult(long timeoutMillis) throws IOException, InterruptedException {
        if (closed) throw new IOException("reboot gate is closed");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        do {
            file.seek(RESULT_OFFSET);
            int result = file.readUnsignedByte();
            if (result == REQUESTED || result == FAILED || result == TIMED_OUT) return result;
            if (result != PENDING && result != READY) throw new IOException("invalid reboot status");
            if (System.nanoTime() >= deadline) break;
            Thread.sleep(50);
        } while (true);
        return TIMED_OUT;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        try {
            file.seek(AUTH_OFFSET);
            file.writeByte(CANCEL);
        } finally {
            closed = true;
            file.close();
        }
        // Keep the cancelled record for a delayed bootstrap. The next run replaces
        // it atomically, or the independent boot path explicitly removes it.
    }
}
