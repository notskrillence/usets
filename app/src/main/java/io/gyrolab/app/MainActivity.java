package io.gyrolab.app;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import io.gyrolab.core.*;
import io.gyrolab.lab.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** A foreground-only instrument. Processing and Bluetooth are independent of rendering. */
public class MainActivity extends Activity implements SensorEventListener {
  private final Handler ui = new Handler(Looper.getMainLooper());
  private HandlerThread inputThread;
  private Handler input;
  private final ExecutorService files = Executors.newSingleThreadExecutor();
  private SensorManager sensors;
  private HidTransport hid;
  private UdpTransport udp;
  private MotionEngine engine;
  private final Quantizer quantizer = new Quantizer();
  private volatile MotionEngine.Config config;
  private volatile boolean tracking, recording, calibrating, clutchDown, holdToMove, udpSelected;
  private volatile boolean volumeClicks, clickGuard = true, foreground, rawGyro;
  private int requestedUs = 5000, buttons;
  private long suppressUntil;
  private double ax, ay, az;
  private long accelNs;
  private Calibration calibration, stats = new Calibration();
  private Recording trace = new Recording(60000);
  private MotionEngine.Config traceConfig;
  private volatile String
      note = "Bluetooth HID is the default. Pair from iPad or PC Bluetooth settings.",
      telemetry = "Waiting for gyro events";
  private long processed, costNs, dispatchNs, accuracy, staleSamples;
  private boolean staleGate;
  private int page;
  private final Object cursorLock = new Object();
  private double pendingX, pendingY;
  private long pendingSamples;
  private LinearLayout body;
  private TextView statusView, detailView;
  private volatile LabView labView;
  private Button trackingButton, recordButton, modeButton, taskButton;
  private String exportText, exportName;
  private Recording exportTrace;
  private MotionEngine.Config exportTraceConfig;
  private int importKind;
  private volatile boolean replayBusy;
  private Runnable calibrationFinish;
  private String labKind = "static";
  private MotionEngine.Config previousConfig, abConfig;
  private boolean abActive;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    config = ConfigStore.loadCurrent(this);
    engine = new MotionEngine(config.copy());
    volumeClicks = getPreferences(0).getBoolean("volumeClicks", false);
    clickGuard = getPreferences(0).getBoolean("clickGuard", true);
    holdToMove = getPreferences(0).getBoolean("holdToMove", false);
    inputThread = new HandlerThread("GyroLab-input", android.os.Process.THREAD_PRIORITY_DISPLAY);
    inputThread.start();
    input = new Handler(inputThread.getLooper());
    sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
    hid = new HidTransport(this, input);
    udp = new UdpTransport(input);
    render(0);
    ui.post(tick);
  }

  private int dp(float n) {
    return (int) (n * getResources().getDisplayMetrics().density + .5f);
  }

  private void text(String s) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(15);
    t.setPadding(0, dp(4), 0, dp(4));
    body.addView(t);
  }

  private Button button(String label, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setMinHeight(dp(48));
    b.setOnClickListener(v -> action.run());
    body.addView(b, new LinearLayout.LayoutParams(-1, -2));
    return b;
  }

  private LinearLayout row() {
    LinearLayout r = new LinearLayout(this);
    r.setOrientation(LinearLayout.HORIZONTAL);
    body.addView(r);
    return r;
  }

  private Button rowButton(LinearLayout row, String title, Runnable r) {
    Button b = new Button(this);
    b.setText(title);
    b.setMinHeight(dp(48));
    b.setOnClickListener(v -> r.run());
    row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
    return b;
  }

  private void render(int selected) {
    page = selected;
    statusView = detailView = null;
    trackingButton = recordButton = modeButton = taskButton = null;
    labView = null;
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.rgb(245, 246, 247));
    root.setOnApplyWindowInsetsListener(
        (v, in) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets i =
                in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(
                i.left + SafeArea.sideMargin(this),
                i.top,
                i.right + SafeArea.sideMargin(this),
                i.bottom + SafeArea.bottomMargin(this));
          } else
            v.setPadding(
                in.getSystemWindowInsetLeft() + SafeArea.sideMargin(this),
                in.getSystemWindowInsetTop(),
                in.getSystemWindowInsetRight() + SafeArea.sideMargin(this),
                in.getSystemWindowInsetBottom() + SafeArea.bottomMargin(this));
          return in;
        });
    View damaged = new View(this);
    root.addView(damaged, new LinearLayout.LayoutParams(-1, 0, .28f));
    LinearLayout accessible = new LinearLayout(this);
    accessible.setOrientation(LinearLayout.VERTICAL);
    root.addView(accessible, new LinearLayout.LayoutParams(-1, 0, .72f));
    ScrollView scroll = new ScrollView(this);
    body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(12), 0, dp(12), dp(8));
    scroll.addView(body);
    accessible.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout nav = new LinearLayout(this);
    for (int i = 0; i < 3; i++) {
      final int n = i;
      Button b = new Button(this);
      b.setText(new String[] {"Mouse", "Advanced", "Lab"}[i]);
      b.setEnabled(i != selected);
      b.setOnClickListener(
          v -> {
            if (page == 2 && n != 2) resetCursor();
            render(n);
          });
      nav.addView(b, new LinearLayout.LayoutParams(0, dp(52), 1));
    }
    accessible.addView(nav);
    setContentView(root);
    root.requestApplyInsets();
    statusView = new TextView(this);
    statusView.setTextSize(15);
    body.addView(statusView);
    if (selected == 0) mousePage();
    else if (selected == 1) advancedPage();
    else labPage();
    updateStatus();
  }

  private void mousePage() {
    text("GyroLab X • Direct Bluetooth mouse");
    LinearLayout conn = row();
    rowButton(conn, "Pair / discover", () -> pair());
    rowButton(conn, "Connect host", () -> hosts());
    trackingButton =
        button(
            "Enable tracking",
            () ->
                input.post(
                    () -> {
                      tracking = !tracking;
                      resetInput();
                      if (tracking)
                        ui.post(
                            () -> {
                              if (labView != null) labView.restart(labKind);
                            });
                      note = tracking ? "Tracking enabled" : "Tracking paused";
                    }));
    modeButton =
        button(
            "Mode: " + MotionEngine.MODE_NAMES[config.mode],
            () ->
                choose(
                    "Tracking mode",
                    MotionEngine.MODE_NAMES,
                    n -> {
                      MotionEngine.Config c = config.copy();
                      c.mode = n;
                      apply(c);
                    }));
    TextView gain = new TextView(this);
    gain.setText(
        String.format(
            Locale.US,
            "Sensitivity %.0f counts / rad (%.1f / degree)",
            config.gainX,
            config.gainX * Math.PI / 180));
    body.addView(gain);
    SeekBar slider = new SeekBar(this);
    slider.setMax(5900);
    slider.setProgress((int) Math.max(0, Math.min(5900, config.gainX - 100)));
    body.addView(slider);
    slider.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          int value = (int) config.gainX;

          public void onStartTrackingTouch(SeekBar b) {}

          public void onProgressChanged(SeekBar b, int p, boolean u) {
            value = p + 100;
            if (u) gain.setText("Sensitivity " + value + " counts / rad • both axes");
          }

          public void onStopTrackingTouch(SeekBar b) {
            MotionEngine.Config c = config.copy();
            c.gainX = c.gainY = b.getProgress() + 100;
            apply(c);
          }
        });
    LinearLayout ref = row();
    rowButton(
        ref,
        "Recenter",
        () ->
            input.post(
                () -> {
                  resetInput();
                  note = "Reference reset; no movement sent";
                }));
    rowButton(ref, "Calibrate 2s", () -> calibrate(2000));
    LinearLayout pads = row();
    pad(pads, "LEFT / drag", 1);
    pad(pads, "RIGHT", 2);
    pad(pads, "MIDDLE", 4);
    LinearLayout actions = row();
    rowButton(
        actions,
        "Drag lock",
        () ->
            input.post(
                () -> {
                  buttons ^= 1;
                  send(0, 0, buttons, 0);
                  note = (buttons & 1) != 0 ? "Drag locked" : "Drag released";
                }));
    rowButton(actions, "Wheel ↑", () -> input.post(() -> send(0, 0, buttons, 1)));
    rowButton(actions, "Wheel ↓", () -> input.post(() -> send(0, 0, buttons, -1)));
    Button clutch =
        button(
            holdToMove ? "HOLD to move" : "HOLD to pause / clutch",
            () ->
                input.post(
                    () -> {
                      clutchDown = !clutchDown;
                      resetInput();
                    }));
    clutch.setOnTouchListener(
        (v, e) -> {
          int a = e.getActionMasked();
          if (a == MotionEvent.ACTION_DOWN
              || a == MotionEvent.ACTION_UP
              || a == MotionEvent.ACTION_CANCEL) {
            input.post(
                () -> {
                  clutchDown = a == MotionEvent.ACTION_DOWN;
                  resetInput();
                });
            return true;
          }
          return true;
        });
    button(
        "Disconnect",
        () ->
            input.post(
                () -> {
                  tracking = false;
                  resetInput();
                  hid.disconnect();
                  udp.close();
                  note = "Disconnected";
                }));
    text(
        "Keep this app visible. Touch pads hold buttons; pause, recenter, and leaving the app"
            + " release them. Pointer acceleration on the host affects counts-to-pixels mapping.");
  }

  private void pad(LinearLayout r, String label, int mask) {
    Button b =
        rowButton(
            r,
            label,
            () ->
                input.post(
                    () -> {
                      send(0, 0, buttons | mask, 0);
                      input.postDelayed(() -> send(0, 0, buttons, 0), 35);
                    }));
    b.setOnTouchListener(
        (v, e) -> {
          int a = e.getActionMasked();
          if (a == 0 || a == 1 || a == 3) {
            input.post(
                () -> {
                  if (a == 0) buttons |= mask;
                  else buttons &= ~mask;
                  if (clickGuard && !recording)
                    suppressUntil = SystemClock.elapsedRealtimeNanos() + 45000000L;
                  send(0, 0, buttons, 0);
                });
          }
          return true;
        });
  }

  private void advancedPage() {
    button(
        "Edit processing parameters / compose pipeline",
        () -> AdvancedPanel.show(this, config.copy(), this::apply));
    LinearLayout cal = row();
    rowButton(cal, "Stationary 2s", () -> calibrate(2000));
    rowButton(cal, "Noise test 10s", () -> calibrate(10000));
    check(
        "Clutch is hold-to-move (otherwise hold-to-pause)",
        holdToMove,
        v -> {
          holdToMove = v;
          getPreferences(0).edit().putBoolean("holdToMove", v).apply();
          input.post(this::resetInput);
        });
    check(
        "Volume keys: left / right mouse buttons",
        volumeClicks,
        v -> {
          volumeClicks = v;
          getPreferences(0).edit().putBoolean("volumeClicks", v).apply();
        });
    check(
        "Suppress movement for 45ms on touch click",
        clickGuard,
        v -> {
          clickGuard = v;
          getPreferences(0).edit().putBoolean("clickGuard", v).apply();
        });
    button("Sensor source / requested rate", () -> sensorDialog());
    button(
        "Bluetooth HID (default; Windows / iPad)",
        () ->
            input.post(
                () -> {
                  resetInput();
                  udp.close();
                  udpSelected = false;
                  startHid();
                  note = "Direct Bluetooth HID selected";
                }));
    button(
        "Optional Windows Wi-Fi receiver (IP)",
        () ->
            inputText(
                "PC local IP",
                "",
                host ->
                    input.post(
                        () -> {
                          resetInput();
                          hid.disconnect();
                          udpSelected = true;
                          udp.connect(host);
                        })));
    LinearLayout p = row();
    rowButton(
        p,
        "Save preset",
        () ->
            inputText(
                "Preset name",
                "My preset",
                name -> {
                  try {
                    ConfigStore.save(this, name, config.copy());
                    note = "Preset saved";
                  } catch (Exception e) {
                    error(e);
                  }
                }));
    rowButton(p, "Load preset", () -> presets(false));
    rowButton(p, "Delete", () -> presets(true));
    LinearLayout imp = row();
    rowButton(
        imp,
        "Export preset",
        () -> export("gyrolab-preset.json", ConfigStore.encode(config.copy()), null, null));
    rowButton(imp, "Import preset", () -> importFile(1));
    LinearLayout ab = row();
    rowButton(
        ab,
        "Pin current as B",
        () -> {
          abConfig = config.copy();
          abActive = false;
          note = "B pinned. Change settings to create A, then switch.";
        });
    rowButton(
        ab,
        "A ↔ B",
        () -> {
          if (abConfig == null) {
            note = "Pin B first";
            return;
          }
          MotionEngine.Config next = abConfig.copy();
          abConfig = config.copy();
          abActive = !abActive;
          apply(next);
        });
    rowButton(
        ab,
        "Undo settings",
        () -> {
          if (previousConfig != null) apply(previousConfig.copy());
          else note = "No earlier settings";
        });
    button("Reset default settings", () -> apply(new MotionEngine.Config()));
    detailView = new TextView(this);
    detailView.setTextSize(12);
    body.addView(detailView);
    text(
        "Experimental modes are hypotheses, not verified improvements. Fusion cannot provide an"
            + " absolute yaw reference. Composition order: bias / axes → deadzone → low-pass →"
            + " nonlinear gain → bounded prediction → counts.");
  }

  private void check(String title, boolean value, java.util.function.Consumer<Boolean> action) {
    CheckBox c = new CheckBox(this);
    c.setText(title);
    c.setChecked(value);
    c.setOnCheckedChangeListener((b, v) -> action.accept(v));
    body.addView(c);
  }

  private void labPage() {
    text("Local accuracy lab • generated counts, not host delivery");
    labView = new LabView(this);
    body.addView(labView, new LinearLayout.LayoutParams(-1, dp(210)));
    LinearLayout controls = row();
    trackingButton =
        rowButton(
            controls,
            "Enable",
            () ->
                input.post(
                    () -> {
                      tracking = !tracking;
                      resetInput();
                      if (tracking)
                        ui.post(
                            () -> {
                              if (labView != null) labView.restart(labKind);
                            });
                    }));
    rowButton(controls, "HIT / select", () -> labView.click());
    rowButton(
        controls,
        "Recenter",
        () ->
            input.post(
                () -> {
                  resetInput();
                  ui.post(
                      () -> {
                        if (labView != null) labView.restart(labKind);
                      });
                }));
    String[] kinds = {"static", "micro", "horizontal", "vertical", "moving", "stationary"};
    labView.restart(labKind);
    taskButton =
        button(
            "Task: " + labKind + " • restart / choose",
            () ->
                choose(
                    "Local task",
                    kinds,
                    n -> {
                      labKind = kinds[n];
                      if (labView != null) labView.restart(labKind);
                    }));
    button(
        "Export current trial metrics + trace",
        () -> {
          if (labView != null)
            export(
                "gyrolab-trials.csv",
                labView.resultsCsv() + "\n# config=" + ConfigStore.encode(config.copy()),
                null,
                null);
        });
    LinearLayout rec = row();
    recordButton =
        rowButton(
            rec,
            "Record motion",
            () ->
                input.post(
                    () -> {
                      if (recording) {
                        recording = false;
                        note = "Recording stopped: " + trace.size() + " samples";
                      } else if (replayBusy) {
                        note = "Wait for replay";
                      } else if (!engaged()) {
                        note = "Enable tracking and engage the clutch before recording";
                      } else {
                        MotionEngine.Config initial = engine.config.copy();
                        resetInput();
                        trace = new Recording(60000);
                        traceConfig = initial;
                        engine = new MotionEngine(initial.copy());
                        trace.recordedConfig = traceConfig.copy();
                        recording = true;
                        note =
                            "Recording raw gyro + accel (separate from accuracy trial); click guard"
                                + " disabled; max 60,000 samples";
                      }
                    }));
    rowButton(
        rec,
        "Export recording",
        () ->
            input.post(
                () -> {
                  recording = false;
                  final Recording saved = trace;
                  final MotionEngine.Config cfg =
                      traceConfig == null ? trace.recordedConfig.copy() : traceConfig.copy();
                  ui.post(() -> export("gyrolab-recording.csv", null, saved, cfg));
                }));
    rowButton(rec, "Import", () -> importFile(2));
    button("Replay and compare all 12 modes", () -> runReplay(false));
    button("Tune One Euro: stability proxy", () -> runReplay(true));
    button(
        "Replay current mode trajectory",
        () -> {
          recording = false;
          final Recording saved = trace;
          final MotionEngine.Config c = saved.recordedConfig.copy();
          c.mode = config.mode;
          files.execute(
              () -> {
                try {
                  Recording.Replay r = saved.replay(c);
                  ui.post(() -> showReplay(r));
                } catch (Exception e) {
                  ui.post(() -> error(e));
                }
              });
        });
    detailView = new TextView(this);
    detailView.setTextSize(12);
    body.addView(detailView);
    text(
        "Local traces are sampled at the 30 Hz display cadence. Pause or a gap ends the trial."
            + " Replay fixes physical input, but cannot predict human adaptation or alternative hit"
            + " rates. Stability tuning is intended for a stationary recording; it rewards reduced"
            + " movement and can slow aiming. Review held-out metrics before applying.");
  }

  private void runReplay(boolean sweep) {
    input.post(
        () -> {
          recording = false;
          if (replayBusy) {
            note = "Replay already running";
            return;
          }
          if (trace.size() < 100) {
            note = "Record at least 100 samples first";
            return;
          }
          replayBusy = true;
          final Recording saved = trace;
          final MotionEngine.Config cfg = saved.recordedConfig.copy();
          note = "Running replay on analysis thread";
          files.execute(
              () -> {
                try {
                  if (sweep) {
                    Recording.SweepResult r = saved.sweep(cfg, Recording.Objective.STABILITY);
                    ui.post(
                        () ->
                            bottomText(
                                "Stationary replay tuning • proxy only",
                                r.csv,
                                () -> apply(r.selected.copy()),
                                "Apply selected (undo available)"));
                  } else {
                    String csv = saved.compare(cfg);
                    ui.post(
                        () ->
                            bottomText(
                                "Same-input replay comparison",
                                csv,
                                () -> export("gyrolab-comparison.csv", csv, null, null),
                                "Export CSV"));
                  }
                } catch (Exception e) {
                  ui.post(() -> error(e));
                } finally {
                  replayBusy = false;
                }
              });
        });
  }

  private void showReplay(Recording.Replay r) {
    if (r.x.length == 0) {
      note = "Recording is empty";
      return;
    }
    ReplayView v = new ReplayView(this, r);
    LinearLayout panel = new LinearLayout(this);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.addView(v, new LinearLayout.LayoutParams(-1, dp(260)));
    TextView caption = new TextView(this);
    caption.setText(
        "Cumulative generated counts; auto-scaled. Same physical trace, no human-performance"
            + " claim.\n"
            + "Path "
            + r.pathCounts
            + " counts; net "
            + r.netCounts);
    panel.addView(caption);
    bottom("Replay trajectory", panel, null, null);
  }

  private void apply(MotionEngine.Config c) {
    try {
      c.validate();
    } catch (Exception e) {
      error(e);
      return;
    }
    input.post(
        () -> {
          recording = false;
          previousConfig = config.copy();
          config = c.copy();
          engine = new MotionEngine(config.copy());
          resetInput();
          ConfigStore.saveCurrent(this, config);
          note = "Settings applied: " + MotionEngine.MODE_NAMES[config.mode];
        });
  }

  private void resetCursor() {
    synchronized (cursorLock) {
      pendingX = pendingY = 0;
      pendingSamples = 0;
    }
  }

  private void resetInput() {
    if (recording) {
      recording = false;
      note = "Recording stopped at input-state change: " + trace.size() + " samples";
    }
    if (calibrationFinish != null) {
      input.removeCallbacks(calibrationFinish);
      calibrationFinish = null;
    }
    calibrating = false;
    engine.reset();
    quantizer.clear();
    buttons = 0;
    suppressUntil = 0;
    hid.release();
    udp.release();
    resetCursor();
    ui.post(
        () -> {
          if (labView != null) labView.interrupt();
        });
  }

  private boolean engaged() {
    return tracking && !calibrating && (holdToMove ? clutchDown : !clutchDown);
  }

  private void send(int x, int y, int b, int wheel) {
    if (udpSelected) udp.send(x, y, b, wheel);
    else hid.send(x, y, b, wheel);
  }

  private void calibrate(int duration) {
    input.post(
        () -> {
          recording = false;
          tracking = false;
          resetInput();
          calibrating = true;
          calibration = new Calibration();
          note = "Keep phone stationary for " + duration / 1000 + " seconds";
          calibrationFinish =
              () -> {
                calibrating = false;
                calibrationFinish = null;
                try {
                  MotionEngine.Config c = config.copy();
                  calibration.apply(c);
                  previousConfig = config;
                  config = c;
                  engine = new MotionEngine(c.copy());
                  ConfigStore.saveCurrent(this, c);
                  note =
                      String.format(
                          Locale.US,
                          "Calibrated %d samples, %.1f Hz, noise %.5f rad/s. Enable tracking.",
                          calibration.count,
                          calibration.hz(),
                          calibration.noiseRms());
                } catch (Exception e) {
                  note = "Calibration rejected: " + e.getMessage();
                }
              };
          input.postDelayed(calibrationFinish, duration);
        });
  }

  private boolean permissions() {
    if (Build.VERSION.SDK_INT < 31) return true;
    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED
        && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
            == PackageManager.PERMISSION_GRANTED) return true;
    requestPermissions(
        new String[] {
          Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE
        },
        10);
    note = "Grant Nearby devices, then Pair / Connect";
    return false;
  }

  @Override
  public void onRequestPermissionsResult(int r, String[] p, int[] g) {
    super.onRequestPermissionsResult(r, p, g);
    if (r == 10 && permissionsGranted()) {
      startHid();
      note = "Bluetooth permission granted; Pair / discover to continue";
    } else if (r == 10) note = "Bluetooth permission denied. Local lab remains available.";
  }

  private boolean permissionsGranted() {
    return Build.VERSION.SDK_INT < 31
        || (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                == PackageManager.PERMISSION_GRANTED);
  }

  private void startHid() {
    try {
      startForegroundService(new Intent(this, PairingService.class));
      hid.start();
    } catch (RuntimeException e) { note = "Cannot keep Bluetooth foreground: " + e.getMessage(); }
  }

  private boolean pairPending;
  private void awaitPairReady(long deadline) {
    if (!foreground || isFinishing()) { pairPending = false; return; }
    if (PairingService.active && hid.isRegistered()) {
      pairPending = false;
      Intent discover = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
      discover.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
      try { startActivity(discover); note = "Pair from iPad / PC Bluetooth settings and confirm both codes."; }
      catch (SecurityException e) { note = "Bluetooth permission changed; grant Nearby devices and retry."; }
      catch (RuntimeException e) { error(e); }
    } else if (SystemClock.elapsedRealtime() < deadline) {
      ui.postDelayed(() -> awaitPairReady(deadline), 100);
    } else { pairPending = false; note = "Mouse registration not ready: " + hid.status + ". Retry Pair after Bluetooth is on."; }
  }

  private void pair() {
    if (!permissions()) return;
    startHid();
    try {
      BluetoothManager m = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
      if (m == null || m.getAdapter() == null) {
        note = "This device has no Bluetooth adapter";
        return;
      }
      if (!m.getAdapter().isEnabled()) {
        startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        note = "Enable Bluetooth, then Pair / discover";
        return;
      }
      if (!pairPending) {
        pairPending = true;
        note = "Preparing Bluetooth mouse before pairing…";
        awaitPairReady(SystemClock.elapsedRealtime() + 10000);
      }
    } catch (SecurityException e) {
      note = "Bluetooth permission changed; grant Nearby devices and retry";
    } catch (Exception e) {
      error(e);
    }
  }

  private void hosts() {
    if (!permissions()) return;
    startHid();
    List<BluetoothDevice> list = hid.bondedHosts();
    if (list.isEmpty()) {
      note = "No paired hosts. Use Pair / discover and pair from the iPad / PC.";
      return;
    }
    LinearLayout items = new LinearLayout(this);
    items.setOrientation(LinearLayout.VERTICAL);
    for (BluetoothDevice d : list) {
      Button b = new Button(this);
      String name;
      try {
        name = d.getName() + "\n" + d.getAddress();
      } catch (SecurityException e) {
        name = "Permission required";
      }
      b.setText(name);
      b.setOnClickListener(
          v -> {
            input.post(
                () -> {
                  resetInput();
                  udp.close();
                  udpSelected = false;
                  hid.connect(d);
                });
            if (items.getTag() instanceof Dialog) ((Dialog) items.getTag()).dismiss();
          });
      items.addView(b);
    }
    Dialog dialog = bottom("Select paired iPad / PC", items, null, null);
    items.setTag(dialog);
  }

  private void sensorDialog() {
    LinearLayout options = new LinearLayout(this);
    options.setOrientation(LinearLayout.VERTICAL);
    for (boolean raw : new boolean[] {false, true})
      for (int period : new int[] {10000, 5000, 2500}) {
        Button b = new Button(this);
        b.setText(
            (raw ? "Uncalibrated gyro" : "Calibrated gyro")
                + " • request "
                + 1000000 / period
                + " Hz");
        b.setOnClickListener(
            v -> {
              rawGyro = raw;
              requestedUs = period;
              tracking = false;
              input.post(
                  () -> {
                    resetInput();
                    stats = new Calibration();
                    processed = costNs = dispatchNs = 0;
                  });
              registerSensors();
              note =
                  "Sampling request changed. Delivered rate is measured, not guaranteed."
                      + " Recalibrate before use.";
              if (options.getTag() instanceof Dialog) ((Dialog) options.getTag()).dismiss();
            });
        options.addView(b);
      }
    Dialog d = bottom("Sensor request", options, null, null);
    options.setTag(d);
  }

  private void presets(boolean delete) {
    String[] names = ConfigStore.names(this);
    LinearLayout list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);
    for (String name : names) {
      Button b = new Button(this);
      b.setText(name);
      b.setOnClickListener(
          v -> {
            try {
              if (delete) {
                ConfigStore.delete(this, name);
                note = "Preset deleted";
              } else apply(ConfigStore.load(this, name));
              if (list.getTag() instanceof Dialog) ((Dialog) list.getTag()).dismiss();
            } catch (Exception e) {
              error(e);
            }
          });
      list.addView(b);
    }
    Dialog d = bottom(delete ? "Delete preset" : "Load preset", list, null, null);
    list.setTag(d);
  }

  private void choose(String title, String[] options, java.util.function.IntConsumer selected) {
    LinearLayout list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);
    for (int n = 0; n < options.length; n++) {
      final int index = n;
      Button b = new Button(this);
      b.setText(options[n]);
      b.setOnClickListener(
          v -> {
            selected.accept(index);
            if (list.getTag() instanceof Dialog) ((Dialog) list.getTag()).dismiss();
          });
      list.addView(b);
    }
    Dialog d = bottom(title, list, null, null);
    list.setTag(d);
  }

  private void inputText(String title, String initial, java.util.function.Consumer<String> accept) {
    EditText edit = new EditText(this);
    edit.setSingleLine(true);
    edit.setText(initial);
    bottom(title, edit, () -> accept.accept(edit.getText().toString().trim()), "Apply");
  }

  private Dialog bottom(String title, View content, Runnable action, String label) {
    Dialog d = new Dialog(this);
    LinearLayout panel = new LinearLayout(this);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(12), dp(8), dp(12), dp(12));
    TextView heading = new TextView(this);
    heading.setText(title);
    heading.setTextSize(18);
    panel.addView(heading);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(content);
    panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout controls = new LinearLayout(this);
    if (action != null) {
      Button ok = new Button(this);
      ok.setText(label);
      ok.setOnClickListener(
          v -> {
            action.run();
            d.dismiss();
          });
      controls.addView(ok, new LinearLayout.LayoutParams(0, -2, 1));
    }
    Button close = new Button(this);
    close.setText("Close");
    close.setOnClickListener(v -> d.dismiss());
    controls.addView(close, new LinearLayout.LayoutParams(0, -2, 1));
    panel.addView(controls);
    d.setContentView(panel);
    Window w = d.getWindow();
    if (w != null) {
      SafeArea.dialog(this, w, .69);
      w.setBackgroundDrawableResource(android.R.color.white);
      w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    d.show();
    if (w != null) SafeArea.dialog(this, w, .69);
    return d;
  }

  private void bottomText(String title, String value, Runnable action, String label) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(12);
    t.setTextIsSelectable(true);
    bottom(title, t, action, label);
  }

  private void error(Exception e) {
    note = e.getClass().getSimpleName() + ": " + e.getMessage();
  }

  private void export(String name, String value, Recording rec, MotionEngine.Config cfg) {
    tracking = false;
    input.post(this::resetInput);
    exportName = name;
    exportText = value;
    exportTrace = rec;
    exportTraceConfig = cfg;
    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    i.setType(name.endsWith("json") ? "application/json" : "text/csv");
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.putExtra(Intent.EXTRA_TITLE, name);
    try {
      startActivityForResult(i, 31);
    } catch (Exception e) {
      error(e);
    }
  }

  private void importFile(int kind) {
    tracking = false;
    recording = false;
    input.post(this::resetInput);
    importKind = kind;
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.setType("*/*");
    i.addCategory(Intent.CATEGORY_OPENABLE);
    try {
      startActivityForResult(i, 32);
    } catch (Exception e) {
      error(e);
    }
  }

  @Override
  protected void onActivityResult(int req, int result, Intent data) {
    super.onActivityResult(req, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    if (req == 31) {
      final String value = exportText;
      final Recording rec = exportTrace;
      final MotionEngine.Config cfg = exportTraceConfig;
      files.execute(
          () -> {
            try (OutputStream stream = getContentResolver().openOutputStream(uri);
                Writer writer = new OutputStreamWriter(stream, "UTF-8")) {
              if (rec != null) rec.write(writer, cfg);
              else writer.write(value == null ? "" : value);
              note = "Exported " + exportName;
            } catch (Exception e) {
              error(e);
            }
          });
    } else if (req == 32) {
      final int kind = importKind;
      files.execute(
          () -> {
            try (InputStream stream = getContentResolver().openInputStream(uri);
                Reader reader = new InputStreamReader(stream, "UTF-8")) {
              if (kind == 1) {
                StringBuilder s = new StringBuilder();
                char[] b = new char[4096];
                int n;
                while ((n = reader.read(b)) != -1) {
                  s.append(b, 0, n);
                  if (s.length() > 100000) throw new IOException("Preset too large");
                }
                MotionEngine.Config c = ConfigStore.decode(s.toString());
                ui.post(() -> apply(c));
              } else {
                Recording loaded = Recording.read(reader);
                input.post(
                    () -> {
                      trace = loaded;
                      traceConfig = loaded.recordedConfig.copy();
                      note = "Imported " + loaded.size() + " samples, origin: " + loaded.origin;
                    });
              }
            } catch (Exception e) {
              error(e);
            }
          });
    }
  }

  private void registerSensors() {
    sensors.unregisterListener(this);
    Sensor gyro =
        sensors.getDefaultSensor(
            rawGyro ? Sensor.TYPE_GYROSCOPE_UNCALIBRATED : Sensor.TYPE_GYROSCOPE);
    Sensor accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    if (gyro == null) {
      note = "Requested gyroscope unavailable. Choose calibrated gyro.";
      return;
    }
    try {
      boolean ok = sensors.registerListener(this, gyro, requestedUs, 0, input);
      if (accel != null) sensors.registerListener(this, accel, 20000, 0, input);
      if (!ok) note = "Gyroscope registration rejected";
    } catch (SecurityException e) {
      note = "High sampling denied; request 200 Hz or below";
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    foreground = true;
    if (sensors != null) registerSensors();
    if (hid != null && permissionsGranted()) startHid();
  }

  @Override
  protected void onPause() {
    foreground = false;
    tracking = false;
    recording = false;
    clutchDown = false;
    if (labView != null) labView.interrupt();
    if (sensors != null) sensors.unregisterListener(this);
    if (input != null)
      input.post(
          () -> {
            calibrating = false;
            resetInput();
          });
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    ui.removeCallbacks(tick);
    if (sensors != null) sensors.unregisterListener(this);
    input.post(
        () -> {
          hid.close();
          udp.close();
        });
    inputThread.quitSafely();
    files.shutdown();
    stopService(new Intent(this, PairingService.class));
    super.onDestroy();
  }

  @Override
  public void onConfigurationChanged(android.content.res.Configuration c) {
    super.onConfigurationChanged(c);
    tracking = false;
    input.post(this::resetInput);
    render(page);
    note = "Orientation changed; reference reset. Enable tracking.";
  }

  public void onAccuracyChanged(Sensor sensor, int level) {
    if (sensor.getType() == Sensor.TYPE_GYROSCOPE
        || sensor.getType() == Sensor.TYPE_GYROSCOPE_UNCALIBRATED) accuracy = level;
  }

  public void onSensorChanged(SensorEvent e) {
    if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
      ax = e.values[0];
      ay = e.values[1];
      az = e.values[2];
      accelNs = e.timestamp;
      return;
    }
    long start = SystemClock.elapsedRealtimeNanos();
    double gx = e.values[0], gy = e.values[1], gz = e.values[2];
    stats.add(e.timestamp, gx, gy, gz);
    if (start - e.timestamp > 100000000L) {
      staleSamples++;
      if (!staleGate) {
        staleGate = true;
        resetInput();
        note = "Discarded sensor data older than 100ms; tracking reference reset";
      }
      return;
    }
    staleGate = false;
    if (calibrating && calibration != null) calibration.add(e.timestamp, gx, gy, gz);
    boolean fresh = Math.abs(e.timestamp - accelNs) < 100000000L;
    double x = fresh ? ax : 0, y = fresh ? ay : 0, z = fresh ? az : 0;
    if (recording && !trace.add(e.timestamp, gx, gy, gz, x, y, z)) {
      recording = false;
      note = "Recording full or invalid sample; export current trace";
    }
    if (engaged()) {
      engine.process(e.timestamp, gx, gy, gz, x, y, z);
      if (start >= suppressUntil) {
        quantizer.add(engine.dx, engine.dy);
        if (quantizer.backlog() > 8128) {
          tracking = false;
          resetInput();
          note = "Safety stop: excessive accumulated motion. Lower gain and resume.";
        } else {
          double px = engine.dx, py = engine.dy;
          int split = 0;
          while (quantizer.drain() && split++ < 65) send(quantizer.dx, quantizer.dy, buttons, 0);
          synchronized (cursorLock) {
            pendingX += px;
            pendingY += py;
            pendingSamples++;
          }
        }
      } else quantizer.clear();
    }
    processed++;
    costNs += SystemClock.elapsedRealtimeNanos() - start;
    dispatchNs += Math.max(0, start - e.timestamp);
    if (processed % 100 == 0)
      telemetry =
          String.format(
              Locale.US,
              "Requested %d Hz; measured %.1f Hz\n"
                  + "Interval p50 %.2f / p95 %.2f / p99 %.2f ms • jitter %.3f ms\n"
                  + "Samples %d • bad timestamps %d • gaps %d\n"
                  + "Mean processing %.1f μs • dispatch %.2f ms\n"
                  + "Gyro %.4f %.4f %.4f rad/s • accuracy %d\n"
                  + "Still confidence %.2f • last angular step %.3f %.3f rad\n"
                  + "Quaternion %.3f %.3f %.3f %.3f\n"
                  + "HID submitted %d • rejected %d • disconnects %d\n"
                  + "Fractional backlog %.4f counts • recording %d samples",
              1000000 / requestedUs,
              stats.hz(),
              stats.percentileMs(.5),
              stats.percentileMs(.95),
              stats.percentileMs(.99),
              stats.jitterMs(),
              processed,
              stats.duplicates,
              stats.gaps,
              costNs / (double) processed / 1000,
              dispatchNs / (double) processed / 1e6,
              gx,
              gy,
              gz,
              accuracy,
              engine.stationaryConfidence,
              engine.angularX,
              engine.angularY,
              engine.orientation.w,
              engine.orientation.x,
              engine.orientation.y,
              engine.orientation.z,
              hid.reportsSubmitted,
              hid.rejectedReports,
              hid.disconnectEvents,
              quantizer.backlog(),
              trace.size());
  }

  private void updateStatus() {
    if (statusView != null)
      statusView.setText(
          (udpSelected ? udp.status : hid.status)
              + "\n"
              + (tracking ? "Enabled" : "Paused")
              + (clutchDown ? " • clutch held" : "")
              + (calibrating ? " • calibrating" : "")
              + "\n"
              + note);
    if (modeButton != null) modeButton.setText("Mode: " + MotionEngine.MODE_NAMES[config.mode]);
    if (taskButton != null) taskButton.setText("Task: " + labKind + " • restart / choose");
    if (trackingButton != null)
      trackingButton.setText(tracking ? "PAUSE tracking" : "ENABLE tracking");
    if (recordButton != null) recordButton.setText(recording ? "STOP recording" : "Record motion");
    if (detailView != null)
      detailView.setText(
          (labView == null ? "" : labView.summary() + "\n")
              + telemetry
              + "\nStale sensor samples discarded: "
              + staleSamples);
  }

  private final Runnable tick =
      new Runnable() {
        long last;

        public void run() {
          double x, y;
          long n;
          synchronized (cursorLock) {
            x = pendingX;
            y = pendingY;
            n = pendingSamples;
            pendingX = pendingY = 0;
            pendingSamples = 0;
          }
          if (labView != null && n > 0) labView.move(x, y);
          long now = SystemClock.elapsedRealtime();
          if (now - last >= 250) {
            last = now;
            updateStatus();
          }
          ui.postDelayed(this, 33);
        }
      };

  @Override
  public boolean onKeyDown(int key, KeyEvent e) {
    if (volumeClicks
        && (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN)) {
      if (e.getRepeatCount() == 0) {
        int mask = key == 24 ? 1 : 2;
        input.post(
            () -> {
              buttons |= mask;
              send(0, 0, buttons, 0);
            });
      }
      return true;
    }
    return super.onKeyDown(key, e);
  }

  @Override
  public boolean onKeyUp(int key, KeyEvent e) {
    if (volumeClicks && (key == 24 || key == 25)) {
      int mask = key == 24 ? 1 : 2;
      input.post(
          () -> {
            buttons &= ~mask;
            send(0, 0, buttons, 0);
          });
      return true;
    }
    return super.onKeyUp(key, e);
  }

  private static final class ReplayView extends View {
    final Recording.Replay r;
    final android.graphics.Paint p = new android.graphics.Paint(3);

    ReplayView(Context c, Recording.Replay r) {
      super(c);
      this.r = r;
    }

    protected void onDraw(android.graphics.Canvas canvas) {
      super.onDraw(canvas);
      canvas.drawColor(Color.WHITE);
      double minX = 0, maxX = 0, minY = 0, maxY = 0;
      for (int i = 0; i < r.x.length; i++) {
        minX = Math.min(minX, r.x[i]);
        maxX = Math.max(maxX, r.x[i]);
        minY = Math.min(minY, r.y[i]);
        maxY = Math.max(maxY, r.y[i]);
      }
      double scale =
          Math.min(
              (getWidth() - 24) / Math.max(1, maxX - minX),
              (getHeight() - 24) / Math.max(1, maxY - minY));
      android.graphics.Path path = new android.graphics.Path();
      for (int i = 0; i < r.x.length; i++) {
        float x = (float) (12 + (r.x[i] - minX) * scale),
            y = (float) (12 + (r.y[i] - minY) * scale);
        if (i == 0) path.moveTo(x, y);
        else path.lineTo(x, y);
      }
      p.setColor(Color.rgb(20, 80, 160));
      p.setStrokeWidth(2);
      p.setStyle(android.graphics.Paint.Style.STROKE);
      canvas.drawPath(path, p);
    }
  }
}
