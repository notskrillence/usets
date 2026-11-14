package io.gyrolab.core;

/** Synthetic numerical/contract tests; does not claim device or Windows verification. */
public final class CoreTests {
    private static int tests;
    private static void check(boolean ok,String what){tests++;if(!ok)throw new AssertionError(what);}
    private static void near(double actual,double expected,double eps,String what){check(Math.abs(actual-expected)<=eps,what+": "+actual+" vs "+expected);}
    private static MotionEngine engine(int mode){MotionEngine.Config c=new MotionEngine.Config();c.mode=mode;return new MotionEngine(c);}
    private static void feed(MotionEngine e,long t,double x,double y,double z){e.process(t,x,y,z,0,0,9.80665);}
    public static void main(String[] args){
        integration();frames();quaternion();timeAndReset();fractional();calibration();filters();modes();composition();dualPathHypothesis();
        System.out.println("PASS "+tests+" numerical assertions; synthetic JVM tests only");
    }
    private static void integration(){
        for(int period:new int[]{1,5,10,20}){MotionEngine e=engine(0);double total=0;feed(e,1,0,1,0);for(int i=1;i<=1000/period;i++){feed(e,1+i*period*1000000L,0,1,0);total+=e.dx;}near(total,900,1e-8,"one radian yields 900 counts at "+period+" ms");}
        MotionEngine e=engine(0);feed(e,1,0,1,0);double total=0;long t=1;int[] ms={3,7,4,6};for(int i=0;i<200;i++){t+=ms[i%4]*1000000L;feed(e,t,0,1,0);total+=e.dx;}near(total,900,1e-8,"irregular dt integral");
    }
    private static void frames(){
        MotionEngine e=engine(0);e.config.invertX=true;e.config.gainY=100;feed(e,1,1,2,0);feed(e,10000001,1,2,0);near(e.dx,-18,1e-10,"horizontal inversion");near(e.dy,1,1e-10,"independent gain");
        e=engine(0);e.config.gripRollDegrees=90;feed(e,1,1,0,0);feed(e,10000001,1,0,0);near(e.dx,9,1e-10,"grip rotation");near(e.dy,0,1e-10,"grip rotation y");
        e=engine(0);e.config.gravityRelative=true;e.process(1,0,0,1,0,0,9.80665);e.process(10000001,0,0,1,0,0,9.80665);near(e.dx,9,1e-10,"gravity yaw when phone flat");near(e.dy,0,1e-10,"gravity yaw no pitch");
        e=engine(0);e.config.gravityRelative=true;e.process(1,0,1,0,0,9.80665,0);e.process(10000001,0,1,0,0,9.80665,0);near(e.dx,9,1e-10,"gravity yaw when phone upright");
        e=engine(0);e.config.biasY=.1;feed(e,1,0,.1,0);feed(e,10000001,0,.1,0);near(e.dx,0,1e-12,"bias subtraction");
    }
    private static void quaternion(){
        Quaternion q=new Quaternion();for(int i=0;i<10000;i++)q.integrate(.1,.2,-.3,.001);near(q.w*q.w+q.x*q.x+q.y*q.y+q.z*q.z,1,1e-12,"quaternion norm");
        q.identity().integrate(0,Math.PI,0,1);near(Math.abs(q.y),1,1e-12,"180-degree quaternion");
        q.identity().integrate(Math.PI/2,0,0,1);near(q.inverseGravityY(0,0,1),1,1e-12,"inverse gravity convention");
        MotionEngine e=engine(3);feed(e,1,0,2,0);double max=0;for(int i=1;i<=1500;i++){feed(e,1+i*5000000L,0,2,0);max=Math.max(max,Math.abs(e.dx));}check(max<40,"anchored wraparound remains bounded");
        for(int mode:new int[]{3,5,6}){e=engine(mode);feed(e,1,.1,Math.sqrt(.99),0);max=0;for(int i=1;i<=2000;i++){feed(e,1+i*5000000L,.1,Math.sqrt(.99),0);max=Math.max(max,Math.hypot(e.dx,e.dy));}check(max<40,"compound quaternion branch bounded mode "+mode);if(mode!=5)check(e.projectionReanchors>0,"compound branch detected mode "+mode);}
    }
    private static void timeAndReset(){
        for(int mode=0;mode<12;mode++){MotionEngine e=engine(mode);feed(e,100,0,1,0);near(e.dx,0,0,"first sample zero");feed(e,5000100,0,1,0);feed(e,5000100,0,1,0);near(e.dx,0,0,"duplicate ignored mode "+mode);feed(e,100,0,1,0);near(e.dy,0,0,"out of order ignored");feed(e,1000000000,0,1,0);near(e.dx,0,0,"gap reanchor mode "+mode);e.reset();feed(e,1100000000,0,1,0);near(e.dx,0,0,"reset no jump");}
        MotionEngine e=engine(0);feed(e,1,0,1,0);feed(e,5000001,Double.NaN,0,0);near(e.dx,0,0,"NaN rejected");check(e.invalidSamples==1,"NaN diagnostic");feed(e,10000001,0,1,0);near(e.dx,9,1e-12,"valid sample after invalid uses actual dt");
        e.config.mode=3;feed(e,15000001,0,1,0);near(e.dx,0,0,"mode switch resets state");
    }
    private static void fractional(){
        Quantizer q=new Quantizer();int sum=0;for(int i=0;i<100;i++){q.add(.125,0);if(q.drain())sum+=q.dx;}near(sum+q.pendingX,12.5,1e-12,"fraction preserved");
        q.clear();q.add(1000.25,-1000.75);int x=0,y=0,n=0;while(q.drain()){check(Math.abs(q.dx)<=127&&Math.abs(q.dy)<=127,"HID range");x+=q.dx;y+=q.dy;if(++n>20)throw new AssertionError("bad drain");}near(x,1000,0,"positive saturation split");near(y,-1000,0,"negative saturation split");near(q.pendingX,.25,1e-12,"positive remainder");near(q.pendingY,-.75,1e-12,"negative remainder");q.clear();check(!q.drain(),"pause clears backlog");
        boolean threw=false;try{q.add(Double.NaN,0);}catch(IllegalArgumentException ex){threw=true;}check(threw,"invalid quantizer input rejected");
    }
    private static void calibration(){
        Calibration c=new Calibration();for(int i=0;i<401;i++)c.add(1+i*5000000L,.01,-.02,.03);check(c.usable(),"stationary calibration usable");near(c.hz(),200,1e-12,"measured sample Hz");near(c.jitterMs(),0,0,"interval jitter");near(c.percentileMs(.95),5,0,"interval percentile");MotionEngine.Config conf=new MotionEngine.Config();c.apply(conf);near(conf.biasY,-.02,1e-12,"calibration applies bias");near(c.noiseRms(),0,1e-12,"constant gyro variance");
        Calibration noisy=new Calibration();for(int i=0;i<401;i++)noisy.add(1+i*5000000L,i%2==0?.2:-.2,0,0);check(!noisy.usable(),"moving calibration rejected");
        Calibration rotating=new Calibration();for(int i=0;i<401;i++)rotating.add(1+i*5000000L,.2,0,0);check(!rotating.usable(),"constant high-rate rotation rejected as calibration");
    }
    private static void filters(){
        MotionEngine e=engine(1);feed(e,1,0,1,0);for(int i=1;i<=100;i++)feed(e,1+i*5000000L,0,1,0);near(e.angularX/.005,1-Math.exp(-2*Math.PI*8*.5),1e-10,"time-correct LP step response");
        MotionEngine e2=engine(1);feed(e2,1,0,1,0);for(int i=1;i<=50;i++)feed(e2,1+i*10000000L,0,1,0);near(e.angularX/.005,e2.angularX/.01,1e-10,"LP independent of frequency");
        MotionEngine euro=engine(2);feed(euro,1,0,0,0);feed(euro,5000001,0,.1,0);check(euro.dx>0&&euro.dx<.45,"One Euro smooths velocity step");
        MotionEngine non=engine(4);feed(non,1,0,.0001,0);feed(non,5000001,0,.0001,0);near(non.dx/(.005*.0001*900),.4,1e-5,"precision gain near zero");
        non=engine(4);non.config.speedScale=1e-300;non.config.validate();feed(non,1,0,1,0);feed(non,5000001,0,1,0);near(non.dx,4.5,1e-12,"nonlinear gain remains finite at extreme accepted scale");
        MotionEngine pred=engine(8);feed(pred,1,0,.1,0);feed(pred,5000001,0,.1,0);feed(pred,10000001,0,.2,0);check(pred.dx>=.9&&pred.dx<=1.8,"bounded prediction");feed(pred,15000001,0,-.2,0);near(pred.dx,-.9,1e-12,"no reversal overshoot");
        MotionEngine notch=engine(7),direct=engine(0);double en=0,ed=0;feed(notch,1,0,0,0);feed(direct,1,0,0,0);for(int i=1;i<=2000;i++){double y=Math.sin(2*Math.PI*10*i*.005);feed(notch,1+i*5000000L,0,y,0);feed(direct,1+i*5000000L,0,y,0);if(i>1000){en+=notch.dx*notch.dx;ed+=direct.dx*direct.dx;}}check(en/ed<.001,"10 Hz notch attenuates 10 Hz synthetic tremor");
    }
    private static void modes(){
        double[] fingerprints=new double[12];
        for(int mode=0;mode<12;mode++){MotionEngine e=engine(mode);feed(e,1,0,0,0);long ts=1;for(int i=0;i<1000;i++){ts+=(i%3==0?3000000L:6000000L);double t=i*.005;double gx=.2*Math.sin(t*7),gy=.7*Math.cos(t*3)+.03*Math.sin(t*63),gz=.1*Math.sin(t*2);e.process(ts,gx,gy,gz,1.5*Math.sin(t),0,9.7);check(Double.isFinite(e.dx)&&Double.isFinite(e.dy),"mode finite "+mode);fingerprints[mode]+=(i%17+1)*(e.dx+.37*e.dy);} }
        for(int a=0;a<12;a++)for(int b=a+1;b<12;b++)check(Math.abs(fingerprints[a]-fingerprints[b])>1e-5,"distinct signatures "+a+"/"+b);
        // Identical input/configuration yields identical deterministic replay.
        MotionEngine a=engine(11),b=engine(11);for(int i=0;i<1000;i++){long t=1+i*5000000L;feed(a,t,.1*Math.sin(i),.2*Math.cos(i*.1),0);feed(b,t,.1*Math.sin(i),.2*Math.cos(i*.1),0);near(a.dx,b.dx,0,"deterministic replay");}
    }
    private static void dualPathHypothesis(){
        MotionEngine dual=engine(11),direct=engine(0);double noiseDual=0,noiseDirect=0;
        feed(dual,1,0,0,0);feed(direct,1,0,0,0);
        for(int i=1;i<=2000;i++){double v=.003*Math.sin(2*Math.PI*10*i*.005);feed(dual,1+i*5000000L,0,v,0);feed(direct,1+i*5000000L,0,v,0);if(i>1000){noiseDual+=dual.dx*dual.dx;noiseDirect+=direct.dx*direct.dx;}}
        check(noiseDual<noiseDirect*.05,"dual path suppresses synthetic stationary oscillation energy");
        feed(dual,10005000001L,0,.5,0);near(dual.dx/(.5*.005*900),1,0.002,"dual path bypasses precision filter at strong onset");
    }
    private static void composition(){
        MotionEngine e=engine(10);e.config.customLowpass=false;e.config.customDeadzone=true;e.config.deadzone=.02;feed(e,1,0,.01,0);feed(e,5000001,0,.01,0);near(e.dx,0,0,"composed deadzone");e.config.customDeadzone=false;feed(e,10000001,0,.01,0);check(e.dx>0,"disabling block changes processing");
        MotionEngine.Config bad=new MotionEngine.Config();bad.axisY=bad.axisX;boolean threw=false;try{bad.validate();}catch(IllegalArgumentException ex){threw=true;}check(threw,"invalid axes rejected");
        MotionEngine.Config clone=e.config.copy();clone.gainX=3;check(e.config.gainX!=clone.gainX,"config copy independent");
    }
}
