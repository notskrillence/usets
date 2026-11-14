package io.gyrolab.lab;

/** Actual on-device target attempts. Coordinates use the test surface's logical units. */
public final class TargetMetrics {
  private boolean active;
  private long started;
  private double targetX, targetY, radius, startX, startY, lastX, lastY, path, distance;
  private int attempts, hits, completed;
  private double elapsedSeconds, endpointError, efficiency, nominalIndex, nominalThroughput;

  public void reset() {
    active = false;
    attempts = hits = completed = 0;
    elapsedSeconds = endpointError = efficiency = nominalIndex = nominalThroughput = 0;
  }

  public void begin(
      long timestampNs, double cursorX, double cursorY, double tx, double ty, double targetRadius) {
    if (targetRadius <= 0 || !finite(targetRadius))
      throw new IllegalArgumentException("positive target radius required");
    started = timestampNs;
    startX = lastX = cursorX;
    startY = lastY = cursorY;
    targetX = tx;
    targetY = ty;
    radius = targetRadius;
    distance = Math.hypot(tx - cursorX, ty - cursorY);
    path = 0;
    active = true;
  }

  public void move(double x, double y) {
    if (active && finite(x) && finite(y)) {
      path += Math.hypot(x - lastX, y - lastY);
      lastX = x;
      lastY = y;
    }
  }

  /**
   * A miss counts as an attempt but leaves the target active; acquisition time includes
   * corrections.
   */
  public boolean click(long timestampNs, double x, double y) {
    if (!active || timestampNs < started || !finite(x) || !finite(y)) return false;
    move(x, y);
    attempts++;
    double error = Math.hypot(x - targetX, y - targetY);
    endpointError += error;
    if (error > radius) return false;
    hits++;
    completed++;
    active = false;
    double seconds = (timestampNs - started) * 1e-9;
    double straightEndpointDistance = Math.hypot(x - startX, y - startY);
    elapsedSeconds += seconds;
    efficiency +=
        path == 0
            ? (straightEndpointDistance == 0 ? 1 : 0)
            : Math.min(1, straightEndpointDistance / path);
    double id = Math.log(1 + distance / (2 * radius)) / Math.log(2);
    nominalIndex += id;
    nominalThroughput += seconds > 0 ? id / seconds : 0;
    return true;
  }

  private static boolean finite(double v) {
    return !Double.isNaN(v) && !Double.isInfinite(v);
  }

  public boolean active() {
    return active;
  }

  public int attempts() {
    return attempts;
  }

  public int hits() {
    return hits;
  }

  public double hitPercent() {
    return attempts == 0 ? 0 : 100.0 * hits / attempts;
  }

  public double meanAcquisitionSeconds() {
    return completed == 0 ? 0 : elapsedSeconds / completed;
  }

  public double meanEndpointError() {
    return attempts == 0 ? 0 : endpointError / attempts;
  }

  public double meanPathEfficiency() {
    return completed == 0 ? 0 : efficiency / completed;
  }

  public String reportCsv() {
    return "# Local click-to-acquire trial; no Windows transport latency measured. Nominal"
               + " throughput is NOT effective Fitts throughput.\n"
               + "attempts,hits,hit_percent,completed_targets,mean_acquisition_seconds,mean_endpoint_error_surface_units,mean_path_efficiency,mean_nominal_index_bits,mean_nominal_throughput_bits_s\n"
        + attempts
        + ","
        + hits
        + ","
        + hitPercent()
        + ","
        + completed
        + ","
        + meanAcquisitionSeconds()
        + ","
        + meanEndpointError()
        + ","
        + meanPathEfficiency()
        + ","
        + (completed == 0 ? 0 : nominalIndex / completed)
        + ","
        + (completed == 0 ? 0 : nominalThroughput / completed)
        + "\n";
  }
}
