package Membership;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import Server.Log;

public class LogSaverThread implements Runnable {
    private Iterator<Entry<String, Log>> logsIter;
    private String path;

    public LogSaverThread(Iterator<Entry<String, Log>> logsIter, String path) {
        this.logsIter = logsIter;
        this.path = path;
    }

    @Override
    public void run() {
        var suppBuff = ByteBuffer.allocate(Log.LOG_BYTE_SIZE);
        // Not including the boolean parameter
        try {
            FileOutputStream output = new FileOutputStream(path + "/log.txt");
            // Sending all active nodes
            while (logsIter.hasNext()) {
                var log = logsIter.next().getValue();
                Log.logSerializer(suppBuff, log);
                output.write(suppBuff.array());
                suppBuff.clear();
            }
            output.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }
}
