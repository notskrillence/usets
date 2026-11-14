package io.gyrolab.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.gyrolab.core.MotionEngine;
import io.gyrolab.core.MotionEngine.Config;
import java.lang.reflect.Field;
import java.util.ArrayList;

/** All controls apply real core parameters; the dialog stays below the damaged screen area. */
public final class AdvancedPanel {
  public interface Callback {
    void apply(Config configuration);
  }

  private AdvancedPanel() {}

  private static final class Numeric {
    final String name, label;
    final EditText view;

    Numeric(String name, String label, EditText view) {
      this.name = name;
      this.label = label;
      this.view = view;
    }
  }

  private static final class Flag {
    final String name;
    final CheckBox view;

    Flag(String name, CheckBox view) {
      this.name = name;
      this.view = view;
    }
  }

  /** Uses a bounded bottom dialog instead of a platform dropdown that can open above its anchor. */
  private static final class Choice extends Button {
    private final Activity activity;
    private final String label;
    private final String[] options;
    private int selected;

    Choice(Activity activity, String label, String[] options, int selected) {
      super(activity);
      this.activity = activity;
      this.label = label;
      this.options = options.clone();
      this.selected = Math.max(0, Math.min(options.length - 1, selected));
      setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density));
      updateLabel();
      setOnClickListener(v -> open());
    }

    int getSelectedItemPosition() {
      return selected;
    }

    private void updateLabel() {
      setText(options[selected] + " ▾");
      setContentDescription(label + ": " + options[selected]);
    }

    private void open() {
      InputMethodManager keyboard =
          (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
      if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
      Dialog dialog = new Dialog(activity);
      dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
      LinearLayout panel = new LinearLayout(activity);
      panel.setOrientation(LinearLayout.VERTICAL);
      int padding = Math.round(12 * getResources().getDisplayMetrics().density);
      panel.setPadding(padding, padding, padding, padding);
      TextView heading = text(activity, label);
      heading.setTextSize(18);
      panel.addView(heading);
      ScrollView scroll = new ScrollView(activity);
      LinearLayout list = new LinearLayout(activity);
      list.setOrientation(LinearLayout.VERTICAL);
      scroll.addView(list);
      panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
      for (int i = 0; i < options.length; i++) {
        final int index = i;
        Button option = new Button(activity);
        option.setText((i == selected ? "✓ " : "") + options[i]);
        option.setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density));
        option.setOnClickListener(
            v -> {
              selected = index;
              updateLabel();
              dialog.dismiss();
            });
        list.addView(option, new LinearLayout.LayoutParams(-1, -2));
      }
      Button close = new Button(activity);
      close.setText("Close");
      close.setOnClickListener(v -> dialog.dismiss());
      panel.addView(close);
      dialog.setContentView(panel);
      dialog.show();
      Window window = dialog.getWindow();
      if (window != null) {
        window.setGravity(Gravity.BOTTOM);
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        SafeArea.dialog(activity, window, .65);
      }
    }
  }

  public static void show(Activity activity, Config original, Callback callback) {
    Config working = original.copy();
    Dialog dialog = new Dialog(activity);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout shell = new LinearLayout(activity);
    shell.setOrientation(LinearLayout.VERTICAL);
    shell.setPadding(16, 12, 16, 12);
    TextView title = text(activity, "Advanced motion settings");
    title.setTextSize(20);
    shell.addView(title);
    ScrollView scroll = new ScrollView(activity);
    LinearLayout body = new LinearLayout(activity);
    body.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(body);
    shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    body.addView(
        text(
            activity,
            "Settings apply on confirmation and reset motion history. Angles use radians; gains use"
                + " mouse counts, not guaranteed Windows pixels."));
    Choice mode = choice(activity, body, "Tracking mode", MotionEngine.MODE_NAMES, working.mode);
    body.addView(
        text(
            activity,
            "Only parameters for the selected mode affect its processing. Composer blocks run in"
                + " the fixed, valid order shown below."));
    ArrayList<Numeric> numbers = new ArrayList<>();
    ArrayList<Flag> flags = new ArrayList<>();
    section(activity, body, "Coordinate mapping and sensitivity");
    Choice axisX =
        choice(
            activity,
            body,
            "Horizontal gyro axis",
            new String[] {"X (0)", "Y (1)", "Z (2)"},
            working.axisX);
    Choice axisY =
        choice(
            activity,
            body,
            "Vertical gyro axis",
            new String[] {"X (0)", "Y (1)", "Z (2)"},
            working.axisY);
    number(activity, body, numbers, working, "gainX", "Horizontal gain · counts/radian");
    number(activity, body, numbers, working, "gainY", "Vertical gain · counts/radian");
    flag(activity, body, flags, working, "invertX", "Invert horizontal movement");
    flag(activity, body, flags, working, "invertY", "Invert vertical movement");
    flag(
        activity,
        body,
        flags,
        working,
        "gravityRelative",
        "Gravity-aligned recentered axes (requires accel)");
    number(
        activity,
        body,
        numbers,
        working,
        "gripRollDegrees",
        "Grip roll compensation · degrees (-360…360)");
    section(activity, body, "Filters · fixed / One Euro / dual path");
    number(activity, body, numbers, working, "cutoffHz", "Fixed low-pass cutoff · Hz");
    number(
        activity,
        body,
        numbers,
        working,
        "minCutoffHz",
        "One Euro / dual-path minimum cutoff · Hz");
    number(
        activity, body, numbers, working, "beta", "One Euro derivative coefficient · nonnegative");
    number(
        activity, body, numbers, working, "derivativeCutoffHz", "One Euro derivative cutoff · Hz");
    section(activity, body, "Nonlinear / ballistic / relative-anchor hybrid");
    number(activity, body, numbers, working, "power", "Nonlinear response power · 1…4");
    number(
        activity, body, numbers, working, "precisionGain", "Low-speed / fine-phase gain · 0.01…1");
    number(
        activity,
        body,
        numbers,
        working,
        "speedScale",
        "Nonlinear / ballistic / dual-path speed scale · rad/s");
    number(activity, body, numbers, working, "blend", "Hybrid anchored fraction · 0…1");
    section(activity, body, "Tremor notch / bounded prediction");
    number(
        activity,
        body,
        numbers,
        working,
        "tremorHz",
        "Tremor notch center · Hz (bypasses near Nyquist)");
    number(activity, body, numbers, working, "tremorQ", "Tremor notch Q · 0.25…100");
    number(
        activity,
        body,
        numbers,
        working,
        "predictionMs",
        "Bounded prediction horizon · milliseconds (0…40)");
    section(activity, body, "Stationarity / quaternion gravity fusion");
    number(
        activity, body, numbers, working, "stationaryThreshold", "Stationary gyro scale · rad/s");
    number(activity, body, numbers, working, "fusionGain", "Gravity fusion correction gain · 0…10");
    flag(
        activity,
        body,
        flags,
        working,
        "adaptBias",
        "Adapt bias after confidently stationary periods");
    body.addView(
        text(
            activity,
            "Bias adaptation can absorb extremely slow intentional motion. Disable it for"
                + " controlled comparisons. Gravity fusion cannot remove yaw drift."));
    number(activity, body, numbers, working, "biasX", "Calibrated gyro bias X · rad/s");
    number(activity, body, numbers, working, "biasY", "Calibrated gyro bias Y · rad/s");
    number(activity, body, numbers, working, "biasZ", "Calibrated gyro bias Z · rad/s");
    section(activity, body, "Composer · dead zone → filter → gain → prediction");
    flag(activity, body, flags, working, "customDeadzone", "Composer: soft radial dead zone");
    number(activity, body, numbers, working, "deadzone", "Composer dead-zone radius · rad/s");
    flag(activity, body, flags, working, "customLowpass", "Composer: fixed low-pass");
    flag(activity, body, flags, working, "customNonlinear", "Composer: nonlinear gain");
    flag(activity, body, flags, working, "customPrediction", "Composer: bounded prediction");
    TextView error = text(activity, "");
    error.setTextColor(Color.rgb(180, 30, 30));
    shell.addView(error);
    LinearLayout actions = new LinearLayout(activity);
    Button cancel = new Button(activity), apply = new Button(activity);
    cancel.setText("Cancel");
    apply.setText("Apply settings");
    actions.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
    actions.addView(apply, new LinearLayout.LayoutParams(0, -2, 1));
    shell.addView(actions);
    cancel.setOnClickListener(v -> dialog.dismiss());
    apply.setOnClickListener(
        v -> {
          error.setText("");
          Config result = working.copy();
          result.mode = mode.getSelectedItemPosition();
          result.axisX = axisX.getSelectedItemPosition();
          result.axisY = axisY.getSelectedItemPosition();
          if (result.axisX == result.axisY) {
            error.setText("Choose two different gyro axes.");
            return;
          }
          for (Numeric n : numbers) {
            n.view.setError(null);
            try {
              String value = n.view.getText().toString().trim();
              if (value.isEmpty()) throw new IllegalArgumentException("Enter a number");
              double parsed = Double.parseDouble(value);
              ConfigStore.checkNumber(n.name, parsed);
              Config.class.getField(n.name).setDouble(result, parsed);
            } catch (Exception e) {
              n.view.setError(
                  e instanceof NumberFormatException ? "Enter a valid number" : e.getMessage());
              n.view.requestFocus();
              error.setText("Check " + n.label);
              return;
            }
          }
          try {
            for (Flag f : flags)
              Config.class.getField(f.name).setBoolean(result, f.view.isChecked());
            ConfigStore.validate(result);
            callback.apply(result);
            dialog.dismiss();
          } catch (Exception e) {
            error.setText(e.getMessage() == null ? "Cannot apply configuration" : e.getMessage());
          }
        });
    dialog.setContentView(shell);
    dialog.show();
    Window window = dialog.getWindow();
    if (window != null) {
      window.setGravity(Gravity.BOTTOM);
      window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
      SafeArea.dialog(activity, window, .70);
    }
  }

  private static TextView text(Activity activity, String value) {
    TextView t = new TextView(activity);
    t.setText(value);
    t.setPadding(0, 6, 0, 6);
    return t;
  }

  private static void section(Activity activity, LinearLayout body, String label) {
    TextView t = text(activity, label);
    t.setTextSize(17);
    body.addView(t);
  }

  private static Choice choice(
      Activity a, LinearLayout body, String label, String[] options, int selected) {
    body.addView(text(a, label));
    Choice choice = new Choice(a, label, options, selected);
    body.addView(choice);
    return choice;
  }

  private static void number(
      Activity a,
      LinearLayout body,
      ArrayList<Numeric> all,
      Config source,
      String name,
      String label) {
    body.addView(text(a, label));
    EditText e = new EditText(a);
    e.setSingleLine(true);
    e.setInputType(
        InputType.TYPE_CLASS_NUMBER
            | InputType.TYPE_NUMBER_FLAG_DECIMAL
            | InputType.TYPE_NUMBER_FLAG_SIGNED);
    try {
      Field field = Config.class.getField(name);
      e.setText(Double.toString(field.getDouble(source)));
    } catch (Exception ex) {
      throw new IllegalArgumentException("Unknown parameter: " + name, ex);
    }
    body.addView(e);
    all.add(new Numeric(name, label, e));
  }

  private static void flag(
      Activity a,
      LinearLayout body,
      ArrayList<Flag> all,
      Config source,
      String name,
      String label) {
    CheckBox c = new CheckBox(a);
    c.setText(label);
    try {
      c.setChecked(Config.class.getField(name).getBoolean(source));
    } catch (Exception ex) {
      throw new IllegalArgumentException("Unknown flag: " + name, ex);
    }
    body.addView(c);
    all.add(new Flag(name, c));
  }
}
