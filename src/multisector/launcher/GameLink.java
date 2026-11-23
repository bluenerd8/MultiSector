package multisector.launcher;

import multisector.net.Frames;
import multisector.net.NetConfig;
import multisector.net.Protocol;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

/**
 * The host's launcher talking to the host's own game on this PC, through
 * the game's port on the loopback address. The game only accepts these
 * messages from loopback.
 */
final class GameLink {

    static final class Result {
        final boolean saved;
        final String message;

        Result(boolean saved, String message) {
            this.saved = saved;
            this.message = message;
        }
    }

    private GameLink() {
    }

    /** Is the game running a world and hosting? (Its port answers on this PC.) */
    static boolean isHosting(int gamePort) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), gamePort), 500);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Asks the game to save now because friend is about to download. Waits up to about 25 s. */
    static Result requestSave(int gamePort, String friend) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), gamePort), 2000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(25000);
            ByteBuffer bb = Protocol.begin(Protocol.LOCAL_SAVE, Protocol.stringSize(friend));
            Protocol.putString(bb, friend);
            bb.flip();
            Frames.write(s.getOutputStream(), bb);
            ByteBuffer reply = Frames.read(s.getInputStream());
            short[] ver = new short[1];
            if (reply == null || Protocol.readHeader(reply, ver) != Protocol.LOCAL_SAVE_DONE) {
                return new Result(false, ver[0] != 0 && ver[0] != NetConfig.PROTOCOL_VERSION
                        ? "the game has a different MultiSector build" : "the game isn't hosting yet");
            }
            boolean ok = reply.get() != 0;
            return new Result(ok, Protocol.getString(reply));
        } catch (ConnectException e) {
            return new Result(false, "the game isn't running or isn't hosting yet");
        } catch (SocketTimeoutException e) {
            return new Result(false, "the game didn't answer in time");
        } catch (IOException e) {
            return new Result(false, "couldn't reach the game (" + e.getMessage() + ")");
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
