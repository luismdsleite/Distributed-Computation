package Server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.NetworkInterface;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
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
 * @param ip_mcast_addr IP of the multicast group (UDP)
 * @param ip_mcast_port Port of the multicast group (UDP)
 * @param node_id       IP address of the node (Or a unique id in case it is
 *                      tested in a localhost)
 * @param store_port    TCP Port used to store
 * @throws RemoteException
 */
public class Server extends UnicastRemoteObject implements MembershipInterface {

    private final String ip_mcast_addr;
    private final int ip_mcast_port;
    private final int store_port;
    private final String node_id;
    // Nodes who according to the logs are still active
    private final ServerTree active_nodes;
    // All the events that happened in the cluster
    private final Map<String, Log> logs;
    // Used as a queue, contains the last 32 logs received
    private final LinkedList<Log> lastLogs;

    // Pipes used to receive join and leave commands through RMI
    private final SinkChannel writeRMI;
    private final SourceChannel readRMI;

    // Pipes used to receive join and leave commands through RMI
    private final SinkChannel writeFileHandler;
    private final SourceChannel readFileHandler;

    // Used to delay sendJoinRespMsg()
    private final Random random;

    private Selector selector;
    private boolean isActive;

    public Server(String ip_mcast_addr, String ip_mcast_port, String node_id, String store_port)
            throws IOException {
        // super();
        this.ip_mcast_addr = ip_mcast_addr;
        this.ip_mcast_port = Integer.parseInt(ip_mcast_port);
        this.store_port = Integer.parseInt(store_port);
        this.node_id = node_id;
        this.active_nodes = new ServerTree();
        this.logs = new HashMap<>();
        this.lastLogs = new LinkedList<>();
        this.random = new Random();
        this.isActive = false;
        // Adding ourselfs in case we are the first node of the cluster
        var selfLog = new Log(node_id, this.store_port);
        var selfLabel = new ServerLabel(selfLog);
        logs.put(node_id, selfLog);
        lastLogs.add(selfLog);
        active_nodes.addServer(selfLabel);

        // Pipe used to read commands send through RMI
        Pipe RMIPipe = Pipe.open();
        writeRMI = RMIPipe.sink();
        readRMI = RMIPipe.source();

        // Pipe used to redirect put(),get(),delete() msg
        Pipe FileHandlerPipe = Pipe.open();
        writeFileHandler = FileHandlerPipe.sink();
        readFileHandler = FileHandlerPipe.source();

        // ----------------------RMI---------------------
        try {
            MemberShipUtils.startRMI(this, node_id, "membership");
        } catch (MalformedURLException e) {
            e.printStackTrace();
            System.out.println("Invalid Node ID");
        }
        // ----------------------RMI---------------------
        System.out.println("Started RMI in " + node_id);

        // // TODO: DEBUG REMEMBER TO REMOVE
        // var log = new Log("127.0.0.66", this.store_port);
        // var label = new ServerLabel(log);
        // logs.put(log.getNodeIDstr(), log);
        // lastLogs.add(log);
        // active_nodes.addServer(label);

        // Blocking till we receive a join
        readRMI.read(ByteBuffer.allocate(100));

        System.out.println("Started Server");
        execute();
    }

    /**
     * Starts a server
     * 
     * @param args {@link #ip_mcast_addr}
     * @param args {@linkplain #ip_mcast_port}
     * @param args {@link #node_id}
     * @param args {@link #store_port}
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
     */
    private void execute() {
        // Opening TCP ServerSocket
        ServerSocketChannel serverSocket;
        DatagramChannel datagramChannel;
        try {
            // ---------------------------- TCP --------------------------
            // Opening a Server Socket and awaiting connections
            selector = Selector.open();
            serverSocket = ServerSocketChannel.open();
            serverSocket.bind(new InetSocketAddress(node_id, store_port));
            serverSocket.configureBlocking(false);
            serverSocket.register(selector, SelectionKey.OP_ACCEPT, new ArrayList<>());
            // ---------------------------- TCP --------------------------

            // ---------------------------- UDP --------------------------
            // Opening UDP Multicast and awaiting connections
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
            while (true) {

                selector.select(); // This blocks while no event is received
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iter = selectedKeys.iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();
                    // TCP ServerSocket
                    if (key.isAcceptable()) {
                        ServerUtils.registerTCPSocket(selector, serverSocket, SelectionKey.OP_READ,
                                new ArrayList<>(Arrays.asList(ServerUtils.TCP_SOCKET)));
                    }
                    // Multicast membership messages, Store messages
                    if (key.isReadable()) {
                        @SuppressWarnings("unchecked")
                        var att = (List<Object>) key.attachment();
                        var id = (int) att.get(0);
                        switch (id) {
                            // leave()
                            case ServerUtils.RMI_PIPE:
                                ServerUtils.sendJoinOrLeaveMsg(ServerUtils.LEAVE_MSG, ip_mcast_addr, ip_mcast_port,
                                        node_id, store_port);
                                System.out.println("RECEIVEDDD!!!!----");
                            case ServerUtils.TCP_SOCKET:
                                break;
                            case ServerUtils.UDP_MULTICAST:
                                System.out.println("Received UDP");
                                // Getting join/leave msg
                                var request = ServerUtils.receiveJoinOrLeaveMsg(buffer, key);
                                var mode = request.get(0).charAt(0);
                                var host = request.get(1);
                                var port = Integer.parseInt(request.get(2));
                                ServerUtils.mergeLog(mode, host, port, active_nodes, logs, lastLogs);
                                switch (mode) {
                                    // Connecting through tcp to send active_nodes and the last 32 logs
                                    case ServerUtils.JOIN_MSG:
                                        long executeIn = random.nextInt(ServerUtils.MAX_JOIN_RESP_DELAY + 1)
                                                + System.currentTimeMillis();
                                        ServerUtils.connectAndRegisterTCPSocket(selector, host, port,
                                                SelectionKey.OP_WRITE,
                                                new ArrayList<>(Arrays.asList(ServerUtils.JOIN_RESP_MSG, executeIn)));
                                        break;
                                    case ServerUtils.LEAVE_MSG:

                                        break;
                                    default:
                                        break;
                                }
                                break;

                            default:
                                break;
                        }
                    }
                    // Join Response, Sink Channel or Multicast Log messages
                    if (key.isWritable()) {
                        @SuppressWarnings("unchecked")
                        var att = (List<Object>) key.attachment();
                        var mode = (char) att.get(0);
                        switch (mode) {
                            case ServerUtils.JOIN_RESP_MSG:
                                // If its not the first time this key was called
                                if (att.size() != 2) {
                                    ServerUtils.sendJoinRespMsg(key, active_nodes, lastLogs);
                                }
                                // In the first call Join Response is only executed after a random time in order
                                // to avoid flooding
                                else if ((long) att.get(1) < System.currentTimeMillis()) {
                                    System.out.println("Join Response");
                                    att.remove(1);
                                    ServerUtils.sendJoinRespMsg(key, active_nodes, lastLogs);
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
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        System.out.println("Server was closed");
    }

    /**
     * Joins a cluster.
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
    @Override
    public void join() throws RemoteException {
        if (isActive) {
            System.err.println("Server is already active");
            return;
        }
        // Opening TCP ServerSocket and sending a join message via multicast
        Selector selector;
        ServerSocketChannel serverSocket;
        try {
            selector = Selector.open();
            serverSocket = ServerSocketChannel.open();
            serverSocket.bind(new InetSocketAddress(node_id, store_port));
            serverSocket.configureBlocking(false);
            serverSocket.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        System.out.println("Opened TCP Port at " + store_port);
        int connectionsFinalized = 0;
        // Sending a UDP Multicast Message and awaiting reply via TCP
        for (int i = 0; i < ServerUtils.JOIN_TRIES; i++) {
            int connectionsReceived = 0;
            try {
                // Sending join
                ServerUtils.sendJoinOrLeaveMsg(ServerUtils.JOIN_MSG, ip_mcast_addr, ip_mcast_port, node_id, store_port);
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
                            if (ServerUtils.receiveJoinRespMsg(buffer, key, active_nodes, logs, lastLogs)) {
                                connectionsFinalized++;
                            }
                            // Reseting timeout
                            timeoutIn = System.currentTimeMillis() + ServerUtils.JOIN_TIMEOUT;
                        }
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (Exception e2) {
                e2.printStackTrace();
            }
            if (connectionsFinalized == 3)
                break;
        }
        // Closing the TCP socket
        try {
            selector.close();
            serverSocket.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        System.out.println(active_nodes);
        System.err.println("1----------------------------------1");
        System.out.println(logs);
        System.err.println("1----------------------------------1");
        System.out.println(lastLogs);
        isActive = true;
        try {
            writeRMI.write(ByteBuffer.allocate(100).putChar('j'));
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    @Override
    public void leave() throws RemoteException {
        if (!isActive) {
            System.err.println("Server is not active!");
            return;
        }
        
        try {
            readRMI.configureBlocking(false);
            readRMI.register(selector, SelectionKey.OP_READ, new ArrayList<>(Arrays.asList(ServerUtils.RMI_PIPE)));
            writeRMI.write(ByteBuffer.allocate(100).putChar('l'));
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        
        System.out.println("Leave()");
    }

}