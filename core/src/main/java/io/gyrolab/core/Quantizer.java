package io.gyrolab.core;

/** Fractional counts are retained until reports can carry them. No saturation loss. */
public final class Quantizer {
  public double pendingX, pendingY;
  public int dx, dy;

  public synchronized void add(double x, double y) {
    if (!Double.isFinite(x) || !Double.isFinite(y))
      throw new IllegalArgumentException("Non-finite counts");
    double nextX = pendingX + x, nextY = pendingY + y;
    if (!Double.isFinite(nextX) || !Double.isFinite(nextY))
      throw new ArithmeticException("Count accumulator overflow");
    pendingX = nextX;
    pendingY = nextY;
  }

  /** Drain one conventional -127..127 HID report. Caller decides bounded scheduling. */
  public synchronized boolean drain() {
    dx = (int) Math.max(-127, Math.min(127, pendingX));
    dy = (int) Math.max(-127, Math.min(127, pendingY));
    pendingX -= dx;
    pendingY -= dy;
    return dx != 0 || dy != 0;
  }

  public synchronized void clear() {
    pendingX = pendingY = 0;
    dx = dy = 0;
  }

  public synchronized double backlog() {
    return Math.max(Math.abs(pendingX), Math.abs(pendingY));
  }
}
