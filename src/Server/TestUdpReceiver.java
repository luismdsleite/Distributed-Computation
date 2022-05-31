package Server;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class TestUdpReceiver {
    public static void main(String[] args) {
        var executor = Executors.newFixedThreadPool(4);
        Future<List<String>> futureMsg;
        try {
            futureMsg = executor.submit(new UdpReceive("224.0.0.0", "3456"));
            System.out.println("Awaiting msg");
            List<String> msg = futureMsg.get();
            System.out.println(msg);
        } catch (Exception e) {
            executor.shutdown();
            e.printStackTrace();
        }
        executor.shutdown();
        System.out.println("ended");
    }
}
