package multisector.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Length-prefixed message framing for the TCP channel: a 4-byte big-endian
 * length, then that many payload bytes. TCP is a byte stream, so without a
 * prefix the receiver can't tell where one message ends.
 *
 * Built on plain InputStream/OutputStream and ByteBuffer, which the game's
 * mod sandbox allows (DataInputStream and friends may not be).
 */
public final class Frames {

    private Frames() {
    }

    /** Writes the bytes between the buffer's position and limit as one frame. */
    public static void write(OutputStream out, ByteBuffer payload) throws IOException {
        int len = payload.remaining();
        byte[] frame = new byte[4 + len];
        ByteBuffer.wrap(frame).putInt(len).put(payload);
        out.write(frame);
        out.flush();
    }

    /** Reads one frame. Returns null on a clean end of stream before a new frame starts. */
    public static ByteBuffer read(InputStream in) throws IOException {
        byte[] header = new byte[4];
        if (!readFully(in, header, true)) return null;
        int len = ByteBuffer.wrap(header).getInt();
        if (len < 0 || len > NetConfig.MAX_FRAME) {
            throw new IOException("bad frame length " + len);
        }
        byte[] body = new byte[len];
        readFully(in, body, false);
        return ByteBuffer.wrap(body);
    }

    private static boolean readFully(InputStream in, byte[] buf, boolean eofOk) throws IOException {
        int n = 0;
        while (n < buf.length) {
            int r = in.read(buf, n, buf.length - n);
            if (r < 0) {
                if (eofOk && n == 0) return false;
                throw new IOException("connection closed mid-frame");
            }
            n += r;
        }
        return true;
    }
}
