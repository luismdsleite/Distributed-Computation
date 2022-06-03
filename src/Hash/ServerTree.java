package Hash;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map.Entry;

public class ServerTree {

    private final TreeMap<ServerKey, ServerLabel> serverTree;

    public ServerTree() {
        this.serverTree = new TreeMap<>();
    }

    public TreeMap<ServerKey, ServerLabel> getTree() {
        return serverTree;
    }

    public boolean isEmpty() {
        return serverTree.isEmpty();
    }

    public void addServer(ServerLabel server) {
        serverTree.put(server.getRangeStart(), server);
    }

    public void removeServer(ServerLabel server) {
        serverTree.remove(server.getRangeStart());
    }

    public boolean containsServer(ServerLabel server) {
        return serverTree.containsKey(server.getRangeStart());
    }

    public ServerLabel getServer(ServerKey hash) {

        Map.Entry<ServerKey, ServerLabel> entry = serverTree.lowerEntry(hash);
        if (entry == null)
            entry = serverTree.lastEntry();

        return entry.getValue().getServer();
    }

    public Set<ServerKey> getKeys() {
        return serverTree.keySet();
    }

    public Set<Entry<ServerKey, ServerLabel>> getEntrySet() {
        return serverTree.entrySet();
    }

    @Override
    public String toString() {

        StringBuilder sb = new StringBuilder("Server tree: ");
        serverTree.forEach((integer, serverLabel) -> {
            sb.append(serverLabel).append(" ");
        });

        return sb.toString();
    }
}