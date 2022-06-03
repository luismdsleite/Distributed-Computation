package Client;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.rmi.*;
import java.util.Scanner;

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
    var clientPort = 6660;
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
        ServerSocket serverSocket = new ServerSocket(clientPort, 10);
        // criar socket e enviar dados
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
        dOut.flush();
        socket.close();

        if (op.equals("put")) {
          // wait for tcp connection
          try {
            Socket fileSocket = serverSocket.accept();
            // Read Local File
            try {
              byte[] data = Files.readAllBytes(Paths.get(opnd));

              OutputStream outFileSocket = fileSocket.getOutputStream();
              DataOutputStream dos = new DataOutputStream(outFileSocket);

              dos.writeInt(data.length);
              if (data.length > 0) {
                dos.write(data, 0, data.length);
              }
              outFileSocket.close();
            } catch (FileNotFoundException e) {
              System.out.println("An error occurred.");
              e.printStackTrace();
            }
            serverSocket.close();

          } catch (IOException e1) {
            e1.printStackTrace();
          }
        }

        if (op.equals("get")) {
          try {
            Socket fileSocket = serverSocket.accept();
            BufferedReader in = new BufferedReader(new InputStreamReader(fileSocket.getInputStream()));

            String fileName = in.readLine();
            System.out.println("Received file: " + fileName);
            serverSocket.close();
          } catch (IOException e1) {
            e1.printStackTrace();

          }
        }

        if (op.equals("delete"))
          try {
            serverSocket.accept();
            serverSocket.close();
          } catch (IOException e1) {
            e1.printStackTrace();
          }

        break;

      default:
        throw new Exception("" + args[1] + " is not a valid operation");
    }
    System.out.println("Terminated Connection");
  }

}
