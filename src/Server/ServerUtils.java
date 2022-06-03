package Server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import Hash.ServerLabel;
import Hash.ServerTree;
import Membership.LogSaverThread;
import Hash.ServerKey;

/**
 * A class that stores message processing methods (all methods ending in Msg),
 * socket register methods (all methods containing register), and variables used
 * to identify different types of sockets and messages.
 */
public class ServerUtils {
    final static int JOIN_TRIES = 3;
    final static int JOIN_TIMEOUT = 5 * 1;

    final static int MAX_JOIN_RESP_DELAY = 4 * 1000;

    final static int TCP_SERVER_SOCKET = 1;
    final static int TCP_SOCKET = 2;
    final static int UDP_MULTICAST = 3;
    final static int RMI_PIPE = 4;

    public final static char JOIN_MSG = 'j';
    public final static char LEAVE_MSG = 'l';
    public final static char JOIN_RESP_MSG = 'r';
    public final static char PUT_MSG = 'p';
    public final static char GET_MSG = 'g';
    public final static char DEL_MSG = 'd';

    final static List<Character> TCP_VALID_MSGS = Arrays.asList(PUT_MSG, GET_MSG, DEL_MSG);

    // Network Interface used by UDP Multicast
    static String niName = "lo";

    /**
     * Parses {@link #JOIN_RESP_MSG} received via TCP. It appends existing
     * users to {@link Server#active_nodes} and merges received logs to
     * {@link Server#logs}.
     * <p>
     * Message format: a char equal to {@value #JOIN_RESP_MSG}, a list of the
     * cluster's active nodes, a dupe log (to finalize the end) and a list of the
     * last 32 membership event logs. If less than 32 membership events exist then
     * dupe logs will be used to fill the remainder.
     * 
     * @param buffer
     * @param key
     * @param activeNodes   All actives nodes (Nodes with an even membership
     *                      counter).
     * @param logs          All Membership Events.
     * @param lastLogs      Most recent 32 Membership Events.
     * @param hostsReceived List of hosts who already sent us data (To avoid
     *                      duplication if a timeout occurs).
     * @return {@code true} when all packets were received.
     * @throws IOException
     * @throws ClassNotFoundException
     */
    public static boolean receiveJoinRespMsg(ByteBuffer buffer, SelectionKey key, ServerTree activeNodes,
            Map<String, Log> logs, LinkedList<Log> lastLogs)
            throws IOException, ClassNotFoundException {
        SocketChannel client = (SocketChannel) key.channel();
        var bRead = client.read(buffer);
        buffer.flip();
        // Activated if its the first time reading from this key
        if (key.attachment() == null) {
            // Support buffer used to facilitate the byte parsing
            var suppBuff = ByteBuffer.allocate(Log.LOG_BYTE_SIZE);

            // Checking if the msg is indeed of type JOIN_RESP_MSG
            var debug = buffer.getChar();
            if (debug != JOIN_RESP_MSG) {
                System.out.println("Expected join response, got " + debug + " read " + bRead);
                client.close();
                key.cancel();
                buffer.clear();
                return false;
            }
            bRead -= Character.BYTES;

            // Boolean used to know when we finished parsing all the active nods
            var receivedAllNodes = false;
            // Int to keep track of the amount of logs received
            var logCounter = 0;
            key.attach(new ArrayList<>() {
                {
                    add(suppBuff);
                    add(receivedAllNodes);
                    add(logCounter);
                }
            });
        }

        @SuppressWarnings("unchecked")
        var att = (List<Object>) key.attachment();
        var suppBuff = (ByteBuffer) att.get(0);
        var receivedAllNodes = (boolean) att.get(1);
        var logCounter = (int) att.get(2);

        // Connection was closed
        if (bRead == -1) {
            client.close();
            key.cancel();
            buffer.clear();
            return false;
        }

        // Fill our suppBuff with bytes and read a log when its filled
        while (bRead > 0) {
            var b = buffer.get();
            suppBuff.put(b);
            bRead--;
            if (!suppBuff.hasRemaining()) {
                suppBuff.flip();
                var log = Log.logDeserializer(suppBuff);
                // dummy log used to mark the end of the active nodes list
                if (!receivedAllNodes && log.getNodeIDstr().equals("255.255.255.255")) {
                    receivedAllNodes = true;
                }
                // If we are reading logs
                else if (receivedAllNodes) {
                    logCounter++;
                    // If its not a dupe (useless log sent so that the logs list size remains
                    // constant)
                    if (log.getCount() != -1) {
                        // Merge the log
                        mergeLog(log, logs, lastLogs);
                    }
                } else {
                    activeNodes.addServer(new ServerLabel(log));
                }
                suppBuff.clear();

            }

        }
        buffer.clear();
        // We use listLength to find the number of active nodes but we also reuse it to
        // find out when we have received all the messages.
        if (logCounter == 32) {
            client.close();
            return true;
        }
        key.attach(new ArrayList<>(Arrays.asList(suppBuff, receivedAllNodes, logCounter)));
        buffer.clear();
        return false;
    }

    /**
     * Handler for TCP Messages when the server is executing. To check what msg
     * codes are supported go to {@link #TCP_VALID_MSGS}.
     * 
     * @param key
     * @param buffer
     * @throws IOException
     */
    public static String receiveTCPMsg(Selector selector, SelectionKey key, ByteBuffer buffer)
            throws IOException {
        @SuppressWarnings("unchecked")
        List<Object> att = (List<Object>) key.attachment();
        SocketChannel client = (SocketChannel) key.channel();
        var bRead = client.read(buffer);
        buffer.flip();
        // If its the first time reading from this socket
        if (att.size() == 1) {
            var msgCode = buffer.getChar();
            bRead -= Character.BYTES;

            // IF the msg code is not supported close the connection
            if (!TCP_VALID_MSGS.contains(msgCode)) {
                System.out.println("Expected any one of " + ServerUtils.TCP_VALID_MSGS + " received " + msgCode);
                buffer.clear();
                client.close();
                key.cancel();
            }
            var msgLength = buffer.getInt();
            bRead -= Integer.BYTES;
            var suppBuff = ByteBuffer.allocate(512);
            att.add(msgLength);
            att.add(suppBuff);
            att.add(msgCode);
        }

        var msgLength = (int) att.get(1);
        var suppBuff = (ByteBuffer) att.get(2);
        var msgCode = (char) att.get(3);
        if (bRead == -1) {
            client.close();
            key.cancel();
            buffer.clear();
        }

        while (bRead > 0) {
            var b = buffer.get();
            suppBuff.put(b);
            bRead--;
            msgLength--;
        }

        if (msgLength == 0) {
            suppBuff.flip();
            var msg = new String(suppBuff.array(), 0, suppBuff.limit(), StandardCharsets.UTF_8);
            suppBuff.clear();
            buffer.clear();
            client.close();
            key.cancel();
            return msgCode + " " + msg;
        }

        buffer.clear();
        att.remove(1);
        att.add(1, msgLength);
        return "";
    }

    /**
     * Used to broadcast a {@link #JOIN_MSG} or a {@link #LEAVE_MSG} via UDP
     * 
     * @param mode {@value #JOIN_MSG} for join {@value #LEAVE_MSG} for leave
     */
    public static void sendJoinOrLeaveMsg(char mode, String ip_mcast_addr, int ip_mcast_port, String nodeID,
            int nodePort)
            throws IOException {
        InetAddress group = InetAddress.getByName(ip_mcast_addr);
        MulticastSocket multicastSocket;
        multicastSocket = new MulticastSocket();

        var buf = new byte[1 + 4 + 4]; // Mode (join or leave) + Server Name (IP/Node_ID) + TCP Port
        // Setting mode
        buf[0] = (byte) mode;
        // Setting IPV4 Address
        var ipv4Str = nodeID.split("\\.");
        for (int i = 1; i < 5; i++) {
            buf[i] = Integer.valueOf(Integer.parseInt(ipv4Str[i - 1])).byteValue();
        }

        ByteBuffer.wrap(buf).putInt(5, nodePort);
        DatagramPacket packet = new DatagramPacket(buf, buf.length, group, ip_mcast_port);

        multicastSocket.send(packet);
        multicastSocket.close();
    }

    /**
     * Function used to decode all UDP messages
     * 
     * @param buffer
     * @param key
     * @return
     *         {@code Request type}:
     *         "j" if its a join request or "l" if its a leave request.
     *         {@code Sender Node ID}:
     *         ip_address of the sender.
     *         {@code The TCP port}:
     *         port opened by the sender.
     * 
     * @throws IOException
     */
    public static List<String> receiveMembershipMsg(ByteBuffer buffer, SelectionKey key) throws IOException {
        DatagramChannel udpChannel = (DatagramChannel) key.channel();
        udpChannel.receive(buffer);

        // Getting Mode
        String mode = "" + (char) buffer.array()[0];
        // Getting IPV4 Address
        String node_id = "";
        for (int i = 1; i < 4; i++) {
            node_id += Integer.toString(Byte.toUnsignedInt(buffer.array()[i])) + ".";
        }
        node_id += Integer.toString(Byte.toUnsignedInt(buffer.array()[4]));
        // Getting Port Address
        String tcpPort = "" + buffer.getInt(5);
        buffer.clear();
        return new ArrayList<>(Arrays.asList(mode, node_id, tcpPort));
    }

    /**
     * TCP message triggered by {@link #receiveMembershipMsg}. Sends a
     * {@link #JOIN_RESP_MSG}
     * <p>
     * Sends all active nodes {@link Server#active_nodes} and the last 32 membership
     * events {@link Server#logs}
     * 
     * @throws IOException If it fails closing the client connection
     */
    public static boolean sendJoinRespMsg(SelectionKey key, ServerTree active_nodes,
            LinkedList<Log> lastLogs) throws IOException {
        @SuppressWarnings("unchecked")
        List<Object> att = (List<Object>) key.attachment();
        SocketChannel client = (SocketChannel) key.channel();
        // If its the first time writing to this socket
        if (att.size() == 1) {
            // We attach an iterator for the active nodes and the logs. We also attach a
            // counter to know when we have sent 32 logs and a buffer to assist us in the
            // translation from logs to bytes
            var logCounter = -1;
            var suppBuff = ByteBuffer.allocate(Log.LOG_BYTE_SIZE);
            att.add(active_nodes.getEntrySet().iterator());
            att.add(lastLogs.descendingIterator());
            att.add(logCounter);
            att.add(suppBuff);

            // Sending a header
            suppBuff.putChar(JOIN_RESP_MSG);
            suppBuff.flip();
            client.write(suppBuff);
            suppBuff.clear();
        }

        // Getting iterators and counter
        @SuppressWarnings("unchecked")
        var itActiveNodes = (Iterator<Entry<ServerKey, ServerLabel>>) att.get(1);
        @SuppressWarnings("unchecked")
        var itLogs = (Iterator<Log>) att.get(2);
        var logCounter = (int) att.get(3);
        var suppBuff = (ByteBuffer) att.get(4);

        // A partial write occurred on the previous call
        if (suppBuff.hasRemaining() && suppBuff.remaining() < Log.LOG_BYTE_SIZE) {
            // Reading the rest of the log from suppBuff
            client.write(suppBuff);
            suppBuff.clear();
        }

        // Sending all active nodes
        while (itActiveNodes.hasNext()) {
            var label = itActiveNodes.next().getValue();
            var log = new Log(label.getNodeID(), label.getPort(), -1);

            // Writing a log to suppBuff
            Log.logSerializer(suppBuff, log);
            suppBuff.flip();
            // Reading a log from suppBuff
            client.write(suppBuff);
            // Partial Write
            if (suppBuff.hasRemaining()) {
                suppBuff.compact();
                key.attach(new ArrayList<>(Arrays.asList(att.get(0), itActiveNodes, itLogs, logCounter, suppBuff)));
                return false;
            }
            suppBuff.clear();
        }

        // Dummy Log used to sinalize the end of the active nodes list
        if (!itActiveNodes.hasNext() && logCounter == -1) {

            var log = new Log("255.255.255.255", -1, -1);
            Log.logSerializer(suppBuff, log);
            suppBuff.flip();
            client.write(suppBuff);
            logCounter = 0;
            suppBuff.clear();
        }

        // when we sended all the active nodes and the dummy node then we repeat the
        // process for the logs
        while (logCounter != -1 && itLogs.hasNext()) {
            var log = itLogs.next();

            // Writing a log to suppBuff
            Log.logSerializer(suppBuff, log);
            suppBuff.flip();
            // Reading a log from suppBuff
            client.write(suppBuff);
            ++logCounter;
            // Partial Write
            if (suppBuff.hasRemaining()) {
                suppBuff.compact();
                key.attach(new ArrayList<>(Arrays.asList(att.get(0), itActiveNodes, itLogs, logCounter, suppBuff)));
                return false;
            }
            suppBuff.clear();
        }
        // By this point all logs were already sent. In case the lastLogs.size() < 32 we
        // fill write dummies till we reach that number
        while (logCounter < 32) {
            var log = new Log("255.255.255.255", -1, -1);

            // Writing a log to suppBuff
            Log.logSerializer(suppBuff, log);
            suppBuff.flip();
            // Reading a log from suppBuff
            client.write(suppBuff);
            ++logCounter;
            // Partial Write
            if (suppBuff.hasRemaining()) {
                suppBuff.compact();
                key.attach(new ArrayList<>(Arrays.asList(att.get(0), itActiveNodes, itLogs, logCounter, suppBuff)));
                return false;
            }
            suppBuff.clear();
        }

        // Normal exit
        if (logCounter == 32) {
            key.attach(new ArrayList<>());
            client.close();
            return true;
        }
        key.attach(new ArrayList<>(Arrays.asList(att.get(0), itActiveNodes, itLogs, logCounter, suppBuff)));
        return false;
    }

    /**
     * Accept and register a TCP Socket
     * 
     * @param selector
     * @param serverSocket
     * @throws IOException
     */
    public static void registerTCPSocket(Selector selector, ServerSocketChannel serverSocket, int interestOps,
            Object att)
            throws IOException {
        SocketChannel client = serverSocket.accept();
        client.configureBlocking(false);
        client.register(selector, interestOps, att);
    }

    /**
     * register a TCP Socket
     * 
     * @param selector
     * @param socket
     * @throws IOException
     */
    public static void registerTCPSocket(Selector selector, int interestOps,
            Object att)
            throws IOException {
        SocketChannel socketChannel;
        socketChannel = SocketChannel.open();
        socketChannel.configureBlocking(false);
        socketChannel.register(selector, interestOps, att);
    }

    /**
     * Connect to a host via TCP and register the socket
     */
    public static void connectAndRegisterTCPSocket(Selector selector, String targetHost, int port, int interestOps,
            Object att)
            throws IOException {

        SocketChannel socketChannel;
        socketChannel = SocketChannel.open();
        socketChannel.connect(new InetSocketAddress(targetHost, port));
        socketChannel.configureBlocking(false);
        socketChannel.register(selector, interestOps, att);
    }

    /**
     * Update logs and lastLogs lists. If the log wasn't on the list, then append
     * it,
     * otherwise it's updated if the counter is bigger than our previous occurrence
     * 
     * @param log
     * @param logs
     * @param lastLogs
     */
    public static void mergeLog(Log log, Map<String, Log> logs, LinkedList<Log> lastLogs) {
        // If the log was not one of the last 32 events then we add it
        // Merge the log
        var nodeID = log.getNodeIDstr();
        var match = logs.get(nodeID);
        if (match == null) {
            logs.put(nodeID, log);
            if (lastLogs.size() == 32)
                lastLogs.removeLast();
            lastLogs.addFirst(log);
        } else if (match.getCount() < log.getCount()) {
            match.setCount(log.getCount());

            // If the log already existed then we remove it and append it in the start
            if (lastLogs.contains(match))
                lastLogs.remove(match);
            else if (lastLogs.size() == 32)
                lastLogs.removeLast();
            lastLogs.addFirst(log);
        }
    }

    /**
     * Update logs and lastLogs lists. If the log wasn't on the list, then append
     * it,
     * otherwise it's updated if the counter is bigger than our previous occurrence
     * 
     * @param mode
     * @param node_id
     * @param port
     * @param logs
     * @param lastLogs
     */
    public static void mergeLog(char mode, String node_id, int port, ServerTree active_nodes,
            Map<String, Log> logs, LinkedList<Log> lastLogs) {
        var match = logs.get(node_id);
        // First time this node_id joined
        if (match == null) {
            var log = new Log(node_id, port);
            logs.put(node_id, log);
            if (lastLogs.size() == 32)
                lastLogs.removeLast();
            lastLogs.addFirst(log);
            active_nodes.addServer(new ServerLabel(log));
        }
        // He can only join is he was not in the cluster and vice-versa
        else if ((mode == JOIN_MSG && !match.isActive()) || (mode == LEAVE_MSG && match.isActive())) {
            match.addCount();
            // If the log already existed then we remove it and append it in the start
            if (lastLogs.contains(match)) {
                lastLogs.remove(match);
            }
            if (lastLogs.size() == 32)
                lastLogs.removeLast();
            lastLogs.addFirst(match);
            if (match.isActive())
                active_nodes.addServer(new ServerLabel(match));
            else
                active_nodes.removeServer(new ServerLabel(match));
        }

        System.out.println("Merge Log:");
        System.out.println(active_nodes);
        System.err.println("----------------------------------");
        System.out.println(logs);
        System.err.println("----------------------------------");
        System.out.println(lastLogs);
    }

    /**
     * similar to {@link #receiveMembershipMsg} but it blocks until the message is
     * received
     * 
     * @param ip
     * @param port
     * @return
     * @throws IOException
     */
    public static List<String> receiveMembershipMsgBlocking(String ip, int port) throws IOException {
        InetAddress group = InetAddress.getByName(ip);
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
            node_id += Integer.toString(Byte.toUnsignedInt(buf[i])) + ".";
        }
        node_id += Integer.toString(Byte.toUnsignedInt(buf[4]));
        // Getting Port Address
        String tcpPort = "" + ByteBuffer.wrap(buf).getInt(5);
        mSocket.close();
        System.out.println("Received: " + Arrays.asList(mode, node_id, tcpPort));
        return Arrays.asList(mode, node_id, tcpPort);
    }

    public static Thread saveLogs(Map<String, Log> logs, String node_id, LinkedList<Log> lastLogs) {
        Thread thread = new Thread(new LogSaverThread(logs.entrySet().iterator(), lastLogs.iterator(), "./" + node_id + "/"));
        thread.start();
        return thread;
    }
}
