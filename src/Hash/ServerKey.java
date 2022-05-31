package Hash;

/**
 * Wrapper Class for byte[].
 * Key Object used for {@link ServerTree}, assumes a byte[] SHA-256 hash and
 * makes it comparable.
 */
public class ServerKey implements Comparable<ServerKey> {
    private final byte[] hash;

    public ServerKey(byte[] hash) {
        this.hash = hash;
    }

    public byte[] getHash() {
        return hash;
    }

    @Override
    public int compareTo(ServerKey o) {
        // SHA-256 bits = 32 bytes
        for (int i = 0; i < 32; i++) {
            if (this.hash[i] > o.hash[i]) {
                return 1;
            } else if (this.hash[i] < o.hash[i]) {
                return -1;
            } else
                continue;
        }
        return 0;
    }

}
