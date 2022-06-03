package FileHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe.SourceChannel;
import java.util.Arrays;

public class FileHandlerThread implements Runnable {
    SourceChannel inChannel;
    private int storePort;
    private String filePath;

    public FileHandlerThread(SourceChannel inChannel, int storePort, String filePath) throws IOException {
        this.inChannel = inChannel;
        inChannel.configureBlocking(true);
        this.storePort = storePort;
        this.filePath = filePath;
    }

    @Override
    public void run() {
        ByteBuffer buffer = ByteBuffer.allocate(512);
        int bRead = 0;
        while (true) {
            try {
                bRead = inChannel.read(buffer);
                buffer.flip();
                if (bRead == -1) {
                    break;
                }
            } catch (IOException e) {
                e.printStackTrace();
                continue;
            }
            var msg = new String(buffer.array());
            var parsedMsg = msg.split(" ");
            // System.out.println(msg);
            System.out.println(Arrays.asList(parsedMsg));
            buffer.clear();
        }
    }
}
