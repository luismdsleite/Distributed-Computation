package Server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.NetworkInterface;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.Pipe.SinkChannel;
import java.nio.channels.Pipe.SourceChannel;
import java.rmi.*;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;

import Hash.ServerLabel;
import Hash.ServerTree;
import Membership.MembershipInterface;
import Membership.MemberShipUtils;

/**
 * 
 * @param ip_mcast_addr   IP of the multicast group (UDP)
 * @param ip_mcast_port   Port of the multicast group (UDP)
 * @param node_id         IP address of the node (Or a unique id in case it is
 *                        tested in a localhost)
 * @param membership_port TCP Port used to store
 * @throws RemoteException
 */
public class Server extends UnicastRemoteObject implements MembershipInterface {

    private final String ip_mcast_addr;
    private final int ip_mcast_port;
    private final int membership_port;
    private final String node_id;
    // Nodes who according to the logs are still active
    private ServerTree active_nodes;
    // All the events that happened in the cluster
    private final Map<String, Log> logs;
    // Used as a queue, contains the last 32 logs received
    private final LinkedList<Log> lastLogs;

    // Pipes used to receive join and leave commands through RMI
    private final SinkChannel writeFileHandler;
    private final SourceChannel readFileHandler;

    // Used to delay sendJoinRespMsg()
    private final Random random;

    private Selector selector;
    private boolean isActive;

    public Server(String ip_mcast_addr, String ip_mcast_port, String node_id, String membership_port)
            throws IOException {
        // super();
        this.ip_mcast_addr = ip_mcast_addr;
        this.ip_mcast_port = Integer.parseInt(ip_mcast_port);
        this.membership_port = Integer.parseInt(membership_port);
        this.node_id = node_id;
        this.active_nodes = new ServerTree();
        this.logs = new HashMap<>();
        this.lastLogs = new LinkedList<>();
        this.random = new Random();
        this.isActive = false;

        // Pipe used to redirect put(),get(),delete() msg
        Pipe FileHandlerPipe = Pipe.open();
        writeFileHandler = FileHandlerPipe.sink();
        readFileHandler = FileHandlerPipe.source();

        // ----------------------RMI---------------------
        try {
            MemberShipUtils.startRMI(this, node_id, "membership");
        } catch (MalformedURLException e) {
            e.printStackTrace();
            System.err.println("Invalid Node ID");
        }
        // ----------------------RMI---------------------
        System.out.println("Started RMI in " + node_id);

        /**
         * The server is always either executing or waiting for a join() message
         */
        List<String> msg;

        while (true) {
            while (true) {
                // Listening to UDP messages and checking if its a join request with our node id
                msg = ServerUtils.receiveMembershipMsgBlocking(this.ip_mcast_addr, this.ip_mcast_port);
                if (msg.get(1).equals(node_id) && msg.get(0).charAt(0) == (ServerUtils.JOIN_MSG)) {
                    executeJoin();
                    break;
                }
            }
            System.out.println("Started Server");
            execute();
            active_nodes = new ServerTree();
            System.out.println("Stopped Server");

        }
    }

    /**
     * Starts a server
     * 
     * @param args {@link #ip_mcast_addr}
     * @param args {@linkplain #ip_mcast_port}
     * @param args {@link #node_id}
     * @param args {@link #membership_port}
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
        // new Server("224.0.0.0", "3456", "127.0.0.1", "6666");
        new Server(args[0], args[1], args[2], args[3]);
        System.out.println("Main Ended");
    }

    /**
     * Running state of server, executed after join().
     * <p>
     * Handles all types of requests, whether that be join, leave, get, put or
     * delete requests.
     * <p>
     * Any file request (put, get, delete) is forwarded to a thread via a
     * {@link java.nio.channels.Pipe.SinkChannel}
     * 
     */
    private void execute() {
        // Opening TCP ServerSocket
        ServerSocketChannel serverSocket;
        DatagramChannel datagramChannel;
        try {
            // Opening a Server Socket and awaiting connections
            // ---------------------------- TCP --------------------------
            selector = Selector.open();
            serverSocket = ServerSocketChannel.open();
            serverSocket.bind(new InetSocketAddress(node_id, membership_port));
            serverSocket.configureBlocking(false);
            serverSocket.register(selector, SelectionKey.OP_ACCEPT, new ArrayList<>());
            // ---------------------------- TCP --------------------------

            // Opening UDP Multicast and awaiting connections
            // ---------------------------- UDP --------------------------
            datagramChannel = DatagramChannel.open(StandardProtocolFamily.INET)
                    .setOption(StandardSocketOptions.SO_REUSEADDR, true)
                    .bind(new InetSocketAddress(ip_mcast_port))
                    .setOption(StandardSocketOptions.IP_MULTICAST_IF, NetworkInterface.getByName(ServerUtils.niName));
            datagramChannel.configureBlocking(false);
            datagramChannel.join(InetAddress.getByName(ip_mcast_addr), NetworkInterface.getByName(ServerUtils.niName));
            datagramChannel.register(selector, SelectionKey.OP_READ,
                    new ArrayList<>(Arrays.asList(ServerUtils.UDP_MULTICAST)));
            // ---------------------------- UDP --------------------------
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        System.out.println("Now able to accept connections");

        /**
         * Here we receive keys of various types (UDP,TCP,Pipe), so we identify them by
         * adding an attachment to the key
         */
        try {
            ByteBuffer buffer = ByteBuffer.allocate(1024);
            while (isActive) {

                selector.select(); // This blocks while no event is received
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iter = selectedKeys.iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();

                    /*
                     * If a key is interested in connecting it means a join was triggered and now we
                     * want to execute the join response in a random time slot
                     */
                    if (key.isValid() && key.isConnectable()) {
                        @SuppressWarnings("unchecked")
                        var att = (List<Object>) key.attachment();
                        var msgType = (char) att.get(0);
                        switch (msgType) {
                            /*
                             * JoinResp() msg is triggered by a join() msg. To avoid flooding we only
                             * execute the response after a random amount of time.
                             * If 3 other nodes already connected to the targetHost then our connection will
                             * be rejected so we cancel the key
                             */
                            case ServerUtils.JOIN_RESP_MSG:
                                var executeIn = (long) att.get(1);
                                if (executeIn < System.currentTimeMillis()) {
                                    System.out.println("Join Response");
                                    var targetHost = (String) att.get(2);
                                    var port = (int) att.get(3);
                                    try {
                                        var socket = (SocketChannel) key.channel();
                                        socket.connect(new InetSocketAddress(targetHost, port));
                                        socket.finishConnect();
                                        att.remove(3);
                                        att.remove(2);
                                        att.remove(1);
                                        key.interestOps(SelectionKey.OP_WRITE);
                                    } catch (Exception e) {
                                        // targetHost is not acception more connections so we cancel the key
                                        key.cancel();
                                    }

                                }
                                break;
                        }
                    }

                    // TCP ServerSocket
                    if (key.isValid() && key.isAcceptable()) {
                        ServerUtils.registerTCPSocket(selector, serverSocket, SelectionKey.OP_READ,
                                new ArrayList<>(Arrays.asList(ServerUtils.TCP_SOCKET)));
                    }
                    /**
                     * Since we receive a lot of diferent types of messages (UDP,TCP,Pipe), we
                     * distinguish them with a attachment that is appended to the key.
                     */
                    if (key.isValid() && key.isReadable()) {
                        @SuppressWarnings("unchecked")
                        var att = (List<Object>) key.attachment();
                        var id = (int) att.get(0);
                        switch (id) {
                            case ServerUtils.TCP_SOCKET:
                                List<String> parsedMsg = Collections.emptyList();
                                try {
                                    parsedMsg = ServerUtils.receiveTCPMsg(selector, key, buffer);
                                    if (parsedMsg.isEmpty())
                                        continue;
                                    // System.out.println(parsedMsg);
                                } catch (Exception e) {
                                    // TODO: handle exception
                                }
                                if (!parsedMsg.isEmpty()) {
                                    var mode = parsedMsg.get(0).charAt(0);
                                    System.out.println(parsedMsg);
                                    switch (mode) {
                                        case ServerUtils.PUT_MSG:
                                        case ServerUtils.GET_MSG:
                                        case ServerUtils.DEL_MSG:
                                            break;

                                        default:
                                            break;
                                    }
                                }
                                break;
                            case ServerUtils.UDP_MULTICAST:
                                System.out.println("Received UDP");
                                // Getting join/leave msg
                                var request = ServerUtils.receiveMembershipMsg(buffer, key);
                                var mode = request.get(0).charAt(0);
                                var host = request.get(1);
                                var port = Integer.parseInt(request.get(2));
                                ServerUtils.mergeLog(mode, host, port, active_nodes, logs, lastLogs);
                                switch (mode) {
                                    /**
                                     * Since we received a join() request we execute the sendJoinRespMsg() in a
                                     * random
                                     * time (to avoid flooding)
                                     */
                                    case ServerUtils.JOIN_MSG:
                                        long executeIn = random.nextInt(ServerUtils.MAX_JOIN_RESP_DELAY + 1)
                                                + System.currentTimeMillis();
                                        ServerUtils.registerTCPSocket(selector,
                                                SelectionKey.OP_CONNECT, new ArrayList<>(Arrays
                                                        .asList(ServerUtils.JOIN_RESP_MSG, executeIn, host, port)));
                                        break;
                                    case ServerUtils.LEAVE_MSG:
                                        // If the leave msg is for me
                                        if (host.equals(node_id)) {
                                            isActive = false;
                                        }
                                        break;
                                }
                                break;
                        }
                    }
                    // Join Response, Sink Channel or Multicast Log messages
                    if (key.isValid() && key.isWritable()) {
                        @SuppressWarnings("unchecked")
                        var att = (List<Object>) key.attachment();
                        var mode = (char) att.get(0);
                        switch (mode) {
                            case ServerUtils.JOIN_RESP_MSG:
                                try {
                                    ServerUtils.sendJoinRespMsg(key, active_nodes, lastLogs);
                                } catch (Exception e) {
                                    // TODO: handle exception
                                }
                                break;

                            default:
                                break;
                        }

                    }
                }
            }
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        // Closing the TCP socket
        try {
            serverSocket.close();
            datagramChannel.close();
            selector.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        System.out.println("Server was closed");
    }

    /**
     * Joins the server to the network.
     * <p>
     * Sends a join message to the multicast group with the server's ip and port.
     * The server will hopefully receive the message and execute
     * {@link #executeJoin()}
     */
    @Override
    public void join() throws RemoteException {
        if (isActive) {
            System.err.println("Server is already active");
            return;
        }
        try {
            ServerUtils.sendJoinOrLeaveMsg(ServerUtils.JOIN_MSG, ip_mcast_addr, ip_mcast_port, node_id,
                    membership_port);
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println(e);
            System.err.println("Unable to send join msg");
        }

    }

    @Override
    public void leave() throws RemoteException {
        if (!isActive) {
            System.err.println("Server is not active!");
            return;
        }

        try {
            ServerUtils.sendJoinOrLeaveMsg(ServerUtils.LEAVE_MSG, ip_mcast_addr, ip_mcast_port, node_id,
                    membership_port);
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println(e);
            System.err.println("Unable to send leave msg");
        }

        System.out.println("Leave()");
    }

    /**
     * Joins a cluster. Executed in response to {@link #join()}
     * <p>
     * Sends a UDP Broadcast message to the {@link Server#ip_mcast_addr} and awaits
     * for 3 Membership answers. It then starts executing its normal functions as a
     * part of the cluster.
     * <p>
     * If less than 3 TCP Connections are received then it begins its normal
     * functions in the cluster after it timeouts.
     * <p>
     * The timeout occurs after
     * {@value ServerUtils#JOIN_TIMEOUT}*{@value ServerUtils#JOIN_TRIES}
     */
    private void executeJoin() throws IOException {
        // Opening TCP ServerSocket and sending a join message via multicast
        Selector selector;
        ServerSocketChannel serverSocket;
        selector = Selector.open();
        serverSocket = ServerSocketChannel.open();
        serverSocket.bind(new InetSocketAddress(node_id, membership_port));
        serverSocket.configureBlocking(false);
        serverSocket.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("Opened TCP Port at " + membership_port);
        int connectionsFinalized = 0;
        // Sending a UDP Multicast Message and awaiting reply via TCP
        for (int i = 0; i < ServerUtils.JOIN_TRIES; i++) {
            int connectionsReceived = 0;
            try {
                ByteBuffer buffer = ByteBuffer.allocate(1024);
                var timeoutIn = System.currentTimeMillis() + ServerUtils.JOIN_TIMEOUT;
                while (connectionsFinalized != 3) {
                    // Timeout!!
                    if (timeoutIn < System.currentTimeMillis()) {
                        System.out.println("Timed out.");
                        break;
                    }
                    // Fetching I/O
                    selector.select(100);
                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> iter = selectedKeys.iterator();
                    while (iter.hasNext()) {
                        SelectionKey key = iter.next();
                        iter.remove();
                        // We only receive 3 connections
                        if (key.isAcceptable() && connectionsReceived < 3) {
                            ServerUtils.registerTCPSocket(selector, serverSocket, SelectionKey.OP_READ, null);
                            connectionsReceived++;
                            timeoutIn = System.currentTimeMillis() + ServerUtils.JOIN_TIMEOUT;
                        }
                        if (key.isReadable()) {
                            try {
                                if (ServerUtils.receiveJoinRespMsg(buffer, key, active_nodes, logs, lastLogs)) {
                                    connectionsFinalized++;

                                    // DEBUG
                                    System.out.println("REACHED");
                                    System.out.println(logs);
                                    System.out.println("------------");
                                    System.out.println(lastLogs);
                                }
                            } catch (Exception e) {
                                // TODO: handle exception
                            }
                            // Reseting timeout
                            timeoutIn = System.currentTimeMillis() + ServerUtils.JOIN_TIMEOUT;
                        }
                    }
                }
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (Exception e2) {
                e2.printStackTrace();
            }

            // Exiting join()
            if (connectionsFinalized == 3)
                break;

            if (i != ServerUtils.JOIN_TRIES - 1)
                // Sending join
                try {
                    ServerUtils.sendJoinOrLeaveMsg(ServerUtils.JOIN_MSG, ip_mcast_addr, ip_mcast_port, node_id,
                            membership_port);
                } catch (IOException e) {
                    e.printStackTrace();
                }
        }
        // Closing the TCP socket
        try {
            selector.close();
            serverSocket.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        // If we are the first node in the cluster we add ourself to the logs
        if (connectionsFinalized == 0) {
            var selfLog = new Log(node_id, this.membership_port);
            var selfLabel = new ServerLabel(selfLog);
            logs.put(node_id, selfLog);
            lastLogs.add(selfLog);
            active_nodes.addServer(selfLabel);
        }

        System.out.println(active_nodes);
        System.err.println("1----------------------------------1");
        System.out.println(logs);
        System.err.println("1----------------------------------1");
        System.out.println(lastLogs);
        isActive = true;
    }

}