import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import models.SystemTexts;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * Breadboard's own words to participants (models.SystemTexts, conf/i18n): in English, or in Chinese for
 * a session whose texts the steps wrote in Chinese, with their parameters filled in and English's plural
 * forms. The participant page reads the same files (frontend/client, lib/texts.ts).
 */
public class SystemTextsTest {

    @Test
    public void chineseForASessionInChineseAndEnglishForAnyOther() {
        assertEquals("zho", SystemTexts.languageOf("zho"));
        assertEquals("zho", SystemTexts.languageOf(" ZHO "));
        assertEquals("zho", SystemTexts.languageOf("zh-CN"));
        assertEquals("zho", SystemTexts.languageOf("chi"));
        assertEquals("eng", SystemTexts.languageOf("eng"));
        assertEquals("eng", SystemTexts.languageOf("spa"));
        assertEquals("eng", SystemTexts.languageOf(""));
        assertEquals("eng", SystemTexts.languageOf(null));
        assertEquals("zh-CN", SystemTexts.htmlLang("zho"));
        assertEquals("en", SystemTexts.htmlLang("spa"));

        assertEquals("Join the session", SystemTexts.text(null, "signIn.title"));
        assertEquals("Join the session", SystemTexts.text("spa", "signIn.title"));
        assertEquals("加入实验", SystemTexts.text("zho", "signIn.title"));
    }

    @Test
    public void parametersAreFilledInAndEnglishCountsOnePersonAndTwoPeople() {
        assertEquals("1 person is here.", SystemTexts.text("eng", "waiting.here", "n", 1));
        assertEquals("2 people are here.", SystemTexts.text("eng", "waiting.here", "n", 2));
        assertEquals("0 people are here.", SystemTexts.text("eng", "waiting.here", "n", 0));
        // (Groovy's numbers may come as doubles)
        assertEquals("1 person is here.", SystemTexts.text("eng", "waiting.here", "n", 1.0d));
        assertEquals("已有 1 人到达。", SystemTexts.text("zho", "waiting.here", "n", 1));
        java.util.Map<String, Object> params = new java.util.HashMap<String, Object>();
        params.put("n", 5);
        assertEquals("5 秒后开始…", SystemTexts.text("zho", "waiting.countdown", params));
        // a parameter not given is left as it is, and an unknown text is its name
        assertEquals("Continue as {id}", SystemTexts.text("eng", "seat.continue"));
        assertEquals("no.such.text", SystemTexts.text("zho", "no.such.text"));
    }

    @Test
    public void inHtmlTheParametersAreEscapedButNotTheMarkupGivenAsSuch() {
        assertEquals("&lt;b&gt;p1&lt;/b&gt; isn't in this session's game right now, so there is nothing to show. A preview adds "
            + "nobody to the game: participants join from their own page. Reload this preview once they have joined.",
            SystemTexts.html("eng", "end.notInGame", "id", "<b>p1</b>"));
        assertEquals("这一场结束后，下一场就会开始。<a href=\"/play/1/login\">加入下一场</a>：那个页面会等下一场开放，然后带你进入。",
            SystemTexts.html("zho", "end.fullNext", "link", new SystemTexts.Html("<a href=\"/play/1/login\">加入下一场</a>")));
    }

    @Test
    public void sentencesFollowOneAnotherAsTheLanguageWritesThem() {
        assertEquals("One. Two.", SystemTexts.sentences("eng", "One.", "Two."));
        assertEquals("One.", SystemTexts.sentences("eng", "One.", "", null));
        assertEquals("一。二。", SystemTexts.sentences("zho", "一。", "二。"));
    }

    @Test
    public void aPageShowsAParameterItsOwnWay() {
        SystemTexts.Page page = SystemTexts.page("zho");
        assertEquals("这个浏览器已经以 ", page.before("seat.lead", "id"));
        assertEquals(" 的身份加入了这场实验。", page.after("seat.lead", "id"));
        assertEquals("zh-CN", page.lang());
        assertEquals("This browser has a seat in the session as ", SystemTexts.page(null).before("seat.lead", "id"));
        assertEquals(".", SystemTexts.page(null).after("seat.lead", "id"));
    }

    @Test
    public void everyTextIsInBothLanguagesWithTheSameParameters() throws Exception {
        JsonNode eng = new ObjectMapper().readTree(new File("conf/i18n/eng.json"));
        JsonNode zho = new ObjectMapper().readTree(new File("conf/i18n/zho.json"));
        assertEquals(names(eng), names(zho));
        Pattern parameter = Pattern.compile("\\{\\w+\\}");
        for (String key : names(eng)) {
            if (key.startsWith("_")) {
                continue;
            }
            assertEquals(key, parameters(eng.get(key), parameter), parameters(zho.get(key), parameter));
            // and the server gives it in each
            assertNotEquals(key, key, SystemTexts.text("eng", key));
            assertNotEquals(key, key, SystemTexts.text("zho", key));
        }
    }

    private static List<String> names(JsonNode texts) {
        List<String> names = new ArrayList<String>();
        Iterator<String> it = texts.fieldNames();
        while (it.hasNext()) {
            names.add(it.next());
        }
        Collections.sort(names);
        return names;
    }

    private static TreeSet<String> parameters(JsonNode text, Pattern parameter) {
        TreeSet<String> found = new TreeSet<String>();
        List<String> forms = new ArrayList<String>();
        if (text.isTextual()) {
            forms.add(text.textValue());
        } else {
            Iterator<JsonNode> it = text.elements();
            while (it.hasNext()) {
                forms.add(it.next().textValue());
            }
        }
        for (String form : forms) {
            Matcher m = parameter.matcher(form);
            while (m.find()) {
                found.add(m.group());
            }
        }
        return found;
    }
}
