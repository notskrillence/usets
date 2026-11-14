package io.gyrolab.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Classic Bluetooth HID mouse. No desktop receiver is used. All Bluetooth mutation and report
 * submission happen on the supplied Handler; ready/status/counters may be read from the UI. A
 * submitted report is NOT proof that the host consumed it.
 *
 * <p>Report protocol: id 1, [buttons, signed X, signed Y, signed wheel]. Standard three button boot
 * protocol is also accepted, with id 0 and no wheel. There is no movement queue: integer overflow
 * is split immediately, with a 64-report safety bound.
 */
@SuppressLint("MissingPermission")
public final class HidTransport implements AutoCloseable {
  public static final int REPORT_ID = 1;
  public static final int MAX_SPLIT_REPORTS = 64;
  // Standard USB HID Generic Desktop Mouse application / Pointer physical collection.
  // Bytes are a new implementation of the public HID specification, not copied source.
  public static final byte[] REPORT_DESCRIPTOR =
      new byte[] {
        0x05,
        0x01,
        0x09,
        0x02,
        (byte) 0xA1,
        0x01,
        (byte) 0x85,
        REPORT_ID,
        0x09,
        0x01,
        (byte) 0xA1,
        0x00,
        0x05,
        0x09,
        0x19,
        0x01,
        0x29,
        0x03,
        0x15,
        0x00,
        0x25,
        0x01,
        0x75,
        0x01,
        (byte) 0x95,
        0x03,
        (byte) 0x81,
        0x02,
        0x75,
        0x05,
        (byte) 0x95,
        0x01,
        (byte) 0x81,
        0x01,
        0x05,
        0x01,
        0x09,
        0x30,
        0x09,
        0x31,
        0x09,
        0x38,
        0x15,
        (byte) 0x81,
        0x25,
        0x7F,
        0x75,
        0x08,
        (byte) 0x95,
        0x03,
        (byte) 0x81,
        0x06,
        (byte) 0xC0,
        (byte) 0xC0
      };

  public volatile String status = "Bluetooth HID not started";
  public volatile long reportsSubmitted;
  public volatile long rejectedReports;
  public volatile long disconnectEvents;
  public volatile boolean bootProtocol;
  private volatile boolean available;
  private final Context context;
  private final Handler handler;
  private final Executor executor;
  private BluetoothAdapter adapter;
  private BluetoothHidDevice hid;
  private BluetoothDevice host;
  private BluetoothDevice wantedHost;
  private boolean registered;
  private boolean registrationPending;
  private boolean proxyPending;
  private boolean closed;
  private boolean fault;
  private boolean reconnectEnabled;
  private int reconnectAttempts;
  private int buttons;
  private final byte[] report = new byte[4];
  private final byte[] bootReport = new byte[3];

  public HidTransport(Context context, Handler transportHandler) {
    this.context = context.getApplicationContext();
    this.handler = transportHandler;
    this.executor =
        new Executor() {
          @Override
          public void execute(Runnable task) {
            handler.post(task);
          }
        };
    BluetoothManager manager =
        (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
    if (manager != null) adapter = manager.getAdapter();
  }

  public boolean ready() {
    return available;
  }

  private void onHandler(Runnable action) {
    if (Looper.myLooper() == handler.getLooper()) action.run();
    else handler.post(action);
  }

  /** Call after BLUETOOTH_CONNECT permission has been granted. */
  public void start() {
    onHandler(
        new Runnable() {
          @Override
          public void run() {
            startInternal();
          }
        });
  }

  private void startInternal() {
    if (closed) {
      status = "Bluetooth HID is closed";
      return;
    }
    try {
      if (adapter == null) {
        status = "No Bluetooth adapter";
        return;
      }
      if (!adapter.isEnabled()) {
        status = "Enable Bluetooth, then Connect";
        available = false;
        return;
      }
      if (registered) {
        if (host != null && !fault) {
          available = true;
          status = connectedText();
        } else {
          status = "HID ready; pair from host Bluetooth settings or select a paired host";
        }
        return;
      }
      if (hid != null) {
        register();
        return;
      }
      if (proxyPending) return;
      proxyPending = adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE);
      status =
          proxyPending
              ? "Opening Android Bluetooth HID profile"
              : "Android rejected HID profile; check Bluetooth support";
    } catch (SecurityException ex) {
      permissionFailure();
    } catch (RuntimeException ex) {
      status = "HID start failed: " + ex.getClass().getSimpleName();
      available = false;
    }
  }

  private final BluetoothProfile.ServiceListener serviceListener =
      new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, final BluetoothProfile proxy) {
          onHandler(
              new Runnable() {
                @Override
                public void run() {
                  proxyPending = false;
                  if (closed) {
                    try {
                      if (adapter != null)
                        adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy);
                    } catch (RuntimeException ignored) {
                    }
                    return;
                  }
                  if (!(proxy instanceof BluetoothHidDevice)) {
                    status = "Unexpected Bluetooth profile";
                    return;
                  }
                  hid = (BluetoothHidDevice) proxy;
                  register();
                }
              });
        }

        @Override
        public void onServiceDisconnected(int profile) {
          onHandler(
              new Runnable() {
                @Override
                public void run() {
                  available = false;
                  registered = false;
                  registrationPending = false;
                  proxyPending = false;
                  hid = null;
                  host = null;
                  buttons = 0;
                  disconnectEvents++;
                  if (!closed) status = "Android HID service disconnected; press Connect to reopen";
                }
              });
        }
      };

  private void register() {
    if (closed || hid == null || registered || registrationPending) return;
    try {
      BluetoothHidDeviceAppSdpSettings sdp =
          new BluetoothHidDeviceAppSdpSettings(
              "GyroLab X",
              "Gyroscopic three-button mouse",
              "GyroLab X",
              BluetoothHidDevice.SUBCLASS1_MOUSE,
              REPORT_DESCRIPTOR);
      // Null QoS uses platform scheduling. No unsupported report-interval promises.
      registrationPending = hid.registerApp(sdp, null, null, executor, callback);
      status =
          registrationPending
              ? "Registering Bluetooth mouse"
              : "HID registration rejected; close other phone HID apps and retry";
    } catch (SecurityException ex) {
      permissionFailure();
    } catch (RuntimeException ex) {
      status = "HID registration failed: " + ex.getClass().getSimpleName();
    }
  }

  /** Does not scan: only the OS's already paired devices are returned. */
  public List<BluetoothDevice> bondedHosts() {
    try {
      if (adapter == null || !adapter.isEnabled()) return Collections.emptyList();
      Set<BluetoothDevice> bonded = adapter.getBondedDevices();
      return new ArrayList<BluetoothDevice>(bonded);
    } catch (SecurityException ex) {
      status = "Bluetooth nearby-devices permission required";
      return Collections.emptyList();
    } catch (RuntimeException ex) {
      status = "Cannot read paired Bluetooth devices";
      return Collections.emptyList();
    }
  }

  public void connect(final BluetoothDevice device) {
    onHandler(
        new Runnable() {
          @Override
          public void run() {
            if (closed || device == null) return;
            reconnectEnabled = true;
            reconnectAttempts = 0;
            fault = false;
            wantedHost = device;
            handler.removeCallbacks(reconnect);
            if (host != null && !host.equals(device)) {
              releaseInternal();
              try {
                if (hid != null) hid.disconnect(host);
              } catch (RuntimeException ignored) {
              }
              available = false;
              host = null;
            }
            startInternal();
            connectInternal();
          }
        });
  }

  private void connectInternal() {
    if (closed || !registered || hid == null || wantedHost == null || !reconnectEnabled) return;
    try {
      if (host != null
          && host.equals(wantedHost)
          && hid.getConnectionState(host) == BluetoothProfile.STATE_CONNECTED) {
        fault = false;
        available = true;
        releaseInternal();
        status = connectedText();
        return;
      }
      status =
          hid.connect(wantedHost)
              ? "Connecting Bluetooth mouse to " + name(wantedHost)
              : "Host rejected connect; initiate pairing/connection on the host";
    } catch (SecurityException ex) {
      permissionFailure();
    } catch (RuntimeException ex) {
      status = "Bluetooth connect failed: " + ex.getClass().getSimpleName();
    }
  }

  private final Runnable reconnect =
      new Runnable() {
        @Override
        public void run() {
          if (!closed && reconnectEnabled && host == null && registered) connectInternal();
        }
      };

  public void disconnect() {
    onHandler(
        new Runnable() {
          @Override
          public void run() {
            reconnectEnabled = false;
            wantedHost = null;
            handler.removeCallbacks(reconnect);
            releaseInternal();
            BluetoothDevice old = host;
            host = null;
            available = false;
            fault = false;
            buttons = 0;
            try {
              if (hid != null && old != null) hid.disconnect(old);
            } catch (SecurityException ex) {
              permissionFailure();
              return;
            } catch (RuntimeException ignored) {
            }
            status =
                registered
                    ? "Bluetooth mouse disconnected; HID remains registered"
                    : "Bluetooth mouse disconnected";
          }
        });
  }

  /** Fractional counts belong in the caller. This method splits all accepted integer counts. */
  public void send(final int dx, final int dy, final int newButtons, final int wheel) {
    // Sensor-loop calls take this allocation-free branch.
    if (Looper.myLooper() == handler.getLooper()) sendInternal(dx, dy, newButtons, wheel);
    else
      handler.post(
          new Runnable() {
            @Override
            public void run() {
              sendInternal(dx, dy, newButtons, wheel);
            }
          });
  }

  private void sendInternal(int dx, int dy, int newButtons, int wheel) {
    if (!available || closed || hid == null || host == null || fault) return;
    long extent =
        Math.max(Math.max(Math.abs((long) dx), Math.abs((long) dy)), Math.abs((long) wheel));
    if (extent > 127L * MAX_SPLIT_REPORTS) {
      rejectedReports++;
      releaseInternal();
      available = false;
      fault = true;
      status = "HID safety stop: displacement exceeds 64 reports; recenter and reconnect";
      return;
    }
    buttons = newButtons & 7;
    if (bootProtocol && wheel != 0) {
      rejectedReports++;
      status = "Connected in boot mouse protocol: host cannot accept scroll";
      wheel = 0;
    }
    do {
      int x = clampReport(dx), y = clampReport(dy), w = clampReport(wheel);
      if (!submit(x, y, buttons, w)) {
        rejectedReports++;
        releaseInternal();
        available = false;
        fault = true;
        status = "HID report submission failed; tracking stopped until reconnect";
        return;
      }
      dx -= x;
      dy -= y;
      wheel -= w;
    } while (dx != 0 || dy != 0 || wheel != 0);
  }

  private static int clampReport(int value) {
    return Math.max(-127, Math.min(127, value));
  }

  private boolean submit(int x, int y, int mask, int wheel) {
    if (hid == null || host == null) return false;
    try {
      boolean sent;
      if (bootProtocol) {
        bootReport[0] = (byte) (mask & 7);
        bootReport[1] = (byte) x;
        bootReport[2] = (byte) y;
        sent = hid.sendReport(host, 0, bootReport);
      } else {
        report[0] = (byte) (mask & 7);
        report[1] = (byte) x;
        report[2] = (byte) y;
        report[3] = (byte) wheel;
        sent = hid.sendReport(host, REPORT_ID, report);
      }
      if (sent) reportsSubmitted++;
      return sent;
    } catch (SecurityException ex) {
      permissionFailure();
      return false;
    } catch (RuntimeException ex) {
      return false;
    }
  }

  /** Always attempts an all-buttons-up report, including after a failed movement submission. */
  public void release() {
    onHandler(
        new Runnable() {
          @Override
          public void run() {
            releaseInternal();
          }
        });
  }

  private void releaseInternal() {
    buttons = 0;
    submit(0, 0, 0, 0);
  }

  private final BluetoothHidDevice.Callback callback =
      new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean isRegistered) {
          registrationPending = false;
          registered = isRegistered;
          if (closed) return;
          if (!isRegistered) {
            available = false;
            host = null;
            buttons = 0;
            status = "HID app unregistered by Android; keep app visible and press Connect";
          } else {
            fault = false;
            bootProtocol = false;
            status = "HID ready; pair from iPad/PC Bluetooth settings or select a paired host";
            if (pluggedDevice != null && wantedHost == null) {
              wantedHost = pluggedDevice;
              reconnectEnabled = true;
            }
            connectInternal();
          }
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
          if (closed) return;
          if (state == BluetoothProfile.STATE_CONNECTED) {
            host = device;
            wantedHost = device;
            reconnectEnabled = true;
            reconnectAttempts = 0;
            handler.removeCallbacks(reconnect);
            buttons = 0;
            fault = false;
            bootProtocol = false;
            available = registered;
            releaseInternal();
            status = connectedText();
          } else if (state == BluetoothProfile.STATE_CONNECTING) {
            status = "Connecting Bluetooth mouse to " + name(device);
          } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
            if (host == null || host.equals(device)) {
              host = null;
              available = false;
              buttons = 0;
              disconnectEvents++;
              status = "Bluetooth host disconnected";
              if (reconnectEnabled
                  && wantedHost != null
                  && wantedHost.equals(device)
                  && registered
                  && reconnectAttempts < 5) {
                long delay = 1000L << reconnectAttempts++;
                handler.removeCallbacks(reconnect);
                handler.postDelayed(reconnect, delay);
                status += "; retry in " + (delay / 1000) + " s";
              }
            }
          }
        }

        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
          if (hid == null || closed) return;
          try {
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT
                && (id == REPORT_ID || (bootProtocol && id == 0))) {
              byte[] value =
                  bootProtocol
                      ? new byte[] {(byte) buttons, 0, 0}
                      : new byte[] {(byte) buttons, 0, 0, 0};
              hid.replyReport(device, type, id, value);
            } else hid.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ);
          } catch (RuntimeException ex) {
            status = "HID host report request failed";
          }
        }

        @Override
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
          try {
            if (hid != null) hid.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ);
          } catch (RuntimeException ignored) {
          }
        }

        @Override
        public void onSetProtocol(BluetoothDevice device, byte protocol) {
          if (closed) return;
          releaseInternal();
          bootProtocol = protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE;
          buttons = 0;
          releaseInternal();
          status = connectedText();
        }

        @Override
        public void onVirtualCableUnplug(BluetoothDevice device) {
          reconnectEnabled = false;
          wantedHost = null;
          handler.removeCallbacks(reconnect);
          releaseInternal();
          host = null;
          available = false;
          buttons = 0;
          status = "Host removed Bluetooth mouse; pair again to reconnect";
        }
      };

  private String name(BluetoothDevice device) {
    if (device == null) return "host";
    try {
      String value = device.getName();
      return value == null ? device.getAddress() : value;
    } catch (SecurityException ex) {
      return "host";
    }
  }

  private String connectedText() {
    return "Bluetooth HID connected to "
        + name(host)
        + (bootProtocol ? " (boot protocol, no wheel)" : " (report protocol)");
  }

  private void permissionFailure() {
    available = false;
    registrationPending = false;
    proxyPending = false;
    status = "Bluetooth nearby-devices permission required; grant it and press Connect";
  }

  @Override
  public void close() {
    onHandler(
        new Runnable() {
          @Override
          public void run() {
            if (closed) return;
            releaseInternal();
            closed = true;
            available = false;
            reconnectEnabled = false;
            handler.removeCallbacks(reconnect);
            try {
              if (hid != null) {
                if (host != null) hid.disconnect(host);
                hid.unregisterApp();
                if (adapter != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
              }
            } catch (RuntimeException ignored) {
            }
            hid = null;
            host = null;
            wantedHost = null;
            registered = false;
            status = "Bluetooth mouse closed";
          }
        });
  }
}
