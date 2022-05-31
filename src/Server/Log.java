package Server;

import java.nio.ByteBuffer;

/**
 * Used to encode membership events about a node. The {@link #getCount()} is
 * increased by 1 each time a node joins or leave the cluster.
 */
public class Log {
    // Serializing this object costs this many bytes
    static final int LOG_BYTE_SIZE = 4 + Integer.BYTES * 2;
    // node_id is saved in bytes instead of a String so that its size always
    // remains constant.
    private final byte[] node_id;
    private int port;
    private int count;

    Log(String node_id, int port, int count) {
        this.node_id = new byte[4];
        // Setting IPV4 Address
        var ipv4Str = node_id.split("\\.");
        for (int i = 0; i < 4; i++) {
            this.node_id[i] = Integer.valueOf(Integer.parseInt(ipv4Str[i])).byteValue();
        }
        this.port = port;
        this.count = count;
    }

    Log(String node_id, int port) {
        this(node_id, port, 0);
    }

    Log(byte[] node_id, int port, int count) {
        this.node_id = node_id;
        this.port = port;
        this.count = count;
    }

    Log(byte[] node_id, int port) {
        this(node_id, port, 0);
    }

    public void addCount() {
        this.count++;
    }

    public byte[] getNodeID() {
        return node_id.clone();
    }

    public String getNodeIDstr() {
        String str = "";
        for (int i = 0; i < 3; i++) {
            str += Integer.toString(Byte.toUnsignedInt(node_id[i])) + ".";
        }
        str += Integer.toString(Byte.toUnsignedInt(node_id[3]));
        return str;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public boolean isActive() {
        return count % 2 == 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Log) {
            var o = (Log) obj;
            var node_id2 = o.getNodeID();
            for (int i = 0; i < 4; i++) {
                if (this.node_id[i] != node_id2[i])
                    return false;
            }
            return true;
        }
        return super.equals(obj);
    }

    @Override
    public String toString() {
        return getNodeIDstr() + ":" + this.port + "=" + this.count;
    }

    /**
     * Creates a Log from a ByteBuffer.
     * <p>
     * No checking is made to see if the buffer has enough bytes.
     * 
     * @param buff
     * @param log
     * @param includeCounter
     */
    public static Log logDeserializer(ByteBuffer buffer, boolean includeCounter) {
        byte[] id = new byte[4];
        for (int j = 0; j < 4; j++) {
            id[j] = buffer.get();
        }
        var port = buffer.getInt();
        if (includeCounter)
            return new Log(id, port, buffer.getInt());
        return new Log(id, port);
    }

    public static Log logDeserializer(ByteBuffer buffer) {
        return logDeserializer(buffer, true);
    }

    /**
     * Writes a Log into a ByteBuffer.
     * <p>
     * No checking is made to see if the buffer has enough bytes
     * 
     * @param buff
     * @param log
     * @param includeCounter
     */
    public static void logSerializer(ByteBuffer buff, Log log, boolean includeCounter) {
        buff.put(log.getNodeID());
        buff.putInt(log.getPort());
        if (includeCounter)
            buff.putInt(log.getCount());
    }

    public static void logSerializer(ByteBuffer buff, Log log) {
        logSerializer(buff, log, true);
    }
}
