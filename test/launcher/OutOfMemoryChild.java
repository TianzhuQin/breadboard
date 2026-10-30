package launcher;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Run by LauncherJavaOptionsTest in a Java of its own, with a launcher's options: fills its window
 * (its standard output, a pipe nobody reads) until writing to it waits, then runs out of memory.
 */
public final class OutOfMemoryChild {

  private static volatile long written;

  private OutOfMemoryChild() {}

  public static void main(String[] args) throws Exception {
    Thread filler = new Thread(() -> {
      byte[] line = new byte[1024];
      Arrays.fill(line, (byte) 'x');
      line[line.length - 1] = '\n';
      try {
        FileOutputStream window = new FileOutputStream(FileDescriptor.out);
        while (true) {
          window.write(line);
          written += line.length;
        }
      } catch (IOException e) {
        // the window was closed
      }
    }, "window-filler");
    filler.setDaemon(true);
    filler.start();
    // The window is full once writing to it has waited for a second.
    long seen = -1;
    while (written == 0 || written != seen) {
      seen = written;
      Thread.sleep(1000);
    }
    System.err.println("The window takes no more output (" + written + " bytes); now the heap.");
    List<long[]> heap = new ArrayList<>();
    while (true) {
      heap.add(new long[1 << 16]);
    }
  }
}
