package org.xjcraft.chunkdownloader.rollback;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RollbackFilesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private RollbackFiles files() throws IOException {
        return new RollbackFiles(tmp.newFolder("plugins").getAbsoluteFile());
    }

    @Test
    public void tmpAndBackupDirsLiveUnderPluginDataFolder() throws Exception {
        RollbackFiles files = files();
        assertEquals(new File(files.tmpDir().getParentFile(), "rollback-tmp"),
                files.tmpDir());
        assertTrue(files.backupDir("MainLand").getPath()
                .endsWith("rollback-backups" + File.separator + "MainLand"));
    }

    @Test
    public void newTmpFileIsInsideTmpDir() throws Exception {
        RollbackFiles files = files();
        File f = files.newTmpFile("r.0.0.mca");
        assertEquals(files.tmpDir().getAbsoluteFile(), f.getParentFile().getAbsoluteFile());
        assertTrue(f.getName().endsWith("r.0.0.mca.tmp"));
    }

    @Test
    public void backupCopyKeepsOriginalContent() throws Exception {
        RollbackFiles files = files();
        File region = tmp.newFile("r.0.0.mca");
        byte[] content = "original region bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(region.toPath(), content);

        File backup = files.backupCopy(region, "MainLand");

        assertTrue(backup.isFile());
        assertEquals(region.getName(),
                backup.getName().substring(0, region.getName().length()));
        assertArrayEquals(content, Files.readAllBytes(backup.toPath()));
        // 原件必须原样保留
        assertArrayEquals(content, Files.readAllBytes(region.toPath()));
    }

    @Test
    public void overwriteInPlaceReplacesAndTruncates() throws Exception {
        RollbackFiles files = files();
        File region = tmp.newFile("r.0.0.mca");
        Files.write(region.toPath(), new byte[4096]);
        File source = tmp.newFile("source.mca");
        byte[] replacement = "short".getBytes(StandardCharsets.UTF_8);
        Files.write(source.toPath(), replacement);

        files.overwriteInPlace(source, region);

        assertArrayEquals(replacement, Files.readAllBytes(region.toPath()));
        assertEquals(replacement.length, region.length());
    }

    @Test
    public void overwriteInPlaceKeepsTheSameFileObject() throws Exception {
        RollbackFiles files = files();
        File region = tmp.newFile("r.0.0.mca");
        Files.write(region.toPath(), new byte[16]);
        File source = tmp.newFile("source.mca");
        Files.write(source.toPath(), new byte[64]);

        Object before = region;
        files.overwriteInPlace(source, region);

        // 同一个 File 指向同一个路径；inode 保留由 FileChannel 原地写入保证
        assertEquals(before, region);
        assertTrue(region.isFile());
        assertEquals(64, region.length());
    }

    @Test
    public void pendingMarkerLifecycle() throws Exception {
        RollbackFiles files = files();
        File region = tmp.newFile("r.0.0.mca");

        assertFalse(files.hasPendingMarker(region));
        files.markPending(region);
        assertTrue(files.hasPendingMarker(region));
        // 标记放在 region 同级目录，不进插件目录
        File marker = new File(region.getParentFile(), region.getName() + ".rollback.pending");
        assertTrue(marker.isFile());
        assertEquals(region.getParentFile(), marker.getParentFile());

        files.clearPending(region);
        assertFalse(files.hasPendingMarker(region));
    }

    @Test
    public void purgeTmpDeletesDownloadedFiles() throws Exception {
        RollbackFiles files = files();
        File a = files.newTmpFile("a.mca");
        File b = files.newTmpFile("b.mca");
        assertTrue(a.createNewFile());
        assertTrue(b.createNewFile());

        files.purgeTmp();

        assertFalse(a.exists());
        assertFalse(b.exists());
        assertTrue(Arrays.asList(files.tmpDir().listFiles()).isEmpty());
    }

    @Test
    public void purgeTmpIsSafeWhenDirectoryIsEmpty() throws Exception {
        files().purgeTmp();
    }
}
