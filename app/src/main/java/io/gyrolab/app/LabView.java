package io.gyrolab.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import io.gyrolab.lab.TargetMetrics;
import java.util.Locale;

/** Native on-device experiment surface. Only move() changes the cursor; touch never aims. */
public final class LabView extends View {
  private static final int TARGETS = 12;
  private static final int MAX_TRACE_BYTES = 2 * 1024 * 1024;
  private static final long MAX_INPUT_GAP_NS = 250000000L;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final TargetMetrics metrics = new TargetMetrics();
  private final StringBuilder trace = new StringBuilder(8192);
  private final double[] historyX = new double[64], historyY = new double[64];
  private String kind = "static";
  private String note = "Enable tracking; rotate the phone; use HIT to acquire targets.";
  private double cursorX, cursorY, virtualX, virtualY, targetX, targetY, radius;
  private double left, right, top, bottom, centerX, centerY;
  private int acquired, historyCount, historyNext;
  private long startedNs, lastIntegralNs, lastTraceNs, inputUpdates, lastInputNs;
  private double startVirtualX, startVirtualY, errorSquaredSeconds, measuredSeconds, generatedPath;
  private boolean initialized, completed, truncated, interrupted;
  private boolean attached;

  public LabView(Context context) {
    super(context);
    setBackgroundColor(Color.rgb(15, 22, 30));
    setContentDescription(
        "Local gyroscope accuracy laboratory. Rotate phone to move crosshair. Use separate HIT"
            + " button; touching this surface does not aim.");
    setFocusable(false);
    setMinimumHeight(dp(230));
  }

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private static boolean finite(double v) {
    return !Double.isNaN(v) && !Double.isInfinite(v);
  }

  private static double clamp(double value, double low, double high) {
    return Math.max(low, Math.min(high, value));
  }

  private boolean timed() {
    return "moving".equals(kind) || "stationary".equals(kind);
  }

  private double durationSeconds() {
    return "moving".equals(kind) ? 15.0 : 10.0;
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    if (w > 0 && h > 0) initialize();
  }

  /** Supported tasks: static, micro, horizontal, vertical, moving, stationary. */
  public void restart(String requested) {
    if (!"static".equals(requested)
        && !"micro".equals(requested)
        && !"horizontal".equals(requested)
        && !"vertical".equals(requested)
        && !"moving".equals(requested)
        && !"stationary".equals(requested))
      throw new IllegalArgumentException("Unknown local task: " + requested);
    kind = requested;
    if (getWidth() > 0 && getHeight() > 0) initialize();
    else initialized = false;
  }

  private void initialize() {
    removeCallbacks(frame);
    double margin = Math.min(dp(18), Math.min(getWidth(), getHeight()) * .08);
    left = margin;
    right = Math.max(left + 1, getWidth() - margin);
    top = Math.min(dp(48), getHeight() * .22);
    bottom = Math.max(top + 1, getHeight() - Math.min(dp(34), getHeight() * .14));
    centerX = (left + right) / 2;
    centerY = (top + bottom) / 2;
    cursorX = virtualX = centerX;
    cursorY = virtualY = centerY;
    startVirtualX = virtualX;
    startVirtualY = virtualY;
    double wantedRadius = "micro".equals(kind) ? dp(6) : dp(17);
    radius = Math.max(2, Math.min(wantedRadius, Math.min(right - left, bottom - top) * .12));
    acquired = historyCount = historyNext = 0;
    startedNs = lastIntegralNs = lastTraceNs = inputUpdates = lastInputNs = 0;
    errorSquaredSeconds = measuredSeconds = generatedPath = 0;
    completed = truncated = interrupted = false;
    initialized = true;
    metrics.reset();
    trace.setLength(0);
    note =
        timed()
            ? "Enable tracking. Timed task begins on the first live core update."
            : "Enable tracking to begin the first target; rotate to aim, then press HIT.";
    if (timed()) updateMovingTarget(0);
    else selectTarget(SystemClock.elapsedRealtimeNanos());
    invalidate();
  }

  private void selectTarget(long now) {
    double spanX = Math.max(1, (right - left) / 2 - radius);
    double spanY = Math.max(1, (bottom - top) / 2 - radius);
    // Seeded deterministic geometry (same sequence for every run of this task).
    double phase = 0.619 + acquired * 2.399963229728653;
    if ("horizontal".equals(kind)) {
      targetX = centerX + ((acquired & 1) == 0 ? .78 : -.78) * spanX;
      targetY = centerY;
    } else if ("vertical".equals(kind)) {
      targetX = centerX;
      targetY = centerY + ((acquired & 1) == 0 ? -.78 : .78) * spanY;
    } else if ("micro".equals(kind)) {
      targetX = centerX + Math.cos(phase) * Math.min(spanX * .35, dp(36));
      targetY = centerY + Math.sin(phase) * Math.min(spanY * .35, dp(36));
    } else {
      targetX = centerX + Math.cos(phase) * spanX * .82;
      targetY = centerY + Math.sin(phase) * spanY * .82;
    }
    // Layout may precede tracking by minutes; never start its first acquisition clock there.
    if (inputUpdates > 0) metrics.begin(now, cursorX, cursorY, targetX, targetY, radius);
    appendTrace(now, "target");
  }

  private void updateMovingTarget(double seconds) {
    if ("stationary".equals(kind)) {
      targetX = centerX;
      targetY = centerY;
      return;
    }
    // Analytic, repeatable Lissajous target. This is task geometry, never synthetic sensor input.
    double rx = Math.max(1, (right - left) / 2 - radius) * .72;
    double ry = Math.max(1, (bottom - top) / 2 - radius) * .66;
    targetX = centerX + rx * Math.sin(seconds * .9);
    targetY = centerY + ry * Math.sin(seconds * 1.3);
  }

  /** UI-thread input, in the same counts used by the real transport; no gain is added here. */
  public void move(double dx, double dy) {
    if (!initialized || !finite(dx) || !finite(dy) || completed) return;
    long now = SystemClock.elapsedRealtimeNanos();
    if (lastInputNs != 0 && now - lastInputNs > MAX_INPUT_GAP_NS) {
      stopInterrupted(
          "Live core updates stopped for over 250 ms; results are partial. Restart the task.", now);
      return;
    }
    lastInputNs = now;
    if (!timed() && inputUpdates == 0) {
      metrics.begin(now, cursorX, cursorY, targetX, targetY, radius);
      note = "Rotate to aim. Press HIT; a miss keeps the same target active.";
      appendTrace(now, "acquisition_started");
    }
    if (timed() && startedNs == 0) {
      startedNs = lastIntegralNs = now;
      startVirtualX = virtualX;
      startVirtualY = virtualY;
      note =
          "moving".equals(kind)
              ? "Follow the green target for 15 seconds; no clicks needed."
              : "Hold still for 10 seconds; generated-count drift is measured before screen"
                  + " clipping.";
      if (attached) postDelayed(frame, 33);
    }
    if (timed()) integrate(now);
    if (completed) return;
    inputUpdates++;
    virtualX += dx;
    virtualY += dy;
    generatedPath += Math.hypot(dx, dy);
    cursorX = clamp(cursorX + dx, left, right);
    cursorY = clamp(cursorY + dy, top, bottom);
    if (!timed()) metrics.move(cursorX, cursorY);
    historyX[historyNext] = cursorX;
    historyY[historyNext] = cursorY;
    historyNext = (historyNext + 1) % historyX.length;
    historyCount = Math.min(historyX.length, historyCount + 1);
    if (now - lastTraceNs >= 33000000L) {
      appendTrace(now, "sample");
      lastTraceNs = now;
    }
    postInvalidateOnAnimation();
  }

  /** Actual click-to-acquire, invoked by a separate control or a mapped mouse button. */
  public void click() {
    if (!initialized || completed) return;
    long now = SystemClock.elapsedRealtimeNanos();
    if (inputUpdates == 0) {
      note = "No live core input yet. Enable tracking before scoring.";
      invalidate();
      return;
    }
    if (now - lastInputNs > MAX_INPUT_GAP_NS) {
      stopInterrupted(
          "Live core updates stopped for over 250 ms; this click was not scored. Restart the task.",
          now);
      return;
    }
    if (timed()) {
      note = "Timed task: movement is scored continuously; clicks are not counted.";
      invalidate();
      return;
    }
    boolean hit = metrics.click(now, cursorX, cursorY);
    appendTrace(now, hit ? "hit" : "miss");
    if (hit) {
      acquired++;
      note = "Hit " + acquired + " / " + TARGETS;
      if (acquired >= TARGETS) {
        completed = true;
        note = "Run complete. Export results or restart with another mode.";
      } else selectTarget(now);
    } else note = "Miss; correct aim and press HIT again.";
    invalidate();
  }

  private void integrate(long now) {
    if (startedNs == 0 || completed) return;
    long end = startedNs + (long) (durationSeconds() * 1e9);
    long sample = Math.min(now, end);
    if (sample > lastIntegralNs) {
      // Time-weighted, piecewise-held cursor; the target is evaluated at each interval midpoint.
      double midpointSeconds =
          ((lastIntegralNs - startedNs) + (sample - lastIntegralNs) * .5) * 1e-9;
      updateMovingTarget(midpointSeconds);
      double ex = "stationary".equals(kind) ? virtualX - startVirtualX : cursorX - targetX;
      double ey = "stationary".equals(kind) ? virtualY - startVirtualY : cursorY - targetY;
      double seconds = (sample - lastIntegralNs) * 1e-9;
      errorSquaredSeconds += (ex * ex + ey * ey) * seconds;
      measuredSeconds += seconds;
      lastIntegralNs = sample;
    }
    updateMovingTarget((sample - startedNs) * 1e-9);
    if (now >= end) {
      completed = true;
      note = "Timed run complete. Export measured results or restart.";
      appendTrace(sample, "complete");
      removeCallbacks(frame);
    }
  }

  private final Runnable frame =
      new Runnable() {
        @Override
        public void run() {
          if (!attached || !initialized || !timed() || completed || startedNs == 0) return;
          long now = SystemClock.elapsedRealtimeNanos();
          if (now - lastInputNs > MAX_INPUT_GAP_NS) {
            stopInterrupted(
                "Live core updates stopped for over 250 ms; timed results are partial. Restart the"
                    + " task.",
                now);
            return;
          }
          integrate(now);
          if (now - lastTraceNs >= 33000000L) {
            appendTrace(now, "frame");
            lastTraceNs = now;
          }
          invalidate();
          if (!completed) postDelayed(this, 33);
        }
      };

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    attached = true;
    if (initialized && timed() && startedNs != 0 && !completed) {
      removeCallbacks(frame);
      postDelayed(frame, 33);
    }
  }

  /**
   * Stop an unfinished trial before pause, calibration, recenter, mode changes, or settings
   * changes.
   */
  public void interrupt() {
    if (!initialized || completed || (inputUpdates == 0 && metrics.attempts() == 0)) return;
    stopInterrupted(
        "Run interrupted; results are partial. Restart for a new controlled run.",
        SystemClock.elapsedRealtimeNanos());
  }

  private void stopInterrupted(String reason, long now) {
    if (completed) return;
    interrupted = true;
    completed = true;
    note = reason;
    removeCallbacks(frame);
    appendTrace(now, "interrupted");
    invalidate();
  }

  @Override
  protected void onDetachedFromWindow() {
    if (initialized && !completed && (inputUpdates > 0 || metrics.attempts() > 0)) interrupt();
    attached = false;
    removeCallbacks(frame);
    super.onDetachedFromWindow();
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    // Consume touches solely to prevent an enclosing scroll container from treating aiming as a
    // tap.
    // Neither cursor movement nor a successful hit can be generated by touching this canvas.
    return true;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (!initialized) return;
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(Color.rgb(195, 207, 222));
    paint.setTextSize(dp(12));
    canvas.drawText(
        "LOCAL ONLY • " + kind.toUpperCase(Locale.US) + " • no host delivery measured",
        dp(8),
        dp(18),
        paint);
    paint.setColor(Color.rgb(41, 57, 74));
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(dp(1));
    canvas.drawRect((float) left, (float) top, (float) right, (float) bottom, paint);
    paint.setColor(Color.rgb(64, 113, 149));
    for (int i = 1; i < historyCount; i++) {
      int a = (historyNext - historyCount + i - 1 + historyX.length) % historyX.length;
      int b = (a + 1) % historyX.length;
      canvas.drawLine(
          (float) historyX[a],
          (float) historyY[a],
          (float) historyX[b],
          (float) historyY[b],
          paint);
    }
    paint.setColor(Color.rgb(76, 223, 142));
    paint.setStrokeWidth(dp(2));
    canvas.drawCircle((float) targetX, (float) targetY, (float) radius, paint);
    if (!timed()) {
      paint.setStyle(Paint.Style.FILL);
      paint.setTextSize(dp(11));
      canvas.drawText(
          Math.min(acquired + 1, TARGETS) + "/" + TARGETS,
          (float) targetX - dp(12),
          (float) targetY + dp(4),
          paint);
    }
    paint.setColor(Color.WHITE);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(dp(2));
    float x = (float) cursorX, y = (float) cursorY, arm = dp(8);
    canvas.drawLine(x - arm, y, x + arm, y, paint);
    canvas.drawLine(x, y - arm, x, y + arm, paint);
    paint.setStyle(Paint.Style.FILL);
    paint.setTextSize(dp(11));
    paint.setColor(Color.rgb(205, 216, 231));
    String bottomText =
        timed()
            ? String.format(
                Locale.US,
                "%.1f / %.0f s   RMS %.1f %s",
                measuredSeconds,
                durationSeconds(),
                rms(),
                "stationary".equals(kind) ? "counts" : "px")
            : acquired
                + "/"
                + TARGETS
                + " acquired   "
                + String.format(
                    Locale.US,
                    "%.0f%% hits   %.2f s mean",
                    metrics.hitPercent(),
                    metrics.meanAcquisitionSeconds());
    canvas.drawText(bottomText, dp(8), getHeight() - dp(10), paint);
  }

  private double rms() {
    return measuredSeconds > 0 ? Math.sqrt(errorSquaredSeconds / measuredSeconds) : 0;
  }

  public String summary() {
    if (!initialized) return "Local lab waiting for surface layout.";
    if (timed()) {
      String metric =
          "stationary".equals(kind)
              ? String.format(
                  Locale.US,
                  "generated drift %.2f counts; RMS displacement %.2f counts",
                  Math.hypot(virtualX - startVirtualX, virtualY - startVirtualY),
                  rms())
              : String.format(Locale.US, "pursuit RMS error %.2f px", rms());
      return String.format(
          Locale.US,
          "%s %s %.1f/%.0f s; %s; %d core updates. %s",
          kind,
          interrupted
              ? "interrupted"
              : completed ? "complete" : inputUpdates == 0 ? "waiting for live input" : "running",
          measuredSeconds,
          durationSeconds(),
          metric,
          inputUpdates,
          note);
    }
    return String.format(
        Locale.US,
        "%s %d/%d targets; %.1f%% hits; mean %.3f s; endpoint error %.2f px; path efficiency %.3f."
            + " %s",
        kind,
        acquired,
        TARGETS,
        metrics.hitPercent(),
        metrics.meanAcquisitionSeconds(),
        metrics.meanEndpointError(),
        metrics.meanPathEfficiency(),
        note);
  }

  private void appendTrace(long now, String event) {
    if (trace.length() >= MAX_TRACE_BYTES) {
      truncated = true;
      return;
    }
    trace
        .append(now)
        .append(',')
        .append(event)
        .append(',')
        .append(cursorX)
        .append(',')
        .append(cursorY)
        .append(',')
        .append(virtualX)
        .append(',')
        .append(virtualY)
        .append(',')
        .append(targetX)
        .append(',')
        .append(targetY)
        .append(',')
        .append(radius)
        .append(',')
        .append(acquired)
        .append('\n');
  }

  /** Real local results and bounded cursor trace; callers may append a configuration snapshot. */
  public String resultsCsv() {
    StringBuilder out = new StringBuilder(trace.length() + 1024);
    out.append(
            "# GyroLab X local on-device test; counts map 1:1 to surface px; no Windows/iPad"
                + " delivery is measured.\n")
        .append(
            "# Fixed analytic target geometry is a test stimulus; all cursor samples come from"
                + " move().\n")
        .append("# Task,")
        .append(kind)
        .append(",complete,")
        .append(completed && !interrupted)
        .append(",interrupted,")
        .append(interrupted)
        .append(",core_updates,")
        .append(inputUpdates)
        .append(",trace_truncated,")
        .append(truncated)
        .append('\n');
    if (timed()) {
      out.append(
              "duration_observed_seconds,rms_error,net_generated_displacement_counts,generated_path_counts,error_units\n")
          .append(measuredSeconds)
          .append(',')
          .append(rms())
          .append(',')
          .append(Math.hypot(virtualX - startVirtualX, virtualY - startVirtualY))
          .append(',')
          .append(generatedPath)
          .append(',')
          .append("stationary".equals(kind) ? "generated_counts" : "surface_px")
          .append('\n');
    } else out.append(metrics.reportCsv());
    out.append(
            "\n"
                + "# Cursor trace; timestamps are Android elapsedRealtimeNanos; position changes"
                + " may precede throttled trace rows.\n")
        .append(
            "timestamp_ns,event,cursor_x_px,cursor_y_px,unbounded_x_counts,unbounded_y_counts,target_x_px,target_y_px,target_radius_px,completed_targets\n")
        .append(trace);
    return out.toString();
  }
}
