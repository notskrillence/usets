package io.gyrolab.app;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import io.gyrolab.lab.TargetMetrics;
import java.lang.reflect.Field;

/** Deterministic control-flow tests using small Android doubles, not an emulator. */
public final class LabViewTests {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static double number(LabView view,String field)throws Exception{Field f=LabView.class.getDeclaredField(field);f.setAccessible(true);return ((Number)f.get(view)).doubleValue();}
    private static TargetMetrics metrics(LabView view)throws Exception{Field f=LabView.class.getDeclaredField("metrics");f.setAccessible(true);return(TargetMetrics)f.get(view);}
    private static void advance(long milliseconds){SystemClock.now+=milliseconds*1000000L;}
    private static LabView fresh(String task){LabView view=new LabView(new Context());view.layout(0,0,400,400);view.attachForTest();view.restart(task);return view;}
    public static void main(String[] args)throws Exception{
        LabView view=fresh("static");
        view.interrupt();
        check(!view.resultsCsv().contains("interrupted,true"),"enabling a fresh trial cannot interrupt it before input");
        advance(60000);view.click();
        check(metrics(view).attempts()==0,"cannot score before first live core update");
        check(!metrics(view).active(),"first acquisition clock does not run during layout/idle");
        view.move(0,0);
        check(metrics(view).active(),"zero-valued live update starts the acquisition clock");
        advance(100);
        view.move(number(view,"targetX")-number(view,"cursorX"),number(view,"targetY")-number(view,"cursorY"));
        view.click();
        check(metrics(view).hits()==1,"actual aim plus click acquires the target");
        check(Math.abs(metrics(view).meanAcquisitionSeconds()-.1)<1e-9,"first acquisition excludes the minute before tracking");
        int attempts=metrics(view).attempts();
        advance(251);view.click();
        check(metrics(view).attempts()==attempts,"stale-input click is not scored");
        check(view.resultsCsv().contains("complete,false,interrupted,true"),"stale click marks run partial");
        view=fresh("stationary");advance(60000);
        check(view.callbackCountForTest()==0,"timed task has no timer before live input");
        check(number(view,"measuredSeconds")==0,"idle time is not stationary measurement");
        view.move(0,0);advance(100);view.move(3,4);advance(100);view.move(0,0);
        check(Math.abs(number(view,"measuredSeconds")-.2)<1e-9,"timed measurement uses monotonic elapsed time");
        check(view.resultsCsv().contains("5.0,5.0,generated_counts"),"stationary metrics record actual generated path and net drift");
        advance(251);view.runOneCallbackForTest();
        check(view.resultsCsv().contains("complete,false,interrupted,true"),"timer interrupts when live updates stop");
        check(number(view,"measuredSeconds")<10,"frozen cursor cannot finish valid stationary trial");
        check(view.callbackCountForTest()==0,"interruption cancels future timer callbacks");
        view=fresh("moving");view.move(0,0);advance(100);view.move(4,-2);view.interrupt();
        String interrupted=view.resultsCsv();
        check(interrupted.contains("complete,false,interrupted,true"),"explicit interrupt marks partial");
        advance(20000);view.move(300,300);view.runOneCallbackForTest();
        check(interrupted.equals(view.resultsCsv()),"interrupted run cannot mutate or finish on later movement");
        view=fresh("stationary");view.move(0,0);
        for(int i=0;i<100;i++){advance(100);view.move(0,0);}
        check(view.resultsCsv().contains("complete,true,interrupted,false"),"continuous live updates allow a valid full timed run");
        check(Math.abs(number(view,"measuredSeconds")-10)<1e-9,"full stationary task lasts exactly ten seconds");
        check(number(view,"errorSquaredSeconds")==0,"actual zero movement yields zero stationary RMS");
        view=fresh("micro");view.move(0,0);double before=number(view,"cursorX");int beforeAttempts=metrics(view).attempts();
        check(view.onTouchEvent(new MotionEvent()),"canvas touch is consumed");
        check(before==number(view,"cursorX")&&beforeAttempts==metrics(view).attempts(),"touch neither teleports cursor nor scores a target");
        view.drawForTest(new Canvas());view.detachForTest();
        check(view.resultsCsv().contains("complete,false,interrupted,true"),"leaving lab interrupts active experiment");
        view.restart("vertical");
        check(!metrics(view).active()&&metrics(view).attempts()==0,"restart resets metrics and waits for fresh input");
        System.out.println("LabView standalone checks passed: "+checks+" (Android doubles; no device/rendering verification)");
    }
}
