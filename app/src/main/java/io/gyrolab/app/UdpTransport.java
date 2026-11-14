package io.gyrolab.app;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.util.Random;

/** Optional local-network output. Socket submission never proves Windows delivery. */
public final class UdpTransport implements AutoCloseable {
  public static final int PORT = 26761;
  public static final int PACKET_BYTES = 40;
  private static final int HEARTBEAT_MS = 100;
  private static final int FLAG_HEARTBEAT = 1;
  private static final int FLAG_RESET = 2;

  public volatile String status = "UDP not started (host unconfirmed)";
  public volatile long reportsSubmitted;
  public volatile long rejectedReports;
  private volatile boolean available;
  private final Handler handler;
  private final ByteBuffer packet = ByteBuffer.allocate(PACKET_BYTES).order(ByteOrder.BIG_ENDIAN);
  private final Random random = new Random();
  private DatagramChannel channel;
  private int session, sequence, buttons;
  private String sendingStatus = "UDP sending (host unconfirmed)";

  public UdpTransport(Handler transportHandler) {
    if (transportHandler == null) throw new IllegalArgumentException("Handler required");
    handler = transportHandler;
  }

  /** True only for local socket readiness, not verified receiver connectivity. */
  public boolean ready() {
    return available;
  }

  private void onHandler(Runnable action) {
    if (Looper.myLooper() == handler.getLooper()) action.run();
    else handler.post(action);
  }

  /** DNS and socket setup run on the supplied worker, never the UI thread. */
  public void connect(final String host) {
    onHandler(
        new Runnable() {
          @Override
          public void run() {
            closeInternal(true);
            status = "Resolving UDP host (host unconfirmed)";
            String address = host == null ? "" : host.trim();
            if (address.length() == 0) {
              status = "Enter the Windows PC IP address";
              return;
            }
            try {
              InetAddress resolved = null;
              for (InetAddress candidate : InetAddress.getAllByName(address)) {
                if (candidate instanceof Inet4Address) {
                  resolved = candidate;
                  break;
                }
              }
              if (resolved == null) {
                status = "UDP receiver requires a PC IPv4 address";
                return;
              }
              channel = DatagramChannel.open();
              channel.configureBlocking(false);
              channel.socket().setSendBufferSize(8192);
              channel.connect(new InetSocketAddress(resolved, PORT));
              session = random.nextInt();
              sequence = 0;
              buttons = 0;
              sendingStatus = "UDP sending (host unconfirmed): " + address + ":" + PORT;
              available = true;
              status = sendingStatus;
              writePacket(0, 0, 0, 0, FLAG_RESET);
              if (available) handler.postDelayed(heartbeat, HEARTBEAT_MS);
            } catch (IOException ex) {
              closeInternal(false);
              status = "UDP setup failed: " + ex.getClass().getSimpleName();
            } catch (RuntimeException ex) {
              closeInternal(false);
              status = "UDP setup failed: " + ex.getClass().getSimpleName();
            }
          }
        });
  }

  private final Runnable heartbeat =
      new Runnable() {
        @Override
        public void run() {
          if (!available) return;
          writePacket(0, 0, buttons, 0, FLAG_HEARTBEAT);
          if (available) handler.postDelayed(this, HEARTBEAT_MS);
        }
      };

  /**
   * Call from the supplied worker. No queued movement is retained: a full socket drops this packet
   * and records the loss. Report integers outside wire limits are rejected.
   */
  public void send(int dx, int dy, int state, int wheel) {
    if (Looper.myLooper() != handler.getLooper()) {
      rejectedReports++;
      status = "UDP send rejected: use transport worker";
      return;
    }
    if ((state & ~7) != 0
        || dx < -32767
        || dx > 32767
        || dy < -32767
        || dy > 32767
        || wheel < -127
        || wheel > 127) {
      rejectedReports++;
      status = "UDP report rejected: outside protocol limits";
      return;
    }
    buttons = state;
    if (available) writePacket(dx, dy, state, wheel, 0);
  }

  private void writePacket(int dx, int dy, int state, int wheel, int flags) {
    if (!available || channel == null) return;
    packet.clear();
    packet.put((byte) 'G').put((byte) 'L').put((byte) 'X').put((byte) '1');
    packet.putInt(session).putInt(sequence++).putLong(SystemClock.elapsedRealtimeNanos());
    packet.putInt(dx).putInt(dy).putInt(state).putInt(wheel).putInt(flags);
    packet.flip();
    try {
      int count = channel.write(packet);
      if (count == PACKET_BYTES) {
        reportsSubmitted++;
        status = sendingStatus;
      } else {
        rejectedReports++;
        status = "UDP socket busy: report dropped (host unconfirmed)";
      }
    } catch (IOException ex) {
      rejectedReports++;
      closeInternal(false);
      status = "UDP send failed: " + ex.getClass().getSimpleName() + "; reconnect";
    } catch (RuntimeException ex) {
      rejectedReports++;
      closeInternal(false);
      status = "UDP send failed: " + ex.getClass().getSimpleName() + "; reconnect";
    }
  }

  /** Clears button state without disconnecting; heartbeat continues with buttons up. */
  public void release() {
    onHandler(
        new Runnable() {
          @Override
          public void run() {
            buttons = 0;
            if (available) writePacket(0, 0, 0, 0, FLAG_RESET);
          }
        });
  }

  public void close() {
    onHandler(
        new Runnable() {
          @Override
          public void run() {
            closeInternal(true);
            status = "UDP closed (host unconfirmed)";
          }
        });
  }

  private void closeInternal(boolean sendRelease) {
    handler.removeCallbacks(heartbeat);
    if (sendRelease && available) {
      buttons = 0;
      // If a send error closes recursively, the second call does not send again.
      writePacket(0, 0, 0, 0, FLAG_RESET);
    }
    available = false;
    buttons = 0;
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException ignored) {
      }
      channel = null;
    }
  }
}
