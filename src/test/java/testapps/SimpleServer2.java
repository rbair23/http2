package testapps;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Locale;

@SuppressWarnings("StringConcatenationInsideStringBufferAppend")
public class SimpleServer2 {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
    private final Selector selector;
    private final ServerSocketChannel serverChannel;
    private long connectionsNum;
    private long totalConnectionsNum;
    private static final long MAX_ACCEPTED_CONNECTIONS = 256;
    private long acceptedConnections = 0;

    public static void main(String[] args) throws Exception {
        SimpleServer2 server = new SimpleServer2();
        server.start();
    }

    public SimpleServer2() throws Exception {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.setOption(StandardSocketOptions.SO_REUSEPORT, true);
        serverChannel.socket().bind(new InetSocketAddress((InetAddress) null, 54321),1000);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    }

//    private void close(SelectionKey key) {
//        Keep-Alive
//    }

    private void start() throws Exception {
        while(true) {
//            System.out.println("======================================================================================");
//            selector.select();
            selector.selectNow(key -> {
                        try {
                            Context context = (Context) key.attachment();
                            if (context != null && context.writeFinished) {
                                key.cancel();
                                key.channel().close();
                                    connectionsNum--;
                                    acceptedConnections--;
                                return;

//
//                                // CLOSE CHANNEL
////        System.out.println("Closing connection for channel: " + channel + ", active connections: " + connectionsNum);
//                                try {
//                                    key.cancel();
//                                    System.out.println("clientChannel.getClass().getName() = " + clientChannel.getClass().getName());
//                                    clientChannel.shutdownInput();
//                                    clientChannel.shutdownOutput();
//                                    clientChannel.close();
//                                    clientChannel.socket().close();
//                                } catch (IOException e) {
//                                    System.err.println("Error during closing channel: " + clientChannel);
//                                    e.printStackTrace();
//                                } finally {
//                                    connectionsNum--;
//                                    acceptedConnections--;
//                                }
                            }

                            if (key.isValid()) {
                                if (key.isAcceptable()) {
                                    accept();
                                } else if (key.isReadable()) {
                                    read(key);
                                } else if (key.isWritable()) {
                                    write(key);
                                }
                            } else {
                                System.err.println("Invalid Key");
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            System.exit(1);
                        }
                    });
//            Set<SelectionKey> keys = selector.selectedKeys();
//
//            final Iterator<SelectionKey> keyIterator = keys.iterator();
//            while (keyIterator.hasNext()) {
//                SelectionKey key = keyIterator.next();
//                keyIterator.remove();
//                try {
//                    if (key.isValid()) {
//                        if (key.isAcceptable()) {
//                            accept();
//                        } else if (key.isReadable()) {
//                            read(key);
//                        } else if (key.isWritable()) {
//                            write(key);
//                        }
//                    } else {
//                        System.err.println("Invalid Key");
//                    }
//                } catch (Exception e) {
//                    e.printStackTrace();
//                    System.exit(1);
//                }
//            }
        }
    }

    private void accept() throws IOException {
        if (acceptedConnections < MAX_ACCEPTED_CONNECTIONS) {
            SocketChannel clientChannel = serverChannel.accept();
            if (clientChannel == null) {
                System.out.println("No connection is available. Skipping selection key");
            } else {
                acceptedConnections++;
                System.out.println("Accept acceptedConnections="+acceptedConnections);
                clientChannel.socket().setTcpNoDelay(true);
                clientChannel.socket().setKeepAlive(false);
                clientChannel.socket().setSoTimeout(0);
                clientChannel.socket().setSoLinger(false,-1);
                clientChannel.configureBlocking(false);
                clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            }
        } else {
            System.out.println("!======= NOT ACCEPTING, FULL");
        }
    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();

        Context context = (Context)key.attachment();
        if (context == null) {
            context = new Context(connectionsNum++);
            totalConnectionsNum ++;
            System.out.println("Got new connection handler for channel: " + clientChannel+ ", connection #: " + context.connectionNo+", totalConnectionsNum: "+totalConnectionsNum);
            key.attach(context);
            return;
        }

        if (!context.readFinished) {
            // read all input
            final StringBuilder sb = new StringBuilder();
            final ByteBuffer readBuffer = ByteBuffer.allocate(1024);
            int read;
            while ((read = clientChannel.read(readBuffer)) > 0) {
                readBuffer.flip();
                byte[] bytes = new byte[readBuffer.limit()];
                readBuffer.get(bytes);
                sb.append(new String(bytes));
                readBuffer.clear();
            }
            if (read < 0) throw new IOException("End of input stream. Connection is closed by the client");
//        System.out.println("REQUEST CONNECTION["+connectionNum+"]: "+sb.toString().replaceAll("[\r\n]+"," | "));

            // switch to write mode
            context.keepAlive = sb.toString().matches("(?i)Keep-Alive");
            System.out.println("context.keepAlive = " + context.keepAlive);
            context.readFinished = true;
    //        key.interestOps(SelectionKey.OP_WRITE);
//            clientChannel.shutdownInput();
        }
    }

    private void write(SelectionKey key) throws IOException {
        final Context context = (Context)key.attachment();
        if (context != null && context.readFinished) {
            SocketChannel clientChannel = (SocketChannel) key.channel();

            String content = "Hello There!";
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 200 OK\r\n");
//            sb.append("Date: " + DATE_FORMAT.format(new Date()) + "\r\n");
            sb.append("Server: SimpleServer\r\n");
            sb.append("Connection: closeSilently\r\n");
            sb.append("Content-Type: text/plain\r\n");
            sb.append("Content-Length: " + content.length() + "\r\n");
            sb.append("\r\n");
            sb.append(content);

            ByteBuffer buf = ByteBuffer.allocate(sb.length());
            buf.put(sb.toString().getBytes(StandardCharsets.US_ASCII));
            clientChannel.write(buf.flip());

            context.writeFinished = true;
//
//            // CLOSE CHANNEL
////        System.out.println("Closing connection for channel: " + channel + ", active connections: " + connectionsNum);
//            try {
//                key.cancel();
//                System.out.println("clientChannel.getClass().getName() = " + clientChannel.getClass().getName());
//                clientChannel.shutdownInput();
//                clientChannel.shutdownOutput();
//                clientChannel.close();
//                clientChannel.socket().close();
//            } catch (IOException e) {
//                System.err.println("Error during closing channel: " + clientChannel);
//                e.printStackTrace();
//            } finally {
//                connectionsNum--;
//                acceptedConnections--;
//            }
//        } else {
//            System.out.println("!");
        }
    }

    public class Context {
        final long connectionNo;
        boolean readFinished = false;
        boolean writeFinished = false;
        boolean keepAlive = false;

        public Context(long connectionNo) {
            this.connectionNo = connectionNo;
        }
    }

}
