package FileHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe.SourceChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

import Hash.ServerKey;
import Hash.ServerLabel;

public class FileHandlerThread implements Runnable {
    SourceChannel inChannel;
    private int storePort;
    private String filePath;

    public FileHandlerThread(SourceChannel inChannel, int storePort, String filePath) throws IOException {
        this.inChannel = inChannel;
        inChannel.configureBlocking(true);
        this.storePort = storePort;
        this.filePath = filePath + "/files";
        // Creating the folder where all files will be stored
        Files.createDirectories(Paths.get(this.filePath));
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
            // Msg Code, Client IP, File Name
            var parsedMsg = msg.split(" ");
            var fileName = hashStr(parsedMsg[2]);

            System.out.println("Thread Received: " + Arrays.asList(parsedMsg));
            System.out.println(fileName);
            buffer.clear();
        }
    }

    private String hashStr(String in) {
        return ServerLabel.bytesToHex(new ServerKey(ServerLabel.hashString(in)));
    }
}
