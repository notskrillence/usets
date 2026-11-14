package io.gyrolab.app;

import android.app.Activity;
import android.content.res.Configuration;
import android.view.Gravity;
import android.view.Surface;
import android.view.Window;
import android.view.WindowManager;

/** Keeps controls away from the damaged natural top of the physical phone after rotation. */
final class SafeArea {
  private SafeArea() {}

  static int sideMargin(Activity activity) {
    return activity.getResources().getConfiguration().orientation
            == Configuration.ORIENTATION_LANDSCAPE
        ? Math.round(activity.getResources().getDisplayMetrics().widthPixels * .28f)
        : 0;
  }

  static int bottomMargin(Activity activity) {
    return activity.getWindowManager().getDefaultDisplay().getRotation() == Surface.ROTATION_180
        ? Math.round(activity.getResources().getDisplayMetrics().heightPixels * .28f)
        : 0;
  }

  static void dialog(Activity activity, Window window, double heightFraction) {
    if (window == null) return;
    int width = activity.getResources().getDisplayMetrics().widthPixels;
    int height = activity.getResources().getDisplayMetrics().heightPixels;
    int side = sideMargin(activity), bottom = bottomMargin(activity);
    // Both landscape sides are excluded conservatively, regardless of left/right rotation.
    window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    WindowManager.LayoutParams attributes = window.getAttributes();
    attributes.y = bottom;
    window.setAttributes(attributes);
    window.setLayout(
        side == 0 ? WindowManager.LayoutParams.MATCH_PARENT : width - 2 * side,
        (int) (height * (bottom == 0 ? heightFraction : .44)));
  }
}
