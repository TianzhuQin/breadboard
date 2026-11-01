package launcher;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class DisconnectedOutputTest {
  private static final String CLOSED = "java.nio.channels.ClosedChannelException\n"
      + "\tat org.jboss.netty.channel.socket.nio.NioWorker.write(NioWorker.java:123)\n"
      + "\tat play.core.server.netty.WebSocketHandler$$anon$3.write(WebSocketHandler.scala:80)\n"
      + "\tat java.lang.Thread.run(Thread.java:750)\n";

  @Test public void aDroppedWebsocketWritesOneNoteAndNoStackTrace() throws Exception {
    ByteArrayOutputStream window = new ByteArrayOutputStream();
    AtomicInteger notes = new AtomicInteger();
    OutputStream out = Main.connectionErrors(window, notes::incrementAndGet);
    String input = "Before\n" + CLOSED + "参与者重新连接\n" + CLOSED;
    // Arbitrary write boundaries, as printStackTrace writes it.
    for (byte b : input.getBytes(StandardCharsets.UTF_8)) out.write(b & 255);
    out.flush();
    assertEquals("Before\n参与者重新连接\n", window.toString("UTF-8"));
    assertEquals(2, notes.get());
  }

  @Test public void unrelatedErrorsAndPartialLinesArePreservedExactly() throws Exception {
    ByteArrayOutputStream window = new ByteArrayOutputStream();
    AtomicInteger notes = new AtomicInteger();
    PrintStream err = new PrintStream(Main.connectionErrors(window, notes::incrementAndGet), false, "UTF-8");
    String ordinary = "java.nio.channels.ClosedChannelException\r\n\tat my.experiment.Step.run(Step.java:5)\r\n"
        + "java.lang.IllegalStateException: real failure\n\tat play.core.server.netty.WebSocketHandler.run(WebSocketHandler.scala:80)\n"
        + "配置错误：缺少引号";
    err.print(ordinary);
    err.flush();
    assertEquals(ordinary, window.toString("UTF-8"));
    assertEquals(0, notes.get());
  }

  @Test public void longLinesAndUnrecognizedTracesDoNotAccumulate() throws Exception {
    ByteArrayOutputStream window = new ByteArrayOutputStream();
    OutputStream out = Main.connectionErrors(window, () -> fail("not a websocket trace"));
    StringBuilder input = new StringBuilder("java.nio.channels.ClosedChannelException\n");
    for (int i = 0; i < 2000; i++) input.append("\tat my.experiment.Step.run(Step.java:5)\n");
    for (int i = 0; i < 20000; i++) input.append('x');
    out.write(input.toString().getBytes(StandardCharsets.UTF_8));
    assertTrue("bounded buffering forwards large output before flush", window.size() > DisconnectedOutput.MAX_TRACE);
    out.flush();
    assertEquals(input.toString(), window.toString("UTF-8"));
  }
}
