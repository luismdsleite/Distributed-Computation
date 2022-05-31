package Server;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Random;

/**
 * Only used for testing!
 */
public class TcpClient {
    final static int TIMEOUT = 1000000;

    public static void main(String[] args) {
        try {
            System.out.println("Connecting to 127.0.0.1 on port " + 6666);
            Socket socket = new Socket("127.0.0.1", 6666);

            System.out.println("Just connected to " + socket.getRemoteSocketAddress());

            Random rand = new Random();
            var logs = new ArrayList<Log>();

            DataOutputStream dOut = new DataOutputStream(socket.getOutputStream());
            int len = 12;
            System.out.println("Active Nodes");
            // dOut.writeInt(len);
            for (int i = 0; i < len; i++) {
                var node_id = "127.0.0." + (i + 1);
                var log = new Log(node_id, rand.nextInt(1000), -1);
                logs.add(log);
                dOut.write(log.getNodeID());
                dOut.writeInt(log.getPort());
                dOut.writeInt(log.getCount());
                System.out.println(log);
            }

            // Dummy log that marks the end of the Active node
            var dummyLog = new Log("255.255.255.255", -1, -1);
            dOut.write(dummyLog.getNodeID());
            dOut.writeInt(dummyLog.getPort());
            dOut.writeInt(dummyLog.getCount());

            System.out.println("--------------------");
            System.out.println("Logs");
            // Writing logs!
            int sep = 10; // To test case where the number of existing logs is less than 32
            for (int i = 0; i < 32; i++) {
                var node_id = "127.0.0." + i;
                var log = new Log(node_id, rand.nextInt(1000), rand.nextInt(1000));
                if (sep < i) {
                    node_id = "255.255.255.255";
                    log = new Log(node_id, -1, -1);
                }
                logs.add(log);
                dOut.write(log.getNodeID());
                dOut.writeInt(log.getPort());
                dOut.writeInt(log.getCount());
                System.out.println(log);
            }

            // for (Log log : logs) {
            // System.out.println(log);
            // }
            // fromServer.close();
            socket.close();
            System.out.println("Closed port");
        } catch (UnknownHostException ex) {
            ex.printStackTrace();
            return;
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("timed out");
            return;
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("someintgdfasdsad");
        }
        System.out.println("Ended Main");
        return;
    }

}