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
        volatile boolean pauseFirstReply;
        final java.util.concurrent.CountDownLatch replyStarted = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch allowReply = new java.util.concurrent.CountDownLatch(1);
        final ThrottledWebSocketOut out = new ThrottledWebSocketOut(new WebSocket.Out<JsonNode>() {
            public void write(JsonNode frame) { frames.add(frame); }
            public void close() {}
        }, 0L) {
            @Override public void writeIfOpen(JsonNode frame) {
                if (pauseFirstReply && frame.has("user")) {
                    replyStarted.countDown();
                    try { allowReply.await(30, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                super.writeIfOpen(frame);
            }
        };
        Socket() { sockets.add(this); }
    }

    @After
    public void closeSockets() {
        for (Socket socket : sockets) {
            socket.allowReply.countDown();
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

    @Test
    public void aPasswordResetDuringTheFirstAccountSnapshotSendsNoAccountData() throws Exception {
        Language english = createLanguage("eng", "English");
        User account = createUser("snapshot-" + UUID.randomUUID() + "@example.org", "Snapshot account", UUID.randomUUID().toString(), english);
        User other = createUser("other-" + UUID.randomUUID() + "@example.org", "Another researcher", UUID.randomUUID().toString(), english);
        java.util.concurrent.CountDownLatch snapshotStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finishSnapshot = new java.util.concurrent.CountDownLatch(1);
        User slowSnapshot = new User() {
            @Override public com.fasterxml.jackson.databind.node.ObjectNode toJson() {
                snapshotStarted.countDown();
                try { finishSnapshot.await(30, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return account.toJson();
            }
        };
        slowSnapshot.email = account.email;
        slowSnapshot.uid = account.uid;
        Socket socket = new Socket();
        java.lang.reflect.Field controller = Breadboard.class.getDeclaredField("breadboardController");
        controller.setAccessible(true);
        ((akka.actor.ActorRef) controller.get(null)).tell(new Breadboard.LogIn(slowSnapshot, socket.out), null);
        try {
            assertTrue("the actor began the first snapshot", snapshotStarted.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(200, status(route(fakeRequest(POST, "/researchers/password").withSession("uid", other.uid)
                .withJsonBody(Json.newObject().put("email", account.email)))));
        } finally {
            finishSnapshot.countDown();
        }
        awaitExpiredSocket(socket);
        assertFalse("the second token check precedes the first account reply", socket.frames.stream().anyMatch(f -> f.has("user")));
    }

    @Test
    public void aResetAfterRegistrationBeforeTheFirstReplyDropsTheNormalFrameButSendsTheFinalNotice() throws Exception {
        Language english = createLanguage("eng", "English");
        User account = createUser("reply-" + UUID.randomUUID() + "@example.org", "Reply account", UUID.randomUUID().toString(), english);
        User other = createUser("other-" + UUID.randomUUID() + "@example.org", "Another researcher", UUID.randomUUID().toString(), english);
        Socket socket = new Socket();
        socket.pauseFirstReply = true;
        java.lang.reflect.Field controller = Breadboard.class.getDeclaredField("breadboardController");
        controller.setAccessible(true);
        ((akka.actor.ActorRef) controller.get(null)).tell(new Breadboard.LogIn(account, socket.out), null);
        try {
            assertTrue("the registered socket is about to accept its first reply", socket.replyStarted.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(200, status(route(fakeRequest(POST, "/researchers/password").withSession("uid", other.uid)
                .withJsonBody(Json.newObject().put("email", account.email)))));
            assertTrue("revocation marked the registered output closed", socket.out.isClosed());
        } finally {
            socket.allowReply.countDown();
        }
        // A barrier through the same actor: the first reply has now returned and no later replay
        // for the expired sign-in can leak a graph either.
        Socket barrier = new Socket();
        ((akka.actor.ActorRef) controller.get(null)).tell(new Breadboard.LogIn(other, barrier.out), null);
        long until = System.currentTimeMillis() + 10000;
        while (barrier.frames.stream().noneMatch(f -> f.has("user")) && System.currentTimeMillis() < until) Thread.sleep(20);
        assertTrue("the actor passed the stopped first reply", barrier.frames.stream().anyMatch(f -> f.has("user")));
        assertFalse("a normal frame accepted after revocation is dropped", socket.frames.stream().anyMatch(f -> f.has("user") || f.has("graphReplay")));
        assertTrue("the final unauthorized notice is still sent", socket.frames.stream().anyMatch(f -> "unauthorized".equals(f.path("status").asText())));
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
        awaitExpiredSocket(socket);
        assertFalse("an expired queued sign-in must send no account data", socket.frames.stream().anyMatch(f -> f.has("user")));
    }

    private void awaitExpiredSocket(Socket socket) throws InterruptedException {
        long until = System.currentTimeMillis() + 10000;
        while ((!socket.out.isClosed() || socket.frames.stream().noneMatch(f -> "unauthorized".equals(f.path("status").asText())))
            && System.currentTimeMillis() < until) Thread.sleep(20);
        assertTrue("the expired sign-in must close its socket", socket.out.isClosed());
        assertTrue("the console is told why it must sign in again",
            socket.frames.stream().anyMatch(f -> "unauthorized".equals(f.path("status").asText())));
    }
}
