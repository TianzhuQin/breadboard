package controllers;

import net.lingala.zip4j.model.FileHeader;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Files read or written on behalf of a request stay where they belong: a file-mode experiment's
 * image is read from its Images folder only (the route is public, and Play decodes %2F in the name),
 * and an imported zip may not unpack outside its temporary folder or contain links.
 */
public class FileSafetyTest {

    private File base;
    private File images;

    @Before
    public void makeFolders() throws Exception {
        base = Files.createTempDirectory("bb-files").toFile();
        images = new File(base, "Images");
        assertTrue(images.mkdirs());
        FileUtils.writeStringToFile(new File(images, "ok.png"), "png");
        FileUtils.writeStringToFile(new File(base, "secret.txt"), "secret");
        assertTrue(new File(images, "sub").mkdirs());
    }

    @After
    public void removeFolders() {
        FileUtils.deleteQuietly(base);
    }

    // ---- file-mode images ------------------------------------------------------------------------

    @Test
    public void anImageInTheFolderIsFound() throws Exception {
        File found = ImagesController.imageFileIn(images, "ok.png");
        assertNotNull(found);
        assertEquals("png", FileUtils.readFileToString(found));
    }

    @Test
    public void namesThatLeaveTheFolderOrAreNoFileFindNothing() {
        for (String name : Arrays.asList("../secret.txt", "..", ".", "", "sub", "missing.png", "sub/../ok.png",
            "..\\secret.txt", "ok.png\0.txt", "/etc/passwd", base.getAbsolutePath() + "/secret.txt")) {
            assertNull(name, ImagesController.imageFileIn(images, name));
        }
        assertNull(ImagesController.imageFileIn(images, null));
        assertNull("no folder", ImagesController.imageFileIn(new File(base, "NoImages"), "ok.png"));
    }

    @Test
    public void aLinkOutOfTheFolderIsNotFollowed() throws Exception {
        Files.createSymbolicLink(new File(images, "link.png").toPath(), new File(base, "secret.txt").toPath());
        assertNull(ImagesController.imageFileIn(images, "link.png"));
    }

    // ---- zip import ------------------------------------------------------------------------------

    private static FileHeader entry(String name, int unixMode) {
        FileHeader header = new FileHeader();
        header.setFileName(name);
        // Unix mode in the upper two bytes, little-endian, as zip tools write it.
        header.setExternalFileAttr(new byte[]{0, 0, (byte) (unixMode & 0xff), (byte) ((unixMode >> 8) & 0xff)});
        return header;
    }

    @Test
    public void anOrdinaryExportIsSafeToUnpack() throws Exception {
        List<FileHeader> headers = new ArrayList<>();
        headers.add(entry("Steps/", 040755));
        headers.add(entry("Steps/InitStep.groovy", 0100644));
        headers.add(entry("My experiment/Content/intro.html", 0100644));
        headers.add(entry("weird..name.txt", 0100644));
        assertNull(ExperimentController.unsafeEntry(headers, base));
    }

    @Test
    public void entriesThatLeaveTheFolderAreNamed() throws Exception {
        for (String name : Arrays.asList("../evil.groovy", "Steps/../../evil.groovy", "/etc/cron.d/evil", "\\evil", "C:\\evil")) {
            List<FileHeader> headers = new ArrayList<>();
            headers.add(entry("Steps/InitStep.groovy", 0100644));
            headers.add(entry(name, 0100644));
            assertEquals(name, ExperimentController.unsafeEntry(headers, base));
        }
    }

    @Test
    public void linksAreRefused() throws Exception {
        List<FileHeader> headers = new ArrayList<>();
        headers.add(entry("Steps/InitStep.groovy", 0120777));
        assertEquals("Steps/InitStep.groovy", ExperimentController.unsafeEntry(headers, base));
    }

    @Test
    public void theFolderItselfMayBeNamed() throws Exception {
        assertNull(ExperimentController.unsafeEntry(Arrays.asList(entry("./", 040755)), base));
    }
}
