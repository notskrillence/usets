package io.gyrolab.core;

/** Unit quaternion mapping device vectors into the reference frame. No sample allocations. */
public final class Quaternion {
  public double w = 1, x, y, z;

  public Quaternion identity() {
    w = 1;
    x = y = z = 0;
    return this;
  }

  public Quaternion set(Quaternion q) {
    w = q.w;
    x = q.x;
    y = q.y;
    z = q.z;
    return this;
  }

  public Quaternion normalize() {
    double n = Math.sqrt(w * w + x * x + y * y + z * z);
    if (n < 1e-15 || !Double.isFinite(n)) return identity();
    w /= n;
    x /= n;
    y /= n;
    z /= n;
    return this;
  }

  public Quaternion integrate(double gx, double gy, double gz, double dt) {
    double speed = Math.sqrt(gx * gx + gy * gy + gz * gz), a = speed * dt * .5;
    double s = speed < 1e-10 ? dt * .5 : Math.sin(a) / speed,
        c = Math.cos(a),
        ix = gx * s,
        iy = gy * s,
        iz = gz * s;
    double nw = w * c - x * ix - y * iy - z * iz,
        nx = w * ix + x * c + y * iz - z * iy,
        ny = w * iy - x * iz + y * c + z * ix,
        nz = w * iz + x * iy - y * ix + z * c;
    w = nw;
    x = nx;
    y = ny;
    z = nz;
    return normalize();
  }

  public double rotationComponent(int axis) {
    // A continuous quaternion sign is retained by integration. Wrapping is handled by caller.
    double n = Math.sqrt(x * x + y * y + z * z);
    if (n < 1e-12) return 0;
    return 2 * Math.atan2(n, w) * (axis == 0 ? x : axis == 1 ? y : z) / n;
  }

  public double inverseGravityX(double rx, double ry, double rz) {
    return (1 - 2 * (y * y + z * z)) * rx + 2 * (x * y + w * z) * ry + 2 * (x * z - w * y) * rz;
  }

  public double inverseGravityY(double rx, double ry, double rz) {
    return 2 * (x * y - w * z) * rx + (1 - 2 * (x * x + z * z)) * ry + 2 * (y * z + w * x) * rz;
  }

  public double inverseGravityZ(double rx, double ry, double rz) {
    return 2 * (x * z + w * y) * rx + 2 * (y * z - w * x) * ry + (1 - 2 * (x * x + y * y)) * rz;
  }
}
