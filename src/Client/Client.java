package Client;

import java.io.DataOutputStream;
import java.net.MalformedURLException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.rmi.*;
import Membership.MemberShipUtils;

public class Client {
  /**
   * 
   * @param args args[0] <node_id>
   * @param args args[1] <port>
   * @param args args[2] <operation>
   * @param args args[3] [<opnd>]
   * @throws Exception
   */
  public static void main(String args[]) throws Exception {
    var clientIP = "127.0.0.66";
    // Membership:
    System.out.println("Connecting to node " + args[0]);
    var node_id = args[0];
    var port = Integer.parseInt(args[1]);
    var op = args[2];

    switch (op) {
      case "join":
      case "leave":
        try {
          var stub = MemberShipUtils.connectViaRMI(node_id, "membership");
          if (op.equals("join"))
            stub.join();
          else
            stub.leave();
        } catch (MalformedURLException | RemoteException | NotBoundException e) {
          e.printStackTrace();
          return;
        }
        break;
      case "put":
      case "get":
      case "delete":
        var opnd = args[3];
        Socket socket = new Socket(node_id, port);
        DataOutputStream dOut = new DataOutputStream(socket.getOutputStream());
        var msg = ("" + clientIP + " " + opnd).getBytes();
        ByteBuffer out = ByteBuffer.allocate(512);
        out.putChar(op.charAt(0));
        out.putInt(msg.length);
        out.put(msg);
        out.flip();
        dOut.write(out.array(), 0, out.limit());
        break;

      default:
        throw new Exception("" + args[1] + " is not a valid operation");
    }
    System.out.println("Terminated Connection");
  }

}
