package io.gyrolab.core;

/** Allocation-free gyro-to-relative-count engine. Inputs rad/s, m/s^2, monotonic ns. */
public final class MotionEngine {
  public static final String[] MODE_NAMES = {
    "Direct angular",
    "Fixed low-pass",
    "One Euro",
    "Orientation anchored",
    "Nonlinear precision",
    "Quaternion gravity fusion",
    "Relative / anchored hybrid",
    "Tremor notch",
    "Bounded prediction",
    "Ballistic / fine",
    "Composed pipeline",
    "Confidence dual path"
  };

  public static final class Config implements Cloneable {
    public int mode = 0, axisX = 1, axisY = 0;
    public double gainX = 900, gainY = 900, biasX, biasY, biasZ;
    public boolean invertX = false, invertY = false, gravityRelative = false, adaptBias = false;
    public double gripRollDegrees = 0,
        cutoffHz = 8,
        minCutoffHz = 1.5,
        beta = .25,
        derivativeCutoffHz = 1;
    public double power = 1.5, precisionGain = .4, speedScale = 1, blend = .25, deadzone = .002;
    public double predictionMs = 12,
        tremorHz = 10,
        tremorQ = 2,
        stationaryThreshold = .012,
        fusionGain = .5;
    public boolean customLowpass = true,
        customNonlinear = true,
        customPrediction = false,
        customDeadzone = true;

    public Config copy() {
      try {
        return (Config) clone();
      } catch (CloneNotSupportedException e) {
        throw new AssertionError(e);
      }
    }

    public void validate() {
      if (mode < 0
          || mode >= MODE_NAMES.length
          || axisX < 0
          || axisX > 2
          || axisY < 0
          || axisY > 2
          || axisX == axisY)
        throw new IllegalArgumentException("Select a mode and two distinct gyro axes");
      double[] values = {
        gainX,
        gainY,
        biasX,
        biasY,
        biasZ,
        gripRollDegrees,
        cutoffHz,
        minCutoffHz,
        beta,
        derivativeCutoffHz,
        power,
        precisionGain,
        speedScale,
        blend,
        deadzone,
        predictionMs,
        tremorHz,
        tremorQ,
        stationaryThreshold,
        fusionGain
      };
      for (double v : values)
        if (!Double.isFinite(v)) throw new IllegalArgumentException("Configuration must be finite");
      if (gainX < 0
          || gainY < 0
          || gainX > 100000
          || gainY > 100000
          || cutoffHz <= 0
          || minCutoffHz <= 0
          || derivativeCutoffHz <= 0
          || beta < 0
          || power < 1
          || power > 4
          || precisionGain < .01
          || precisionGain > 1
          || speedScale <= 0
          || blend < 0
          || blend > 1
          || deadzone < 0
          || predictionMs < 0
          || predictionMs > 40
          || tremorHz <= 0
          || tremorQ < .25
          || stationaryThreshold <= 0
          || fusionGain < 0
          || fusionGain > 10)
        throw new IllegalArgumentException("Parameter outside safe numerical range");
    }
  }

  public final Config config;
  public final Quaternion orientation = new Quaternion();
  public double dx, dy, stationaryConfidence, velocityX, velocityY, angularX, angularY;
  public long acceptedSamples, rejectedTimestamps, longGaps, invalidSamples, projectionReanchors;
  private long lastNs = Long.MIN_VALUE;
  private int lastMode = -1;
  private double lowX,
      lowY,
      euroX,
      euroY,
      dEuroX,
      dEuroY,
      prevX,
      prevY,
      slowX,
      slowY,
      phase,
      stillTime,
      composePrevX,
      composePrevY;
  private double anchorX,
      anchorY,
      prevAngleX,
      prevAngleY,
      unwrapX,
      unwrapY,
      gravityX,
      gravityY,
      gravityZ = 1;
  private boolean filterReady, angleReady, gravityReady;
  private double rightX = 1, rightY, rightZ, forwardX, forwardY, forwardZ = 1;
  private final Notch notchX = new Notch(), notchY = new Notch();

  public MotionEngine() {
    this(new Config());
  }

  public MotionEngine(Config config) {
    this.config = config;
    config.validate();
    reset();
  }

  /**
   * Reanchor without output. Discards filter history; caller must clear transport fractions too.
   */
  public void reset() {
    lastNs = Long.MIN_VALUE;
    lastMode = config.mode;
    orientation.identity();
    dx = dy = velocityX = velocityY = angularX = angularY = 0;
    lowX =
        lowY =
            euroX =
                euroY =
                    dEuroX =
                        dEuroY =
                            prevX =
                                prevY =
                                    slowX =
                                        slowY = phase = stillTime = composePrevX = composePrevY = 0;
    anchorX = anchorY = prevAngleX = prevAngleY = unwrapX = unwrapY = 0;
    filterReady = angleReady = gravityReady = false;
    stationaryConfidence = 0;
    notchX.reset();
    notchY.reset();
  }

  public void process(long ns, double gx, double gy, double gz, double ax, double ay, double az) {
    dx = dy = angularX = angularY = 0;
    if (config.mode != lastMode) reset();
    if (!finite(gx)
        || !finite(gy)
        || !finite(gz)
        || Math.max(Math.abs(gx), Math.max(Math.abs(gy), Math.abs(gz))) > 100) {
      invalidSamples++;
      return;
    }
    if (lastNs != Long.MIN_VALUE && ns <= lastNs) {
      rejectedTimestamps++;
      return;
    }
    if (lastNs == Long.MIN_VALUE) {
      lastNs = ns;
      initializeGravity(ax, ay, az);
      seedVelocity(gx, gy, gz);
      return;
    }
    double dt = (ns - lastNs) * 1e-9;
    lastNs = ns;
    if (dt > .1) {
      longGaps++;
      reset();
      lastNs = ns;
      initializeGravity(ax, ay, az);
      seedVelocity(gx, gy, gz);
      return;
    }
    acceptedSamples++;
    gx -= config.biasX;
    gy -= config.biasY;
    gz -= config.biasZ;
    double speed3 = Math.sqrt(gx * gx + gy * gy + gz * gz),
        an = Math.sqrt(ax * ax + ay * ay + az * az);
    double accelConfidence =
        finite(an) && an > 1 ? Math.exp(-Math.pow((an - 9.80665) / 1.5, 2)) : 0;
    double instantConfidence =
        Math.exp(-Math.pow(speed3 / config.stationaryThreshold, 2)) * accelConfidence;
    stationaryConfidence += (instantConfidence - stationaryConfidence) * alpha(3, dt);
    if (config.adaptBias && instantConfidence > .98) {
      stillTime += dt;
      if (stillTime > 1) {
        double ba = alpha(.015, dt);
        config.biasX += gx * ba;
        config.biasY += gy * ba;
        config.biasZ += gz * ba;
      }
    } else stillTime = 0;
    if (!gravityReady) initializeGravity(ax, ay, az);
    if (config.mode == 5 && gravityReady && accelConfidence > .5) {
      double ex = orientation.inverseGravityX(gravityX, gravityY, gravityZ),
          ey = orientation.inverseGravityY(gravityX, gravityY, gravityZ),
          ez = orientation.inverseGravityZ(gravityX, gravityY, gravityZ);
      double mx = ax / an, my = ay / an, mz = az / an, k = config.fusionGain * accelConfidence;
      gx += k * (my * ez - mz * ey);
      gy += k * (mz * ex - mx * ez);
      gz += k * (mx * ey - my * ex);
    }
    double integratedStep = Math.sqrt(gx * gx + gy * gy + gz * gz) * dt;
    orientation.integrate(gx, gy, gz, dt);
    if (config.gravityRelative && gravityReady) {
      double qw = orientation.w, qx = orientation.x, qy = orientation.y, qz = orientation.z;
      double rx =
          (1 - 2 * (qy * qy + qz * qz)) * gx
              + 2 * (qx * qy - qw * qz) * gy
              + 2 * (qx * qz + qw * qy) * gz;
      double ry =
          2 * (qx * qy + qw * qz) * gx
              + (1 - 2 * (qx * qx + qz * qz)) * gy
              + 2 * (qy * qz - qw * qx) * gz;
      double rz =
          2 * (qx * qz - qw * qy) * gx
              + 2 * (qy * qz + qw * qx) * gy
              + (1 - 2 * (qx * qx + qy * qy)) * gz;
      gx = rightX * rx + rightY * ry + rightZ * rz;
      gy = gravityX * rx + gravityY * ry + gravityZ * rz;
      gz = forwardX * rx + forwardY * ry + forwardZ * rz;
    }
    double vx = axis(config.axisX, gx, gy, gz),
        vy = axis(config.axisY, gx, gy, gz),
        r = Math.toRadians(config.gripRollDegrees),
        c = Math.cos(r),
        s = Math.sin(r);
    double tx = c * vx + s * vy;
    vy = -s * vx + c * vy;
    vx = tx;
    if (config.invertX) vx = -vx;
    if (config.invertY) vy = -vy;
    velocityX = vx;
    velocityY = vy;
    double speed = Math.hypot(vx, vy), outX = vx, outY = vy;
    // Quaternion log, unwrapped per component, then bounded tangent-plane projection.
    double logX = orientation.rotationComponent(0),
        logY = orientation.rotationComponent(1),
        logZ = orientation.rotationComponent(2);
    if (config.gravityRelative && gravityReady) {
      double rx = rightX * logX + rightY * logY + rightZ * logZ,
          ry = gravityX * logX + gravityY * logY + gravityZ * logZ,
          rz = forwardX * logX + forwardY * logY + forwardZ * logZ;
      logX = rx;
      logY = ry;
      logZ = rz;
    }
    double ox = axis(config.axisX, logX, logY, logZ), oy = axis(config.axisY, logX, logY, logZ);
    tx = c * ox + s * oy;
    oy = -s * ox + c * oy;
    ox = tx;
    if (config.invertX) ox = -ox;
    if (config.invertY) oy = -oy;
    if (!angleReady) {
      prevAngleX = 0;
      prevAngleY = 0;
      angleReady = true;
    }
    double incX = wrap(ox - prevAngleX), incY = wrap(oy - prevAngleY);
    prevAngleX = ox;
    prevAngleY = oy;
    // Quaternion logarithm is singular around complete turns. Keep the local projection
    // reference continuous across branch changes instead of converting them into motion.
    if (Math.hypot(incX, incY) > 4 * integratedStep + 1e-6) {
      incX = incY = 0;
      if (config.mode == 3 || config.mode == 6) projectionReanchors++;
    }
    unwrapX += incX;
    unwrapY += incY;
    double targetX = Math.tan(clamp(unwrapX, -1.05, 1.05)),
        targetY = Math.tan(clamp(unwrapY, -1.05, 1.05));
    double anchoredX = targetX - anchorX, anchoredY = targetY - anchorY;
    anchorX = targetX;
    anchorY = targetY;
    if (!filterReady) {
      prevX = euroX = vx;
      prevY = euroY = vy;
      filterReady = true;
    }
    double derivativeX = (vx - prevX) / dt, derivativeY = (vy - prevY) / dt;
    switch (config.mode) {
      case 0:
        break;
      case 1:
        lowX += (vx - lowX) * alpha(config.cutoffHz, dt);
        lowY += (vy - lowY) * alpha(config.cutoffHz, dt);
        outX = lowX;
        outY = lowY;
        break;
      case 2:
        dEuroX += (derivativeX - dEuroX) * alpha(config.derivativeCutoffHz, dt);
        dEuroY += (derivativeY - dEuroY) * alpha(config.derivativeCutoffHz, dt);
        euroX += (vx - euroX) * alpha(config.minCutoffHz + config.beta * Math.abs(dEuroX), dt);
        euroY += (vy - euroY) * alpha(config.minCutoffHz + config.beta * Math.abs(dEuroY), dt);
        outX = euroX;
        outY = euroY;
        break;
      case 3:
        outX = anchoredX / dt;
        outY = anchoredY / dt;
        break;
      case 4:
        {
          double gain = nonlinear(speed);
          outX *= gain;
          outY *= gain;
          break;
        }
      case 5:
        outX = vx;
        outY = vy;
        break; // local log of the integrated quaternion increment is corrected gyro*dt
      case 6:
        outX = (1 - config.blend) * vx + config.blend * anchoredX / dt;
        outY = (1 - config.blend) * vy + config.blend * anchoredY / dt;
        break;
      case 7:
        outX = notchX.apply(vx, dt, config.tremorHz, config.tremorQ);
        outY = notchY.apply(vy, dt, config.tremorHz, config.tremorQ);
        break;
      case 8:
        outX = predict(vx, prevX, derivativeX);
        outY = predict(vy, prevY, derivativeY);
        break;
      case 9:
        {
          double threshold = config.speedScale * (phase > .5 ? .65 : 1.05);
          double desired = speed > threshold ? 1 : 0;
          phase += (desired - phase) * (1 - Math.exp(-dt / .035));
          double gain = config.precisionGain + (1 - config.precisionGain) * phase;
          outX *= gain;
          outY *= gain;
          break;
        }
      case 10:
        // Ordered velocity blocks: deadzone -> lowpass -> nonlinear -> bounded prediction.
        if (config.customDeadzone && speed > 0) {
          double gain = Math.max(0, 1 - config.deadzone / speed);
          outX *= gain;
          outY *= gain;
        }
        if (config.customLowpass) {
          lowX += (outX - lowX) * alpha(config.cutoffHz, dt);
          lowY += (outY - lowY) * alpha(config.cutoffHz, dt);
          outX = lowX;
          outY = lowY;
        }
        if (config.customNonlinear) {
          double gain = nonlinear(Math.hypot(outX, outY));
          outX *= gain;
          outY *= gain;
        }
        double stageX = outX, stageY = outY;
        if (config.customPrediction) {
          outX = predict(stageX, composePrevX, (stageX - composePrevX) / dt);
          outY = predict(stageY, composePrevY, (stageY - composePrevY) / dt);
        }
        composePrevX = stageX;
        composePrevY = stageY;
        break;
      case 11:
        {
          // Hypothesis: bypass precision path at onset/reversal, blend by speed/noise confidence.
          slowX += (vx - slowX) * alpha(config.minCutoffHz, dt);
          slowY += (vy - slowY) * alpha(config.minCutoffHz, dt);
          double fast = 1 - Math.exp(-Math.pow(speed / Math.max(.02, config.speedScale * .15), 2));
          double reversal = vx * prevX + vy * prevY < 0 ? 1 : 0;
          double onset = clamp(Math.hypot(derivativeX, derivativeY) * .003, 0, 1);
          fast = Math.max(fast, Math.max(reversal, onset));
          double certainty = 1 - stationaryConfidence * .75;
          outX = fast * vx + (1 - fast) * slowX * certainty;
          outY = fast * vy + (1 - fast) * slowY * certainty;
          break;
        }
      default:
        throw new IllegalArgumentException("Unknown motion mode");
    }
    prevX = vx;
    prevY = vy;
    angularX = outX * dt;
    angularY = outY * dt;
    dx = angularX * config.gainX;
    dy = angularY * config.gainY;
    if (!finite(dx) || !finite(dy)) {
      dx = dy = 0;
      invalidSamples++;
      reset();
    }
  }

  private void seedVelocity(double gx, double gy, double gz) {
    gx -= config.biasX;
    gy -= config.biasY;
    gz -= config.biasZ;
    if (config.gravityRelative && gravityReady) {
      double rx = rightX * gx + rightY * gy + rightZ * gz,
          ry = gravityX * gx + gravityY * gy + gravityZ * gz,
          rz = forwardX * gx + forwardY * gy + forwardZ * gz;
      gx = rx;
      gy = ry;
      gz = rz;
    }
    double x = axis(config.axisX, gx, gy, gz),
        y = axis(config.axisY, gx, gy, gz),
        r = Math.toRadians(config.gripRollDegrees),
        c = Math.cos(r),
        s = Math.sin(r);
    prevX = euroX = (config.invertX ? -1 : 1) * (c * x + s * y);
    prevY = euroY = (config.invertY ? -1 : 1) * (-s * x + c * y);
    filterReady = true;
  }

  private void initializeGravity(double ax, double ay, double az) {
    double n = Math.sqrt(ax * ax + ay * ay + az * az);
    if (finite(n) && n > 1) {
      gravityX = ax / n;
      gravityY = ay / n;
      gravityZ = az / n;
      rightX = 1 - gravityX * gravityX;
      rightY = -gravityX * gravityY;
      rightZ = -gravityX * gravityZ;
      double rn = Math.sqrt(rightX * rightX + rightY * rightY + rightZ * rightZ);
      if (rn < .01) {
        rightX = -gravityZ * gravityX;
        rightY = -gravityZ * gravityY;
        rightZ = 1 - gravityZ * gravityZ;
        rn = Math.sqrt(rightX * rightX + rightY * rightY + rightZ * rightZ);
      }
      rightX /= rn;
      rightY /= rn;
      rightZ /= rn;
      forwardX = rightY * gravityZ - rightZ * gravityY;
      forwardY = rightZ * gravityX - rightX * gravityZ;
      forwardZ = rightX * gravityY - rightY * gravityX;
      gravityReady = true;
    }
  }

  private double nonlinear(double speed) {
    double u = Math.pow(Math.max(0, speed / config.speedScale), config.power);
    double ratio = u <= 1 ? u / (1 + u) : 1 - 1 / (1 + u);
    return config.precisionGain + (1 - config.precisionGain) * ratio;
  }

  private double predict(double rate, double previous, double derivative) {
    if (rate * previous <= 0 || stationaryConfidence > .8) return rate;
    double extra = clamp(derivative * config.predictionMs * .001, -Math.abs(rate), Math.abs(rate));
    return rate + extra;
  }

  private static double axis(int axis, double x, double y, double z) {
    return axis == 0 ? x : axis == 1 ? y : z;
  }

  private static double alpha(double hz, double dt) {
    return 1 - Math.exp(-2 * Math.PI * hz * dt);
  }

  private static double clamp(double x, double lo, double hi) {
    return Math.max(lo, Math.min(hi, x));
  }

  private static double wrap(double v) {
    while (v > Math.PI) v -= 2 * Math.PI;
    while (v < -Math.PI) v += 2 * Math.PI;
    return v;
  }

  private static boolean finite(double v) {
    return !Double.isNaN(v) && !Double.isInfinite(v);
  }

  private static final class Notch {
    double x1, x2, y1, y2;

    void reset() {
      x1 = x2 = y1 = y2 = 0;
    }

    double apply(double x, double dt, double hz, double q) {
      if (hz * dt > .4) {
        reset();
        return x;
      }
      double w = 2 * Math.PI * hz * dt, a = Math.sin(w) / (2 * q), c = -2 * Math.cos(w);
      double y = (x + c * x1 + x2 - c * y1 - (1 - a) * y2) / (1 + a);
      x2 = x1;
      x1 = x;
      y2 = y1;
      y1 = y;
      return y;
    }
  }
}
