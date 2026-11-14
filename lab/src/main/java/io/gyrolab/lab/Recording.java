package io.gyrolab.lab;

import io.gyrolab.core.MotionEngine;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;

/** Bounded raw sensor recording and deterministic, transport-independent replay. */
public final class Recording {
  public static final int FORMAT_VERSION = 1;
  public static final int MAX_IMPORT_SAMPLES = 240000;
  private final long[] time;
  private final double[][] channels;
  private int count;
  public String origin = "live_sensor";
  public MotionEngine.Config recordedConfig = new MotionEngine.Config();

  public Recording() {
    this(120000);
  }

  public Recording(int capacity) {
    if (capacity < 1 || capacity > MAX_IMPORT_SAMPLES)
      throw new IllegalArgumentException("capacity 1..240000");
    time = new long[capacity];
    channels = new double[6][capacity];
  }

  /**
   * Returns false for full storage or invalid values. Duplicate timestamps are preserved for
   * diagnosis.
   */
  public synchronized boolean add(
      long tsNs, double gx, double gy, double gz, double ax, double ay, double az) {
    if (count == time.length
        || tsNs < 0
        || !finite(gx)
        || !finite(gy)
        || !finite(gz)
        || !finite(ax)
        || !finite(ay)
        || !finite(az)) return false;
    time[count] = tsNs;
    channels[0][count] = gx;
    channels[1][count] = gy;
    channels[2][count] = gz;
    channels[3][count] = ax;
    channels[4][count] = ay;
    channels[5][count] = az;
    count++;
    return true;
  }

  public synchronized int size() {
    return count;
  }

  public synchronized int capacity() {
    return time.length;
  }

  public synchronized void clear() {
    count = 0;
  }

  public synchronized long timestamp(int index) {
    if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
    return time[index];
  }

  public synchronized double value(int channel, int index) {
    if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
    return channels[channel][index];
  }

  private static boolean finite(double v) {
    return !Double.isNaN(v) && !Double.isInfinite(v);
  }

  /** Writer ownership stays with the caller. Configuration is the state at recording start. */
  public synchronized void write(Writer writer, MotionEngine.Config initialConfig)
      throws IOException {
    BufferedWriter out =
        writer instanceof BufferedWriter ? (BufferedWriter) writer : new BufferedWriter(writer);
    out.write(
        "# GYROLAB_RECORDING,1\n# units,timestamp=monotonic_ns,gyro=rad_per_s,accel=m_per_s2\n");
    out.write("# origin=" + origin.replace('\n', ' ').replace('\r', ' ') + "\n");
    for (Field f : sortedFields()) {
      try {
        out.write("# config." + f.getName() + "=" + f.get(initialConfig).toString() + "\n");
      } catch (IllegalAccessException e) {
        throw new IOException(e);
      }
    }
    out.write("tsNs,gx,gy,gz,ax,ay,az\n");
    for (int i = 0; i < count; i++) {
      out.write(Long.toString(time[i]));
      for (int c = 0; c < 6; c++) {
        out.write(',');
        out.write(Double.toString(channels[c][i]));
      }
      out.write('\n');
    }
    out.flush();
  }

  public void write(Writer writer) throws IOException {
    write(writer, recordedConfig);
  }

  public static Recording read(Reader reader) throws IOException {
    BufferedReader in =
        reader instanceof BufferedReader ? (BufferedReader) reader : new BufferedReader(reader);
    Recording recording = new Recording(MAX_IMPORT_SAMPLES);
    boolean version = false, header = false;
    String line;
    int lineNumber = 0;
    while ((line = in.readLine()) != null) {
      lineNumber++;
      if (line.length() > 4096) throw new IOException("Oversized line " + lineNumber);
      if (line.trim().isEmpty()) continue;
      if (line.equals("# GYROLAB_RECORDING,1")) {
        version = true;
        continue;
      }
      if (line.startsWith("# GYROLAB_RECORDING,"))
        throw new IOException("Unsupported recording version");
      if (line.startsWith("# origin=")) {
        recording.origin = line.substring(9);
        continue;
      }
      if (line.startsWith("# config.")) {
        int equal = line.indexOf('=');
        if (equal < 10) throw new IOException("Invalid config line " + lineNumber);
        String name = line.substring(9, equal), value = line.substring(equal + 1);
        try {
          Field f = MotionEngine.Config.class.getField(name);
          if (f.getType() == double.class) {
            double v = Double.parseDouble(value);
            if (!finite(v)) throw new NumberFormatException();
            f.setDouble(recording.recordedConfig, v);
          } else if (f.getType() == int.class)
            f.setInt(recording.recordedConfig, Integer.parseInt(value));
          else if (f.getType() == boolean.class) {
            if (!value.equals("true") && !value.equals("false")) throw new NumberFormatException();
            f.setBoolean(recording.recordedConfig, Boolean.parseBoolean(value));
          }
        } catch (NoSuchFieldException ignored) {
          /* forward-compatible metadata */
        } catch (Exception e) {
          throw new IOException("Invalid config value at line " + lineNumber, e);
        }
        continue;
      }
      if (line.startsWith("#")) continue;
      if (line.equals("tsNs,gx,gy,gz,ax,ay,az")) {
        if (header) throw new IOException("Duplicate header");
        header = true;
        continue;
      }
      if (!version || !header) throw new IOException("Missing GyroLab v1 header");
      String[] parts = line.split(",", -1);
      if (parts.length != 7) throw new IOException("Expected seven columns at line " + lineNumber);
      try {
        long ts = Long.parseLong(parts[0]);
        double[] v = new double[6];
        for (int i = 0; i < 6; i++) v[i] = Double.parseDouble(parts[i + 1]);
        if (!recording.add(ts, v[0], v[1], v[2], v[3], v[4], v[5]))
          throw new IOException(
              "Invalid sample or recording exceeds 240000 samples at line " + lineNumber);
      } catch (NumberFormatException e) {
        throw new IOException("Invalid number at line " + lineNumber, e);
      }
    }
    if (!version || !header) throw new IOException("Not a GyroLab v1 recording");
    try {
      recording.recordedConfig.validate();
    } catch (IllegalArgumentException e) {
      throw new IOException("Invalid recorded configuration", e);
    }
    return recording;
  }

  private static Field[] sortedFields() {
    Field[] all = MotionEngine.Config.class.getFields();
    Arrays.sort(
        all,
        new Comparator<Field>() {
          public int compare(Field a, Field b) {
            return a.getName().compareTo(b.getName());
          }
        });
    List<Field> fields = new ArrayList<Field>();
    for (Field f : all)
      if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
          && (f.getType() == double.class
              || f.getType() == int.class
              || f.getType() == boolean.class)) fields.add(f);
    return fields.toArray(new Field[0]);
  }

  public static final class Replay {
    /** Cumulative output counts, not Windows screen pixels. */
    public final double[] x, y;

    public final double pathCounts, netCounts, rmsSpeedCountsPerSecond;

    /** Measured computation cost on the runtime executing replay; not transport or end-to-end latency. */
    public final double nanosecondsPerSample;

    Replay(double[] x, double[] y, double path, double rms, double ns) {
      this.x = x;
      this.y = y;
      pathCounts = path;
      rmsSpeedCountsPerSecond = rms;
      nanosecondsPerSample = ns;
      netCounts = x.length == 0 ? 0 : Math.hypot(x[x.length - 1], y[y.length - 1]);
    }
  }

  public synchronized Replay replay(MotionEngine.Config cfg) {
    return replayRange(cfg, 0, count);
  }

  private Replay replayRange(MotionEngine.Config cfg, int from, int end) {
    MotionEngine e = new MotionEngine(cfg.copy());
    double[] x = new double[end - from], y = new double[end - from];
    double px = 0, py = 0, path = 0, speedSquared = 0;
    int speedCount = 0;
    long cpu = 0;
    for (int i = from; i < end; i++) {
      long start = System.nanoTime();
      e.process(
          time[i],
          channels[0][i],
          channels[1][i],
          channels[2][i],
          channels[3][i],
          channels[4][i],
          channels[5][i]);
      cpu += System.nanoTime() - start;
      px += e.dx;
      py += e.dy;
      path += Math.hypot(e.dx, e.dy);
      x[i - from] = px;
      y[i - from] = py;
      if (i > from) {
        double dt = (time[i] - time[i - 1]) * 1e-9;
        if (dt > 0 && dt <= .1) {
          double speed = Math.hypot(e.dx, e.dy) / dt;
          speedSquared += speed * speed;
          speedCount++;
        }
      }
    }
    return new Replay(
        x,
        y,
        path,
        speedCount == 0 ? 0 : Math.sqrt(speedSquared / speedCount),
        end == from ? 0 : (double) cpu / (end - from));
  }

  /** Each mode sees exactly the same samples/config; timings intentionally vary between runs. */
  public synchronized String compare(MotionEngine.Config base) {
    StringBuilder out =
        new StringBuilder(
            "# Counterfactual replay; output counts are not screen pixels. No human acquisition"
                + " claims.\n");
    out.append("# origin=").append(origin).append(",samples=").append(count).append("\n");
    out.append(
        "mode,path_counts,net_displacement_counts,rms_speed_counts_s,trajectory_rms_difference_from_direct_counts,replay_compute_ns_sample\n");
    MotionEngine.Config direct = base.copy();
    direct.mode = 0;
    Replay reference = replayRange(direct, 0, count);
    for (int mode = 0; mode < MotionEngine.MODE_NAMES.length; mode++) {
      MotionEngine.Config c = base.copy();
      c.mode = mode;
      Replay r = replayRange(c, 0, count);
      double squared = 0;
      for (int i = 0; i < count; i++) {
        double dx = r.x[i] - reference.x[i], dy = r.y[i] - reference.y[i];
        squared += dx * dx + dy * dy;
      }
      out.append('"')
          .append(MotionEngine.MODE_NAMES[mode].replace("\"", "\"\""))
          .append('"')
          .append(',')
          .append(r.pathCounts)
          .append(',')
          .append(r.netCounts)
          .append(',')
          .append(r.rmsSpeedCountsPerSecond)
          .append(',')
          .append(count == 0 ? 0 : Math.sqrt(squared / count))
          .append(',')
          .append(r.nanosecondsPerSample)
          .append('\n');
    }
    return out.toString();
  }

  public enum Objective {
    STABILITY,
    TRACKING,
    BALANCED
  }

  public static final class SweepResult {
    public final MotionEngine.Config selected;
    public final String csv;
    public final double trainingScore, heldOutScore;

    SweepResult(MotionEngine.Config selected, String csv, double train, double held) {
      this.selected = selected;
      this.csv = csv;
      trainingScore = train;
      heldOutScore = held;
    }

    @Override
    public String toString() {
      return csv;
    }
  }

  /**
   * Small held-out One Euro sweep. Scores are engineering proxies, never human target performance.
   */
  public synchronized SweepResult sweep(MotionEngine.Config base, Objective objective) {
    if (count < 100)
      throw new IllegalStateException("Record at least 100 samples before replay tuning");
    int split = count * 2 / 3;
    MotionEngine.Config direct = base.copy();
    direct.mode = 0;
    Replay trainReference = replayRange(direct, 0, split),
        testReference = replayRange(direct, split, count);
    StringBuilder out =
        new StringBuilder(
            "# One Euro replay parameter sweep. Lower proxy score is better; NOT a human pointing"
                + " score.\n");
    out.append("# objective=")
        .append(objective)
        .append(",train_samples=")
        .append(split)
        .append(",held_out_samples=")
        .append(count - split)
        .append("\n");
    out.append(
        "# Each segment starts with reset state. STABILITY minimizes slow-input output plus a"
            + " motion-distortion penalty. TRACKING minimizes deviation from direct output plus"
            + " roughness. BALANCED combines these proxies.\n");
    out.append("min_cutoff_hz,beta,training_proxy,held_out_proxy\n");
    double[] cutoffs = {.5, 1, 2, 4, 8}, betas = {0, .1, .5, 2};
    double best = Double.POSITIVE_INFINITY, bestHeld = 0;
    MotionEngine.Config chosen = base.copy();
    for (double cutoff : cutoffs)
      for (double beta : betas) {
        MotionEngine.Config c = base.copy();
        c.mode = 2;
        c.minCutoffHz = cutoff;
        c.beta = beta;
        Replay train = replayRange(c, 0, split), test = replayRange(c, split, count);
        double score = proxy(train, trainReference, 0, split, base, objective),
            held = proxy(test, testReference, split, count, base, objective);
        out.append(cutoff)
            .append(',')
            .append(beta)
            .append(',')
            .append(score)
            .append(',')
            .append(held)
            .append('\n');
        if (score < best) {
          best = score;
          bestHeld = held;
          chosen = c;
        }
      }
    out.append("# Selected using training only: cutoff=")
        .append(chosen.minCutoffHz)
        .append(",beta=")
        .append(chosen.beta)
        .append("; held-out proxy=")
        .append(bestHeld)
        .append(". Retest with NEW human trials before adopting.\n");
    return new SweepResult(chosen, out.toString(), best, bestHeld);
  }

  private double proxy(
      Replay r, Replay ref, int from, int end, MotionEngine.Config cfg, Objective objective) {
    double error = 0, energy = 0, noise = 0, noiseRef = 0, rough = 0, roughRef = 0;
    int slowSamples = 0;
    double prevVx = 0, prevVy = 0, prevRx = 0, prevRy = 0;
    for (int j = 1; j < end - from; j++) {
      double dt = (time[from + j] - time[from + j - 1]) * 1e-9;
      if (dt <= 0 || dt > .1) continue;
      double vx = (r.x[j] - r.x[j - 1]) / dt, vy = (r.y[j] - r.y[j - 1]) / dt;
      double rx = (ref.x[j] - ref.x[j - 1]) / dt, ry = (ref.y[j] - ref.y[j - 1]) / dt;
      error += (vx - rx) * (vx - rx) + (vy - ry) * (vy - ry);
      energy += rx * rx + ry * ry;
      double gx = channels[0][from + j] - cfg.biasX,
          gy = channels[1][from + j] - cfg.biasY,
          gz = channels[2][from + j] - cfg.biasZ;
      if (Math.sqrt(gx * gx + gy * gy + gz * gz) < cfg.stationaryThreshold) {
        noise += vx * vx + vy * vy;
        noiseRef += rx * rx + ry * ry;
        slowSamples++;
      }
      rough += (vx - prevVx) * (vx - prevVx) + (vy - prevVy) * (vy - prevVy);
      roughRef += (rx - prevRx) * (rx - prevRx) + (ry - prevRy) * (ry - prevRy);
      prevVx = vx;
      prevVy = vy;
      prevRx = rx;
      prevRy = ry;
    }
    double distortion = error / Math.max(1e-12, energy),
        smoothness = rough / Math.max(1e-12, roughRef);
    double still = slowSamples == 0 ? smoothness : noise / Math.max(1e-12, noiseRef);
    if (objective == Objective.STABILITY) return still + .25 * distortion;
    if (objective == Objective.TRACKING) return distortion + .02 * smoothness;
    return .5 * still + .5 * distortion;
  }
}
