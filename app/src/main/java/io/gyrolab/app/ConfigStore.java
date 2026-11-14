package io.gyrolab.app;

import android.content.Context;
import android.content.SharedPreferences;
import io.gyrolab.core.MotionEngine.Config;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import org.json.JSONException;
import org.json.JSONObject;

/** Versioned, typed local presets. No transport/session data is persisted here. */
public final class ConfigStore {
  private ConfigStore() {}

  private static final String PREFIX = "preset:";
  private static final String CURRENT = "current";

  public static String encode(Config config) {
    validate(config);
    try {
      JSONObject fields = new JSONObject();
      for (Field f : Config.class.getFields()) {
        if (Modifier.isStatic(f.getModifiers())) continue;
        fields.put(f.getName(), f.get(config));
      }
      return new JSONObject()
          .put("format", "gyrolab-config")
          .put("version", 1)
          .put("config", fields)
          .toString(2);
    } catch (JSONException | IllegalAccessException e) {
      throw new IllegalArgumentException("Cannot encode configuration", e);
    }
  }

  public static Config decode(String json) {
    if (json == null || json.length() > 65536)
      throw new IllegalArgumentException("Preset must contain at most 64 KiB of JSON");
    try {
      JSONObject root = new JSONObject(json);
      Object version = root.get("version");
      if (!"gyrolab-config".equals(root.get("format"))
          || !(version instanceof Number)
          || ((Number) version).doubleValue() != 1)
        throw new IllegalArgumentException("Unsupported preset format/version");
      JSONObject fields = root.getJSONObject("config");
      Config result = new Config();
      Iterator<String> keys = fields.keys();
      while (keys.hasNext()) {
        String name = keys.next();
        Field f;
        try {
          f = Config.class.getField(name);
        } catch (NoSuchFieldException e) {
          throw new IllegalArgumentException("Unknown configuration field: " + name);
        }
        Object value = fields.get(name);
        if (f.getType() == boolean.class) {
          if (!(value instanceof Boolean))
            throw new IllegalArgumentException(name + " must be a boolean");
          f.setBoolean(result, (Boolean) value);
        } else if (f.getType() == int.class) {
          if (!(value instanceof Number))
            throw new IllegalArgumentException(name + " must be an integer");
          double number = ((Number) value).doubleValue();
          if (!Double.isFinite(number)
              || number != Math.rint(number)
              || number < Integer.MIN_VALUE
              || number > Integer.MAX_VALUE)
            throw new IllegalArgumentException(name + " must be an integer");
          f.setInt(result, (int) number);
        } else if (f.getType() == double.class) {
          if (!(value instanceof Number))
            throw new IllegalArgumentException(name + " must be a number");
          double number = ((Number) value).doubleValue();
          checkNumber(name, number);
          f.setDouble(result, number);
        } else throw new IllegalArgumentException("Unsupported configuration field: " + name);
      }
      // Require every field: truncated exports must not silently change settings.
      for (Field f : Config.class.getFields())
        if (!Modifier.isStatic(f.getModifiers()) && !fields.has(f.getName()))
          throw new IllegalArgumentException("Missing configuration field: " + f.getName());
      validate(result);
      return result;
    } catch (JSONException | IllegalAccessException e) {
      throw new IllegalArgumentException("Invalid preset JSON: " + e.getMessage(), e);
    }
  }

  static void validate(Config config) {
    if (config == null) throw new IllegalArgumentException("Configuration is missing");
    config.validate();
    try {
      for (Field f : Config.class.getFields())
        if (f.getType() == double.class) checkNumber(f.getName(), f.getDouble(config));
    } catch (IllegalAccessException e) {
      throw new IllegalArgumentException("Cannot read configuration", e);
    }
  }

  /** Bounds supplement the core validator with limits for every editable number. */
  static void checkNumber(String name, double value) {
    if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    double min = 0, max = 1000;
    switch (name) {
      case "gainX":
      case "gainY":
        max = 100000;
        break;
      case "biasX":
      case "biasY":
      case "biasZ":
        min = -100;
        max = 100;
        break;
      case "gripRollDegrees":
        min = -360;
        max = 360;
        break;
      case "cutoffHz":
      case "minCutoffHz":
      case "derivativeCutoffHz":
        min = .001;
        break;
      case "beta":
        max = 1000;
        break;
      case "power":
        min = 1;
        max = 4;
        break;
      case "precisionGain":
        min = .01;
        max = 1;
        break;
      case "speedScale":
        min = .001;
        max = 100;
        break;
      case "blend":
        max = 1;
        break;
      case "deadzone":
        max = 10;
        break;
      case "predictionMs":
        max = 40;
        break;
      case "tremorHz":
        min = .1;
        max = 100;
        break;
      case "tremorQ":
        min = .25;
        max = 100;
        break;
      case "stationaryThreshold":
        min = .000001;
        max = 10;
        break;
      case "fusionGain":
        max = 10;
        break;
      default:
        throw new IllegalArgumentException("Unknown numeric parameter: " + name);
    }
    if (value < min || value > max)
      throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
  }

  private static SharedPreferences preferences(Context context) {
    SharedPreferences p =
        context
            .getApplicationContext()
            .getSharedPreferences("gyrolab-config-v1", Context.MODE_PRIVATE);
    if (!p.getBoolean("defaults-v1", false)) {
      Config direct = new Config(), precision = new Config(), balanced = new Config();
      precision.mode = 2;
      precision.minCutoffHz = 1;
      precision.beta = .25;
      balanced.mode = 11;
      p.edit()
          .putString(PREFIX + "Direct", encode(direct))
          .putString(PREFIX + "Precision One Euro", encode(precision))
          .putString(PREFIX + "Experimental dual path", encode(balanced))
          .putBoolean("defaults-v1", true)
          .apply();
    }
    return p;
  }

  private static String checkedName(String name) {
    if (name == null) throw new IllegalArgumentException("Enter a preset name");
    name = name.trim();
    if (name.isEmpty() || name.length() > 64)
      throw new IllegalArgumentException("Preset names need 1–64 characters");
    for (int i = 0; i < name.length(); i++)
      if (Character.isISOControl(name.charAt(i)))
        throw new IllegalArgumentException("Preset names cannot contain control characters");
    return name;
  }

  public static void save(Context context, String name, Config config) {
    preferences(context).edit().putString(PREFIX + checkedName(name), encode(config)).apply();
  }

  public static Config load(Context context, String name) {
    String json = preferences(context).getString(PREFIX + checkedName(name), null);
    if (json == null) throw new IllegalArgumentException("Preset does not exist: " + name);
    return decode(json);
  }

  public static String[] names(Context context) {
    ArrayList<String> names = new ArrayList<>();
    for (String key : preferences(context).getAll().keySet())
      if (key.startsWith(PREFIX)) names.add(key.substring(PREFIX.length()));
    Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
    return names.toArray(new String[0]);
  }

  public static void delete(Context context, String name) {
    preferences(context).edit().remove(PREFIX + checkedName(name)).apply();
  }

  public static void saveCurrent(Context context, Config config) {
    preferences(context).edit().putString(CURRENT, encode(config)).apply();
  }

  public static Config loadCurrent(Context context) {
    String json = preferences(context).getString(CURRENT, null);
    if (json == null) return new Config();
    try {
      return decode(json);
    } catch (IllegalArgumentException e) {
      return new Config();
    }
  }
}
