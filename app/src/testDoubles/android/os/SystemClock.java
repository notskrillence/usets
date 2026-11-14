package android.os;
/** Deterministic monotonic clock used only by the standalone local-lab tests. */
public class SystemClock {public static long now=1000000000L;public static long elapsedRealtimeNanos(){return now;} }
