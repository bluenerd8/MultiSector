package multisector.probe;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * The individual probes. Each one is its own nested class (so its own .class
 * file), and is only created inside run(i), so a blocked JDK reference fails
 * that probe alone instead of the whole mod.
 *
 * Note: this outer class imports java.net. If java.net itself were blocked,
 * only the java.net probes would fail, because nested classes are resolved
 * separately from the outer class.
 */
final class Probes {

    static final int COUNT = 16;

    private Probes() {
    }

    static String name(int i) {
        switch (i) {
            case 0: return "java.net TCP loopback (Socket/ServerSocket)";
            case 1: return "java.net UDP loopback (DatagramSocket)";
            case 2: return "java.nio.ByteBuffer";
            case 3: return "java.nio.channels Selector + ServerSocketChannel (KryoNet needs)";
            case 4: return "java.nio.channels DatagramChannel (KryoNet needs)";
            case 5: return "java.lang.Thread + java.util.concurrent queue";
            case 6: return "java.lang.Class.getName";
            case 7: return "lambda (LambdaMetafactory)";
            case 8: return "java.lang.invoke MethodHandles";
            case 9: return "java.lang.reflect.Field (Kryo needs)";
            case 10: return "java.io.ByteArrayOutputStream";
            case 11: return "java.io.DataOutputStream/DataInputStream";
            case 12: return "java.util.zip CRC32 (state checksums)";
            case 13: return "java.util.zip Deflater/Inflater (snapshot compression)";
            case 14: return "System.nanoTime / currentTimeMillis";
            case 15: return "java.io.ObjectOutputStream (Java serialization)";
            default: return "unknown " + i;
        }
    }

    static String run(int i) throws Throwable {
        switch (i) {
            case 0: return new TcpLoopback().run();
            case 1: return new UdpLoopback().run();
            case 2: return new ByteBufferProbe().run();
            case 3: return new NioSelectorProbe().run();
            case 4: return new NioDatagramProbe().run();
            case 5: return new ThreadProbe().run();
            case 6: return new ClassProbe().run();
            case 7: return new LambdaProbe().run();
            case 8: return new MethodHandleProbe().run();
            case 9: return new ReflectFieldProbe().run();
            case 10: return new ByteArrayOutProbe().run();
            case 11: return new DataStreamProbe().run();
            case 12: return new Crc32Probe().run();
            case 13: return new DeflaterProbe().run();
            case 14: return new ClockProbe().run();
            case 15: return new ObjectStreamProbe().run();
            default: throw new IllegalArgumentException("no probe " + i);
        }
    }

    // ---------------------------------------------------------------- probes

    static final class TcpLoopback {
        String run() throws Exception {
            InetAddress lo = InetAddress.getLoopbackAddress();
            ServerSocket server = new ServerSocket(0, 1, lo);
            try {
                server.setSoTimeout(2000);
                Socket client = new Socket();
                client.connect(new InetSocketAddress(lo, server.getLocalPort()), 2000);
                Socket accepted = server.accept();
                try {
                    accepted.setSoTimeout(2000);
                    client.getOutputStream().write(new byte[]{1, 2, 3, 4});
                    client.getOutputStream().flush();
                    byte[] buf = new byte[4];
                    int n = 0;
                    while (n < 4) {
                        int r = accepted.getInputStream().read(buf, n, 4 - n);
                        if (r < 0) throw new IllegalStateException("stream closed after " + n + " bytes");
                        n += r;
                    }
                    if (buf[3] != 4) throw new IllegalStateException("bad payload");
                    return "round trip ok on port " + server.getLocalPort();
                } finally {
                    accepted.close();
                    client.close();
                }
            } finally {
                server.close();
            }
        }
    }

    static final class UdpLoopback {
        String run() throws Exception {
            InetAddress lo = InetAddress.getLoopbackAddress();
            DatagramSocket a = new DatagramSocket(0, lo);
            DatagramSocket b = new DatagramSocket(0, lo);
            try {
                b.setSoTimeout(2000);
                byte[] out = new byte[]{9, 8, 7};
                a.send(new DatagramPacket(out, out.length, lo, b.getLocalPort()));
                byte[] in = new byte[16];
                DatagramPacket p = new DatagramPacket(in, in.length);
                b.receive(p);
                if (p.getLength() != 3 || in[0] != 9) throw new IllegalStateException("bad datagram");
                return "datagram ok";
            } finally {
                a.close();
                b.close();
            }
        }
    }

    static final class ByteBufferProbe {
        String run() {
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(16);
            bb.putInt(42).putFloat(1.5f).flip();
            int v = bb.getInt();
            float f = bb.getFloat();
            return "read back " + v + ", " + f;
        }
    }

    static final class NioSelectorProbe {
        String run() throws Exception {
            java.nio.channels.Selector sel = java.nio.channels.Selector.open();
            java.nio.channels.ServerSocketChannel ch = java.nio.channels.ServerSocketChannel.open();
            try {
                ch.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                ch.configureBlocking(false);
                ch.register(sel, java.nio.channels.SelectionKey.OP_ACCEPT);
                sel.selectNow();
                return "selector + server channel ok";
            } finally {
                ch.close();
                sel.close();
            }
        }
    }

    static final class NioDatagramProbe {
        String run() throws Exception {
            java.nio.channels.DatagramChannel ch = java.nio.channels.DatagramChannel.open();
            try {
                ch.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                return "datagram channel ok";
            } finally {
                ch.close();
            }
        }
    }

    static final class ThreadProbe {
        String run() throws Exception {
            final java.util.concurrent.ConcurrentLinkedQueue<String> q =
                    new java.util.concurrent.ConcurrentLinkedQueue<String>();
            Thread t = new Thread(new Runnable() {
                public void run() {
                    q.add("hello");
                }
            }, "MultiSector-probe");
            t.setDaemon(true);
            t.start();
            t.join(2000);
            return "background thread wrote: " + q.poll();
        }
    }

    static final class ClassProbe {
        String run() {
            return String.class.getName();
        }
    }

    static final class LambdaProbe {
        String run() {
            java.util.function.Supplier<String> s = () -> "lambda ok";
            return s.get();
        }
    }

    static final class MethodHandleProbe {
        String run() throws Throwable {
            java.lang.invoke.MethodHandle mh = java.lang.invoke.MethodHandles.lookup().findVirtual(
                    String.class, "length", java.lang.invoke.MethodType.methodType(int.class));
            int len = (int) mh.invoke("abcd");
            return "length via handle = " + len;
        }
    }

    static final class ReflectFieldProbe {
        int target = 7;

        String run() throws Exception {
            java.lang.reflect.Field f = ReflectFieldProbe.class.getDeclaredField("target");
            return "field read = " + f.getInt(this);
        }
    }

    static final class ByteArrayOutProbe {
        String run() {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            bos.write(5);
            return "wrote " + bos.size() + " byte";
        }
    }

    static final class DataStreamProbe {
        String run() throws Exception {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(bos);
            out.writeInt(1234);
            out.flush();
            java.io.DataInputStream in = new java.io.DataInputStream(
                    new java.io.ByteArrayInputStream(bos.toByteArray()));
            return "read back " + in.readInt();
        }
    }

    static final class Crc32Probe {
        String run() {
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(new byte[]{1, 2, 3});
            return "crc = " + Long.toHexString(crc.getValue());
        }
    }

    static final class DeflaterProbe {
        String run() throws Exception {
            byte[] input = new byte[1000];
            java.util.zip.Deflater d = new java.util.zip.Deflater();
            d.setInput(input);
            d.finish();
            byte[] out = new byte[1000];
            int n = d.deflate(out);
            d.end();
            java.util.zip.Inflater inf = new java.util.zip.Inflater();
            inf.setInput(out, 0, n);
            byte[] back = new byte[1000];
            int m = inf.inflate(back);
            inf.end();
            return "1000 -> " + n + " -> " + m + " bytes";
        }
    }

    static final class ClockProbe {
        String run() {
            long a = System.nanoTime();
            long b = System.currentTimeMillis();
            return "nanoTime " + a + ", millis " + b;
        }
    }

    static final class ObjectStreamProbe {
        String run() throws Exception {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bos);
            oos.writeObject("x");
            oos.close();
            return "serialized " + bos.size() + " bytes";
        }
    }
}
