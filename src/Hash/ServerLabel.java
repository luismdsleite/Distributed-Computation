package Hash;

import Server.Log;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Used to know which nodes get what key ranges
 */
public class ServerLabel {

    public static MessageDigest digest;
    static {
        String algorithm = "SHA-256";
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException | NullPointerException e) {
            e.printStackTrace();
            System.err.println("Algorithm " + algorithm + " was not found");
        }
    }

    private final String node_id;
    private final int port;

    public ServerLabel(Log server) {
        this.node_id = server.getNodeIDstr();
        this.port = server.getPort();
    }

    public int getPort() {
        return port;
    }

    public String getNodeID() {
        return node_id;
    }

    public ServerLabel getServer() {
        return this;
    }

    public ServerKey getRangeStart() {
        return new ServerKey(digest.digest(node_id.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public String toString() {
        return "(node ='" + node_id + '\'' +
                ", hash=" + bytesToHex(this.getRangeStart()) +
                ')';
    }

    public static String bytesToHex(ServerKey key) {
        var hash = key.getHash();
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (int i = 0; i < hash.length; i++) {
            String hex = Integer.toHexString(0xff & hash[i]);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    public static byte[] hashString(String in){
        return ServerLabel.digest.digest(in.getBytes());
    }
}