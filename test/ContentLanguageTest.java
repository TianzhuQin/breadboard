import models.*;
import org.apache.commons.io.FileUtils;
import org.junit.Test;

import java.io.File;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * The texts scripts show ({@code c.get}, {@code c.getTranslated}, ContentFetcher) in an experiment
 * translated text by text, as the network game example is by a researcher whose language isn't
 * English: a text with no version in the language the steps chose, or an empty one, is shown in
 * English, or else in the language it was written in first. It used to stop the step with a
 * NullPointerException (and gave "" in file mode).
 */
public class ContentLanguageTest extends BaseTest {

    private static Content text(String name, Object... languagesAndHtml) {
        Content content = new Content();
        content.name = name;
        for (int i = 0; i + 1 < languagesAndHtml.length; i += 2) {
            Translation t = new Translation();
            t.setLanguage((Language) languagesAndHtml[i]);
            t.setHtml((String) languagesAndHtml[i + 1]);
            content.translations.add(t);
        }
        return content;
    }

    @Test
    public void aTextNotTranslatedYetIsShownInEnglishOrElseInItsFirstLanguage() {
        Language english = createLanguage("eng", "English");
        Language chinese = createLanguage("zho", "Chinese");
        Language spanish = createLanguage("spa", "Spanish");
        Experiment experiment = createExperiment("Translated in part");
        experiment.content.add(text("Prompt", english, "<p>Pick a color.</p>", chinese, "<p>选一个颜色。</p>"));
        experiment.content.add(text("Thanks", english, "<p>Thank you!</p>"));
        experiment.content.add(text("Heading", english, "<h2>Round {0}</h2>", chinese, "  "));
        experiment.content.add(text("Hola", spanish, "<p>¡Hola!</p>"));
        experiment.save();

        ContentFetcher c = Experiment.findById(experiment.id).contentFetcher;
        c.setDefaultLanguage("zho");
        assertEquals("<p>选一个颜色。</p>", c.get("Prompt"));
        assertEquals("not translated yet", "<p>Thank you!</p>", c.get("Thanks"));
        assertEquals("an empty version", "<h2>Round 2</h2>", c.get("Heading", 2));
        assertEquals("no English one either", "<p>¡Hola!</p>", c.get("Hola"));
        assertEquals("", c.get("No such text"));
        assertEquals("<p>Thank you!</p>", c.getTranslated("Thanks", "zho"));
        assertEquals("<p>Pick a color.</p>", c.getTranslated("Prompt", "eng"));
        assertEquals("a language the server doesn't have", "<p>Pick a color.</p>", c.getTranslated("Prompt", "zh"));

        // chosen by name too
        c.setDefaultLanguage("Chinese");
        assertEquals("<p>选一个颜色。</p>", c.get("Prompt"));
        // none chosen: the first version, as before
        c.setDefaultLanguage((String) null);
        assertEquals("<p>Pick a color.</p>", c.get("Prompt"));
    }

    @Test
    public void inFileModeToo() throws Exception {
        createLanguage("eng", "English");
        createLanguage("zho", "Chinese");
        Experiment experiment = new Experiment();
        experiment.name = "Files in part";
        experiment.uid = UUID.randomUUID().toString();
        experiment.fileMode = true;
        experiment.save();
        File dev = new File(play.Play.application().path(), "dev");
        boolean devExisted = dev.exists();
        File folder = new File(dev, experiment.getDirectoryName());
        try {
            FileUtils.writeStringToFile(new File(folder, "Content/eng/Prompt.html"), "<p>Pick a color.</p>", "UTF-8");
            FileUtils.writeStringToFile(new File(folder, "Content/zho/Prompt.html"), "<p>选一个颜色。</p>", "UTF-8");
            FileUtils.writeStringToFile(new File(folder, "Content/eng/Thanks.html"), "<p>Thank you!</p>", "UTF-8");

            ContentFetcher c = Experiment.findById(experiment.id).contentFetcher;
            c.setDefaultLanguage("zho");
            assertEquals("<p>选一个颜色。</p>", c.get("Prompt"));
            assertEquals("<p>Thank you!</p>", c.get("Thanks"));
        } finally {
            FileUtils.deleteQuietly(devExisted ? folder : dev);
        }
    }
}
