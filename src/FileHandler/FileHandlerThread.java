package FileHandler;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannel;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.Pipe.SourceChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.nio.file.StandardOpenOption;

import Hash.ServerKey;
import Hash.ServerLabel;
import Server.ServerUtils;

public class FileHandlerThread implements Runnable {
    SourceChannel inChannel;
    private int storePort;
    private String filePath;

    public FileHandlerThread(SourceChannel inChannel, int storePort, String filePath) throws IOException {
        this.inChannel = inChannel;
        inChannel.configureBlocking(true);
        this.storePort = storePort;
        this.filePath = filePath;
        // Creating the folder where all files will be stored
        Files.createDirectories(Paths.get(this.filePath));
    }

    @Override
    public void run() {
        ByteBuffer buffer = ByteBuffer.allocate(512);
        int bRead = 0;
        while (true) {
            try {
                bRead = inChannel.read(buffer);
                buffer.flip();
                if (bRead == -1) {
                    break;
                }
            } catch (IOException e) {
                e.printStackTrace();
                continue;
            }
            var msg = new String(buffer.array());
            // Msg Code, Client IP, File Name
            var parsedMsg = msg.split(" ");
            var msgCode = parsedMsg[0].charAt(0);
            var clientIP = parsedMsg[1];
            var fileName = filePath + hashStr(parsedMsg[2]);

            try {
                final Socket tcpSocket = new Socket(clientIP, 6660);
                while (!tcpSocket.isBound())
                    ;
                final var outStream = tcpSocket.getOutputStream();
                final InputStream in = tcpSocket.getInputStream();

                switch (msgCode) {
                    case ServerUtils.PUT_MSG:
                        try {
                            AsynchronousFileChannel fileChannel = AsynchronousFileChannel.open(Paths.get(fileName),
                                    StandardOpenOption.WRITE, StandardOpenOption.CREATE);

                            DataInputStream dis = new DataInputStream(in);
                            int len = dis.readInt();
                            byte[] data = new byte[len];

                            if (len > 0) {
                                dis.readFully(data);
                            }

                            ByteBuffer fileBuffer = ByteBuffer.allocate(data.length);
                            fileBuffer.put(data);
                            long position = 0;
                            fileBuffer.flip();
                            fileChannel.write(fileBuffer, position, buffer, new CompletionHandler<Integer, Object>() {
                                @Override
                                public void completed(Integer result, Object attachment) {
                                    // TODO Auto-generated method stub
                                    try {
                                        tcpSocket.close();
                                        outStream.close();
                                    } catch (IOException e) {
                                        // TODO Auto-generated catch block
                                        e.printStackTrace();
                                    }
                                }

                                @Override
                                public void failed(Throwable exc, Object attachment) {
                                    try {
                                        tcpSocket.close();
                                        outStream.close();
                                    } catch (IOException e) {
                                        // TODO Auto-generated catch block
                                        e.printStackTrace();
                                    }
                                }
                            });
                        } catch (IOException e) {
                            System.out.println(e.toString());
                        }

                        break;
                    case ServerUtils.GET_MSG:
                        System.out.println("RECEIVED GET");
                        try {
                            AsynchronousFileChannel fileChannel = AsynchronousFileChannel.open(Paths.get(fileName),
                                    StandardOpenOption.READ);

                            final int fileSize = (int) Files.size(Paths.get(fileName));
                            ByteBuffer fileBuffer = ByteBuffer.allocate(fileSize);

                            CompletionHandler<Integer, ByteBuffer> getHandler = new CompletionHandler<Integer, ByteBuffer>() {
                                @Override
                                public void completed(Integer result, ByteBuffer attachment) {
                                    try {
                                        ByteBuffer supp = ByteBuffer.allocate(Integer.BYTES);
                                        supp.putInt(fileSize);
                                        supp.flip();
                                        outStream.write(supp.array());
                                        outStream.write(fileBuffer.array());
                                        tcpSocket.close();
                                        outStream.close();
                                    } catch (IOException e) {
                                        // TODO Auto-generated catch block
                                        e.printStackTrace();
                                    }
                                }

                                @Override
                                public void failed(Throwable exc, ByteBuffer attachment) {
                                    try {
                                        System.out.println("Failed");
                                        tcpSocket.close();
                                        outStream.close();
                                    } catch (IOException e) {
                                        // TODO Auto-generated catch block
                                        e.printStackTrace();
                                    }
                                }
                            };

                            fileChannel.read(fileBuffer, 0, fileBuffer, getHandler);

                        } catch (IOException e) {
                            System.out.println(e.toString());
                        }

                        break;
                    case ServerUtils.DEL_MSG:
                        try {
                            Files.delete(Paths.get(fileName));
                            outStream.write("File Deleted".getBytes());
                        } catch (IOException e) {
                            System.out.println(e.toString());
                            outStream.write("File Deleted".getBytes());
                        }
                        tcpSocket.close();
                        outStream.close();
                        break;

                }

            } catch (IOException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
            }

            System.out.println("Thread Received: " + Arrays.asList(parsedMsg));
            System.out.println(fileName);
            buffer.clear();
        }
    }

    private String hashStr(String in) {
        return ServerLabel.bytesToHex(new ServerKey(ServerLabel.hashString(in)));
    }
}
