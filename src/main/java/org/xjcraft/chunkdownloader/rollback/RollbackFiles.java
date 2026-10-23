package org.xjcraft.chunkdownloader.rollback;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Plugin directory layout and region-file replacement operations.
 *
 * plugins/ChunkDownloader/
 *   rollback-tmp/                 download work-in-progress files
 *   rollback-backups/<world>/     pre-rollback copies of originals
 */
public class RollbackFiles {

    private final File dataFolder;

    public RollbackFiles(File dataFolder) {
        this.dataFolder = dataFolder;
    }

    public File tmpDir() {
        return ensureDir(new File(dataFolder, "rollback-tmp"));
    }

    public File backupDir(String world) {
        return ensureDir(new File(new File(dataFolder, "rollback-backups"), world));
    }

    public File newTmpFile(String suffix) {
        return new File(tmpDir(), ".rollback-" + System.nanoTime() + "-" + suffix + ".tmp");
    }

    /** Copy regionFile to rollback-backups/<world>/<name>.<timestamp>. Returns the backup file. */
    public File backupCopy(File regionFile, String world) throws IOException {
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File target = new File(backupDir(world), regionFile.getName() + "." + ts);
        copy(regionFile, target);
        return target;
    }

    /**
     * Overwrite regionFile in-place from source (preserves inode so server's
     * RegionFileCache file-handles remain valid).
     * Strategy: truncate, write, fsync.
     */
    public void overwriteInPlace(File source, File regionFile) throws IOException {
        FileChannel ch = FileChannel.open(regionFile.toPath(),
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            InputStream in = new FileInputStream(source);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf);
                while ((n = in.read(buf)) > 0) {
                    bb.clear().limit(n);
                    while (bb.hasRemaining()) {
                        ch.write(bb);
                    }
                }
            } finally {
                in.close();
            }
            ch.force(true);
        } finally {
            ch.close();
        }
    }

    public void markPending(File regionFile) throws IOException {
        File marker = pendingMarker(regionFile);
        marker.createNewFile();
    }

    public void clearPending(File regionFile) {
        pendingMarker(regionFile).delete();
    }

    public boolean hasPendingMarker(File regionFile) {
        return pendingMarker(regionFile).exists();
    }

    /** Delete all files in rollback-tmp/ on startup to clean up interrupted downloads. */
    public void purgeTmp() {
        File[] files = tmpDir().listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            f.delete();
        }
    }

    private File pendingMarker(File regionFile) {
        return new File(regionFile.getParentFile(),
                regionFile.getName() + ".rollback.pending");
    }

    private static File ensureDir(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建目录: " + dir);
        }
        return dir;
    }

    private static void copy(File from, File to) throws IOException {
        InputStream in = new FileInputStream(from);
        try {
            OutputStream out = new FileOutputStream(to);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }
}
