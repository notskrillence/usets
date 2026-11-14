package android.os;
import java.util.ArrayList;
public final class Handler {
 public final ArrayList<Runnable> delayed=new ArrayList<Runnable>();
 public Looper getLooper(){return Looper.ONLY;}
 public boolean post(Runnable r){r.run();return true;}
 public boolean postDelayed(Runnable r,long ms){if(ms!=100)throw new AssertionError("heartbeat interval");delayed.add(r);return true;}
 public void removeCallbacks(Runnable r){while(delayed.remove(r)){} }
}
