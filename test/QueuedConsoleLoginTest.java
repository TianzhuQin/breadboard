import com.fasterxml.jackson.databind.JsonNode;
import models.*;
import org.junit.After;
import org.junit.Test;
import play.libs.Json;
import play.mvc.WebSocket;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/** A frame authenticated before an account change must not register a console after that change. */
public class QueuedConsoleLoginTest extends BaseTest {
    private final List<Socket> sockets = new ArrayList<>();

    private class Socket {
        final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        final ThrottledWebSocketOut out = new ThrottledWebSocketOut(new WebSocket.Out<JsonNode>() {
            public void write(JsonNode frame) { frames.add(frame); }
            public void close() {}
        }, 0L);
        Socket() { sockets.add(this); }
    }

    @After
    public void closeSockets() {
        for (Socket socket : sockets) {
            socket.out.markClosed();
            ScriptBoard.removeAdmin(socket.out);
        }
    }

    @Test
    public void aPasswordResetThatOvertakesAnAuthenticatedLogInClosesItsUnregisteredSocket() throws Exception {
        queuedLoginCannotRegister(false);
    }

    @Test
    public void removingTheAccountBeforeItsQueuedLogInRegistersClosesTheSocketToo() throws Exception {
        queuedLoginCannotRegister(true);
    }

    private void queuedLoginCannotRegister(boolean remove) throws Exception {
        Language english = createLanguage("eng", "English");
        User account = createUser("queued-" + UUID.randomUUID() + "@example.org", "Queued account", UUID.randomUUID().toString(), english);
        User other = createUser("other-" + UUID.randomUUID() + "@example.org", "Another researcher", UUID.randomUUID().toString(), english);
        // Breadboard.dispatch has authenticated the frame and made this actor message already, but
        // the actor has not handled it yet: no Admin exists for its output, so revocation cannot find it.
        User authenticated = User.findByUID(account.uid);
        assertNotNull(authenticated);
        Socket socket = new Socket();
        Breadboard.LogIn queued = new Breadboard.LogIn(authenticated, socket.out);
        String route = remove ? "/researchers/remove" : "/researchers/password";
        assertEquals(200, status(route(fakeRequest(POST, route).withSession("uid", other.uid)
            .withJsonBody(Json.newObject().put("email", account.email)))));
        assertFalse("the socket was not registered when the account changed", socket.out.isClosed());
        java.lang.reflect.Field controller = Breadboard.class.getDeclaredField("breadboardController");
        controller.setAccessible(true);
        ((akka.actor.ActorRef) controller.get(null)).tell(queued, null);
        long until = System.currentTimeMillis() + 30000;
        while (!socket.out.isClosed() && socket.frames.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20);
        assertTrue("the expired queued sign-in must close its socket", socket.out.isClosed());
        assertFalse("an expired queued sign-in must send no account data", socket.frames.stream().anyMatch(f -> f.has("user")));
        assertTrue("the console is told why it must sign in again",
            socket.frames.stream().anyMatch(f -> "unauthorized".equals(f.path("status").asText())));
    }
}
