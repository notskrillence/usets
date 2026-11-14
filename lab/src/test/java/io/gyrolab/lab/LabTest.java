package io.gyrolab.lab;

import io.gyrolab.core.MotionEngine;
import java.io.*;

/** Standalone tests: javac core + lab + this class; java io.gyrolab.lab.LabTest. */
public final class LabTest {
    private static int checks;
    private static void check(boolean condition,String description) {checks++;if(!condition)throw new AssertionError(description);}
    private static void near(double got,double expected,double tolerance,String description) {check(Math.abs(got-expected)<=tolerance,description+" got="+got+" expected="+expected);}
    private static Recording synthetic(int n) {
        Recording r=new Recording(n);r.origin="synthetic_test";
        for(int i=0;i<n;i++)r.add(1000000000L+i*5000000L,.02*Math.sin(i*.17),.1+.03*Math.sin(i*.05),0,0,0,9.80665);
        return r;
    }
    public static void main(String[] args) throws Exception {
        MotionEngine.Config config=new MotionEngine.Config();config.gainX=100;config.gainY=200;config.biasZ=.001;config.invertY=true;
        Recording r=synthetic(501);
        StringWriter text=new StringWriter();r.write(text,config);
        Recording loaded=Recording.read(new StringReader(text.toString()));
        check(loaded.size()==501,"round-trip count");
        check(loaded.origin.equals("synthetic_test"),"origin preserved");
        check(loaded.recordedConfig.invertY,"boolean config preserved");
        near(loaded.recordedConfig.gainX,100,0,"gain config preserved");
        near(loaded.recordedConfig.biasZ,.001,0,"bias config preserved");
        for(int i=0;i<r.size();i++){check(r.timestamp(i)==loaded.timestamp(i),"timestamp round trip");for(int c=0;c<6;c++)near(r.value(c,i),loaded.value(c,i),0,"raw double round trip");}
        Recording.Replay a=r.replay(config),b=loaded.replay(config);
        for(int i=0;i<r.size();i++){near(a.x[i],b.x[i],0,"deterministic x");near(a.y[i],b.y[i],0,"deterministic y");}
        check(a.pathCounts>0,"nonzero real replay output");
        Recording straight=new Recording(101);
        for(int i=0;i<101;i++) straight.add(i*10000000L,0,1,0,0,0,9.80665);
        near(straight.replay(config).x[100],100,1e-10,"rad/s times seconds times gain");
        MotionEngine.Config low=config.copy();low.mode=1;low.cutoffHz=.5;
        check(straight.replay(low).x[100]<100,"lowpass differs from direct on onset");
        String comparison=r.compare(config);
        check(comparison.split("\n").length==15,"all 12 modes compared");
        check(comparison.contains("No human acquisition claims"),"counterfactual warning");
        Recording.SweepResult sweep=r.sweep(config,Recording.Objective.BALANCED);
        check(sweep.selected.mode==2,"One Euro sweep returns real mode");
        check(Double.isFinite(sweep.trainingScore)&&Double.isFinite(sweep.heldOutScore),"finite held-out proxy");
        check(sweep.csv.contains("held_out_proxy"),"held-out explicitly reported");
        Recording tiny=new Recording(1);
        check(tiny.add(1,0,0,0,0,0,9.8),"within cap accepted");
        check(!tiny.add(2,0,0,0,0,0,9.8),"cap enforced");
        tiny.clear();check(!tiny.add(3,Double.NaN,0,0,0,0,0),"nonfinite rejected");
        boolean invalid=false;try{Recording.read(new StringReader(text.toString().replace("# GYROLAB_RECORDING,1","# GYROLAB_RECORDING,2")));}catch(IOException e){invalid=true;}check(invalid,"unknown version rejected");
        invalid=false;try{Recording.read(new StringReader(text.toString().replace("# config.gainX=100.0","# config.gainX=-1.0")));}catch(IOException e){invalid=true;}check(invalid,"invalid metadata rejected");
        TargetMetrics metrics=new TargetMetrics();
        metrics.begin(1000000000L,0,0,100,0,10);
        metrics.move(40,0);
        check(!metrics.click(1500000000L,80,0),"miss preserved");
        check(metrics.active(),"target remains after miss");
        check(metrics.click(2000000000L,100,0),"hit recorded");
        check(!metrics.active(),"hit ends target");
        check(metrics.attempts()==2&&metrics.hits()==1,"real attempts counted");
        near(metrics.hitPercent(),50,0,"hit percentage");
        near(metrics.meanAcquisitionSeconds(),1,1e-12,"time includes miss correction");
        near(metrics.meanEndpointError(),10,1e-12,"endpoint error across attempts");
        near(metrics.meanPathEfficiency(),1,1e-12,"straight path efficiency");
        check(metrics.reportCsv().contains("NOT effective Fitts"),"nominal throughput distinguished");
        metrics.reset();check(metrics.attempts()==0&&!metrics.active(),"trial reset");
        System.out.println("LabTest passed: "+checks+" assertions; synthetic input only.");
    }
}
