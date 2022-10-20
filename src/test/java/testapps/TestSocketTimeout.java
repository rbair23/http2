package testapps;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Locale;

public class TestSocketTimeout {
    private final Selector selector;
    private final ServerSocketChannel serverChannel;
    private long connectionsNum;
    private long totalConnectionsNum;
    private static final long KEEP_ALIVE_TIMEOUT_MS = 10_000;
    private static final long MAX_ACCEPTED_CONNECTIONS = 256;
    private long acceptedConnections = 0;

    public static void main(String[] args) throws Exception {
        TestSocketTimeout server = new TestSocketTimeout();
        Thread serverThread = new Thread(server::start);
        serverThread.start();
        // give server a chance to start
        System.out.println("sleeping 3s");
        Thread.sleep(3000);
        System.out.println("starting client");
        Socket client = new Socket("127.0.0.1",54321);
        client.setSoTimeout(60_000);
        System.out.println("client.isConnected() = " + client.isConnected());
        final var out = client.getOutputStream();
        final var in = client.getInputStream();
//        out.write("HTTP".getBytes());
        System.out.println("just waiting now to see what happens");
        System.out.println(in.read());
        System.out.println("read finished");
        Thread.sleep(30000);
        System.out.println("more than 30s passed");
        Thread.sleep(30000);
        System.out.println("more than 60s passed");
    }

    public TestSocketTimeout() throws Exception {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.setOption(StandardSocketOptions.SO_REUSEPORT, true);
        serverChannel.socket().bind(new InetSocketAddress((InetAddress) null, 54321),1000);
        serverChannel.socket().setSoTimeout(1000);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    }

    private void close(SelectionKey key) {
        System.out.println("TestSocketTimeout.close");
        new Exception().printStackTrace();
        // CLOSE CHANNEL
        try {
            key.cancel(); // TODO test if needed
            key.channel().close();
        } catch (IOException e) {
            System.err.println("Error during closing SelectionKey: " + key);
            e.printStackTrace();
        } finally {
            connectionsNum--;
            acceptedConnections--;
            System.out.println("Closing connection, active connections: " + connectionsNum);
        }
    }

    private void start() {
        try {
            while (true) {
//            System.out.println("======================================================================================");
//            selector.select();
                selector.select(key -> {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    try {
                        TestSocketTimeout.Context context = (TestSocketTimeout.Context) key.attachment();
                        System.out.print("time = "+ Instant.now() +" -- context = "+context+"\r");

                        // create a new context
                        if (context == null && key.isValid() && (key.isReadable() || key.isWritable())) {
                            context = new TestSocketTimeout.Context(connectionsNum++);
                            totalConnectionsNum++;
                            System.out.println("Got new connection handler for key: " + key + ", connection #: " + context.connectionNo + ", totalConnectionsNum: " + totalConnectionsNum);
                            key.attach(context);
                        }

//                        System.out.println("-- KEY["+((context == null) ? "NEW" : context.connectionNo) +
//                                "] valid ="+key.isValid()+", acceptable="+key.isAcceptable()+", read="+key.isReadable()+", write="+key.isWritable());
                        if (context != null && context.isTimeout()) {
                            close(key);
                            return;
                        }
                        if (context != null && context.writeFinished) {
                            if (!context.keepAlive) {
                                close(key);
                                return;
                            } else {
                                context.readFinished = false;
                                context.writeFinished = false;
                                context.keepAlive = false;
                            }
                        }
                        if (key.isValid()) {
                            if (key.isAcceptable()) {
                                accept(key);
                            } else if (key.isReadable()) {
                                read(key);
                            } else if (key.isWritable()) { // TODO test if needed
                                write(key);
                            }
                        } else {
                            System.err.println("Invalid Key");
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        System.exit(1);
                    }
                }, 1000);
            }
        } catch (Exception e) {
            System.err.println("SERVER ERROR");
            e.printStackTrace();
        }
    }

    private void accept(SelectionKey key) throws IOException {
        if (acceptedConnections < MAX_ACCEPTED_CONNECTIONS) {
            SocketChannel clientChannel = serverChannel.accept();
            if (clientChannel == null) {
                System.out.println("No connection is available. Skipping selection key");
            } else {
                acceptedConnections++;
                System.out.println("Accept acceptedConnections="+acceptedConnections);
                clientChannel.socket().setTcpNoDelay(true);
                clientChannel.socket().setKeepAlive(false);
                clientChannel.socket().setSoTimeout(1000);
                clientChannel.socket().setSoLinger(false,-1);
                clientChannel.configureBlocking(false);
                clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            }
        } else {
            System.out.println("!======= NOT ACCEPTING, FULL");
        }
    }

    private void read(SelectionKey key) throws IOException {
        final SocketChannel clientChannel = (SocketChannel) key.channel();
        TestSocketTimeout.Context context = (TestSocketTimeout.Context)key.attachment();

        if (context != null && !context.readFinished) {
            try {
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
                if (read < 0) {
                    System.out.println("End of input stream. Connection is closed by the client");
                    close(key);
                    return;
                }
                System.out.println("REQUEST CONNECTION["+context.connectionNo+"]: "+sb.toString().replaceAll("[\r\n]+"," | "));

                // switch to write mode
                context.keepAlive = sb.toString().toLowerCase().contains("keep-alive");
                context.readFinished = true;
                System.out.println("context.readFinished = " + context.readFinished);
            } catch (SocketException se) {
                se.printStackTrace();
                close(key);
            }
        }
    }

    private void write(SelectionKey key) throws IOException {
        final TestSocketTimeout.Context context = (TestSocketTimeout.Context)key.attachment();
        if (context != null && context.readFinished) {
            SocketChannel clientChannel = (SocketChannel) key.channel();

            String content = "Hello There!";
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 200 OK\r\n");
//            sb.append("Date: " + DATE_FORMAT.format(new Date()) + "\r\n");
            sb.append("Server: TestSocketTimeout\r\n");
            if (context.keepAlive) {
                sb.append("Connection: Keep-Alive\r\n");
            } else {
                sb.append("Connection: closeSilently\r\n");
            }
            sb.append("Content-Type: text/plain\r\n");
            sb.append("Content-Length: " + content.length() + "\r\n");
            sb.append("\r\n");
            sb.append(content);

            ByteBuffer buf = ByteBuffer.allocate(sb.length());
            buf.put(sb.toString().getBytes(StandardCharsets.US_ASCII));
            clientChannel.write(buf.flip());

            context.writeFinished = true;
        }
    }

    public class Context {
        final long connectionNo;
        final long openTime;
        boolean readFinished = false;
        boolean writeFinished = false;
        boolean keepAlive = false;

        public Context(long connectionNo) {
            this.connectionNo = connectionNo;
            this.openTime = System.currentTimeMillis();
        }

        public boolean isTimeout() {
            return false; //((System.currentTimeMillis()-this.openTime) > KEEP_ALIVE_TIMEOUT_MS);
        }

        @Override
        public String toString() {
            return "Context{" +
                    "connectionNo=" + connectionNo +
                    ", openTime=" + openTime +
                    ", readFinished=" + readFinished +
                    ", writeFinished=" + writeFinished +
                    ", keepAlive=" + keepAlive +
                    '}';
        }
    }
}
