package models;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The end screens and notices the server sends a session's participants are in the language of the
 * session's texts (SessionLanguage): in Chinese for a session whose steps chose zho, as everything
 * Breadboard itself tells its participants (SystemTexts). They used to be English, at the end of a
 * study written in Chinese. In English they are word for word what they were.
 */
public class EndScreensLanguageTest {

    private static String text(ObjectNode screen) {
        return screen.get("player").get("text").asText();
    }

    @Test
    public void inChinese() {
        assertEquals("<h3>本场实验已被停止</h3><p>研究者停止了本场实验。如果研究者重新开始，请再打开一次你收到的链接。否则，感谢你的参与，"
            + "现在可以关闭这个页面了。</p>", text(ScriptBoard.stoppedScreen("zho")));
        assertEquals("<h3>本场实验已结束</h3><p>实验时间已经用完。感谢你的参与。实验在给你完成码之前就结束了，请在 Prolific 上给研究者发消息，"
            + "说明你的提交情况。</p>", text(ScriptBoard.outOfTimeScreen(true, "zho")));
        assertEquals("<h3>本场实验已结束</h3><p>实验时间已经用完。感谢你的参与。现在可以关闭这个页面；如果有任何问题，请联系研究者。</p>",
            text(ScriptBoard.outOfTimeScreen(false, "zho")));
        assertEquals("<h3>本场实验已结束</h3><p>因为研究出了问题，本场实验不得不停止。感谢你的参与。现在可以关闭这个页面；如果有任何问题，"
            + "请联系研究者。</p>", text(ScriptBoard.problemScreen(false, "zho")));
        assertTrue(text(ScriptBoard.problemScreen(true, "zho")).contains("你可以稍后从 Prolific 重新打开这项研究"));
        assertEquals("<h3>本场实验已结束</h3><p>研究的服务器停止或重启了，所以本场实验结束了。你可以过几分钟从 Prolific 重新打开这项研究，"
            + "加入那时开放的一场。如果没有开放的场次，请在 Prolific 上退回这项研究；如果有任何问题，请在 Prolific 上给研究者发消息。</p>",
            text(ScriptBoard.serverStoppedScreen(true, "zho")));
        assertTrue(text(ScriptBoard.serverStoppedScreen(false, "zho")).contains("如果你收到过这项研究的链接，可以过几分钟再打开它"));
        assertEquals("<h3>实验在你不在时开始了</h3><p>实验开始时你的连接断了，所以实验没有等你就开始了，现在也不能再加入。"
            + "请在 Prolific 上退回这项研究，然后就可以关闭这个页面了。</p>", text(ScriptBoard.missedStartScreen(true, "zho")));
        assertEquals("<h3>本场实验人数已满</h3><p>本场实验已经按人数上限开始了，没法再让你加入。现在可以关闭这个页面。如果有任何问题，"
            + "请联系研究者。</p>", text(ScriptBoard.sessionFullScreen(false, null, "zho")));
        assertTrue(text(ScriptBoard.sessionFullScreen(true, null, "zho")).contains("请在 Prolific 上退回这项研究"));
        assertEquals("<h3>不在游戏里</h3><p>&lt;p1&gt; 现在不在本场的游戏里，所以没有可以显示的内容。预览不会把人加进游戏：参与者要从自己的页面加入。"
            + "等他加入后，请重新加载这个预览。</p>", text(ScriptBoard.notInGameScreen("<p1>", "zho")));
        assertEquals("<h3>这场实验没有在进行</h3><p>它可能已经结束，或者还没有开始。如果你收到过这项研究的链接，请重新打开它；否则可以关闭这个页面。</p>",
            text(ScriptBoard.sessionNotRunningScreen("zho")));
        assertEquals("<h3>实验已经开始了</h3><p>它在你到达之前就开始了，不能再加入新的参与者。请联系研究者。</p>",
            text(ScriptBoard.sessionStartedScreen("zho")));
        // what marks them stays the same
        assertEquals("stopped", ScriptBoard.stoppedScreen("zho").get("player").get("ended").asText());
        assertEquals("sessionFull", ScriptBoard.sessionFullScreen(false, null, "zho").get("player").get("ended").asText());
    }

    @Test
    public void thePlainEndOfAChineseSessionIsInChinese() {
        SessionLanguage.record(9101L, "zho");
        try {
            assertEquals("<h3>本场实验已结束</h3><p>感谢你的参与。现在可以关闭这个页面了。</p>",
                text(ScriptBoard.endedSessionScreen(9101L, "nobody")));
        } finally {
            SessionLanguage.reset();
        }
    }

    @Test
    public void inEnglishWordForWordAsBefore() {
        assertEquals("<h3>This session has been stopped</h3><p>The researcher stopped this session. If they start it again, open "
            + "the link you were given once more. Otherwise, thank you for taking part: you can close this page.</p>",
            text(ScriptBoard.stoppedScreen()));
        assertEquals(text(ScriptBoard.stoppedScreen()), text(ScriptBoard.stoppedScreen("spa")));
        assertEquals("<h3>This session has ended</h3><p>It ran out of time. Thank you for taking part. It ended before the study "
            + "gave you a completion code, so please message the researcher on Prolific about your submission.</p>",
            text(ScriptBoard.outOfTimeScreen(true)));
        assertEquals("<h3>This session has ended</h3><p>It had to stop because of a problem with the study. Thank you for taking "
            + "part. You can close this page; if you have any questions, please contact the researcher.</p>",
            text(ScriptBoard.problemScreen(false, "eng")));
        assertEquals("<h3>This session has ended</h3><p>It ended because the study's server was stopped or restarted. If you were "
            + "given a link to the study, you can open it again in a few minutes to join the session that is open then; otherwise, "
            + "thank you for taking part, and please contact the researcher if you have any questions.</p>",
            text(ScriptBoard.serverStoppedScreen(false)));
        assertEquals("<h3>The session started without you</h3><p>The session started while your connection was lost, so it went "
            + "ahead without you, and it can't take you in any more. You can close this page. If you have any questions, please "
            + "contact the researcher.</p>", text(ScriptBoard.missedStartScreen(false)));
        assertEquals("<h3>This session is full</h3><p>It started with as many people as it can take, so it can't take you in. "
            + "Please return the study on Prolific, and message the researcher there if you have any questions.</p>",
            text(ScriptBoard.sessionFullScreen(true, null)));
        assertEquals("<h3>Not in the game</h3><p>&lt;p1&gt; isn't in this session's game right now, so there is nothing to show. "
            + "A preview adds nobody to the game: participants join from their own page. Reload this preview once they have "
            + "joined.</p>", text(ScriptBoard.notInGameScreen("<p1>", null)));
        assertEquals("<h3>This session isn't running</h3><p>It may have ended, or it hasn't been opened yet. If you were given a "
            + "link to the study, open it again; otherwise you can close this page.</p>", text(ScriptBoard.sessionNotRunningScreen(null)));
        assertEquals("<h3>The session has already started</h3><p>It began before you arrived, so it can't take anyone new. Please "
            + "contact the researcher.</p>", text(ScriptBoard.sessionStartedScreen(null)));
        assertEquals("<h3>This session has ended</h3><p>Thank you for taking part. You can close this page.</p>",
            text(ScriptBoard.endedSessionScreen(9102L, "nobody")));
    }
}
