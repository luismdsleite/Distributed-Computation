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
    private Iterator<Log> lastLogsIter;

    public LogSaverThread(Iterator<Entry<String, Log>> logsIter, Iterator<Log> lastLogsIter, String path) {
        this.logsIter = logsIter;
        this.path = path;
        this.lastLogsIter = lastLogsIter;
    }

    @Override
    public void run() {
        var suppBuff = ByteBuffer.allocate(Log.LOG_BYTE_SIZE);
        // Not including the boolean parameter
        try {
            FileOutputStream output = new FileOutputStream(path + "logs");
            // Sending all active nodes
            while (logsIter.hasNext()) {
                var log = logsIter.next().getValue();
                Log.logSerializer(suppBuff, log);
                output.write(suppBuff.array());
                suppBuff.clear();
            }
            output.close();

            FileOutputStream output2 = new FileOutputStream(path + "lastLogs");
            // Sending all active nodes
            while (lastLogsIter.hasNext()) {
                var log = lastLogsIter.next();
                Log.logSerializer(suppBuff, log);
                output2.write(suppBuff.array());
                suppBuff.clear();
            }
            output2.close();

            System.out.println("Just saved!");
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }
}
