package android.os;
import java.util.*;
/** Deterministic API double: immediate posts, explicitly advanced delayed callbacks. */
public class Handler {private final List<Runnable> delayed=new ArrayList<>();public Looper getLooper(){return Looper.myLooper();}public boolean post(Runnable r){r.run();return true;}public boolean postDelayed(Runnable r,long delay){delayed.add(r);return true;}public void removeCallbacks(Runnable r){delayed.removeIf(x->x==r);}public int delayedCount(){return delayed.size();}public void runDelayed(){List<Runnable> copy=new ArrayList<>(delayed);delayed.clear();for(Runnable r:copy)r.run();}}
