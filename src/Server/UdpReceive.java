package Server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

// Thread that awaits an UDP multicast msg
public class UdpReceive implements Callable<List<String>> {
    final InetAddress group;
    final int port;

    UdpReceive(String ip_addr, String ip_port) throws UnknownHostException {
        group = InetAddress.getByName(ip_addr);
        port = Integer.parseInt(ip_port);
    }

    @Override
    public List<String> call() throws IOException {
        MulticastSocket mSocket = new MulticastSocket(port);
        mSocket.joinGroup(new InetSocketAddress(group, 0), null);

        // System.out.println("waiting for input");
        byte[] buf = new byte[1 + 4 + 4]; // Mode (join or leave) + Server Name (IP/Node_ID) + TCP Port
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        mSocket.receive(packet);
        mSocket.close();

        // Getting Mode
        String mode = "" + (char) buf[0];
        // Getting IPV4 Address 
        String node_id = "";
        for (int i = 1; i < 4; i++) {
            node_id += Integer.toString(Byte.toUnsignedInt(buf[i])) + "." ;
        }
        node_id += Integer.toString(Byte.toUnsignedInt(buf[4]));
        // Getting Port Address
        String tcpPort = "" + ByteBuffer.wrap(buf).getInt(5);

        return Arrays.asList(mode,node_id, tcpPort);
    }
}
