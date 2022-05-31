package Client;

import java.net.MalformedURLException;
import java.rmi.*;
import Membership.MemberShipUtils;

public class Client {
  /**
   * 
   * @param args args[0] <node_id>
   * @param args args[1] <operation>
   * @param args args[2] [<opnd>]
   * @throws Exception
   */
  public static void main(String args[]) throws Exception {

    // Membership:
    System.out.println("Connecting to node " + args[0]);

    switch (args[1]) {
      case "join":
      case "leave":
        try {
          var stub = MemberShipUtils.connectViaRMI(args[0], "membership");
          if (args[1].equals("join"))
            stub.join();
          else
            stub.leave();
        } catch (MalformedURLException | RemoteException | NotBoundException e) {
          e.printStackTrace();
          return;
        }
        break;
      case "put":

        break;
      case "get":

        break;
      case "delete":

        break;

      default:
        throw new Exception("" + args[1] + " is not a valid operation");
    }
    System.out.println("Terminated Connection");
  }

}
