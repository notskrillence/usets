package io.gyrolab.core;

/** Stationary calibration statistics: Welford means/variance and measured interval distribution. */
public final class Calibration {
  public long count, firstNs, lastNs, duplicates, gaps;
  public double meanX, meanY, meanZ, m2X, m2Y, m2Z, meanIntervalNs, m2IntervalNs;
  private long intervals;
  private final long[] intervalRing = new long[4096];
  private int ringCount;

  public void add(long ts, double x, double y, double z) {
    if (!Double.isFinite(x + y + z)) return;
    if (count > 0 && ts <= lastNs) {
      duplicates++;
      return;
    }
    if (count == 0) firstNs = ts;
    if (count > 0) {
      long dt = ts - lastNs;
      intervals++;
      double d = dt - meanIntervalNs;
      meanIntervalNs += d / intervals;
      m2IntervalNs += d * (dt - meanIntervalNs);
      if (dt > 50000000L) gaps++;
      intervalRing[ringCount++ % intervalRing.length] = dt;
    }
    lastNs = ts;
    count++;
    double d = x - meanX;
    meanX += d / count;
    m2X += d * (x - meanX);
    d = y - meanY;
    meanY += d / count;
    m2Y += d * (y - meanY);
    d = z - meanZ;
    meanZ += d / count;
    m2Z += d * (z - meanZ);
  }

  public double varianceX() {
    return count > 1 ? m2X / (count - 1) : 0;
  }

  public double varianceY() {
    return count > 1 ? m2Y / (count - 1) : 0;
  }

  public double varianceZ() {
    return count > 1 ? m2Z / (count - 1) : 0;
  }

  public double noiseRms() {
    return Math.sqrt(varianceX() + varianceY() + varianceZ());
  }

  public double hz() {
    return meanIntervalNs > 0 ? 1e9 / meanIntervalNs : 0;
  }

  public double jitterMs() {
    return intervals > 1 ? Math.sqrt(m2IntervalNs / (intervals - 1)) * 1e-6 : 0;
  }

  public double percentileMs(double quantile) {
    int n = Math.min(ringCount, intervalRing.length);
    if (n == 0) return 0;
    long[] sorted = java.util.Arrays.copyOf(intervalRing, n);
    java.util.Arrays.sort(sorted);
    return sorted[(int) Math.round(Math.max(0, Math.min(1, quantile)) * (n - 1))] * 1e-6;
  }

  public boolean usable() {
    return count >= 100
        && lastNs - firstNs >= 500000000L
        && noiseRms() < .08
        && Math.sqrt(meanX * meanX + meanY * meanY + meanZ * meanZ) < .15;
  }

  public void apply(MotionEngine.Config c) {
    if (!usable())
      throw new IllegalStateException(
          "Keep phone still: need at least 0.5 s / 100 samples with low variance and bias below"
              + " 0.15 rad/s");
    c.biasX = meanX;
    c.biasY = meanY;
    c.biasZ = meanZ;
    c.stationaryThreshold = Math.max(.003, 3 * noiseRms());
  }
}
