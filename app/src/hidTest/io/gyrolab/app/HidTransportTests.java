package io.gyrolab.app;

import android.bluetooth.*;
import android.content.Context;
import android.os.Handler;
import java.util.*;

/** Compiles the ACTUAL production HidTransport against explicitly fake Android APIs. */
public final class HidTransportTests {
    private static int checks;
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);checks++;}
    private static void zero(BluetoothHidDevice.Report report,String label){for(byte b:report.data)check(b==0,label);}
    private static BluetoothHidDevice.Report last(Fixture f){return f.fake.reports.get(f.fake.reports.size()-1);}
    private static final class Fixture {
        final BluetoothHidDevice fake=new BluetoothHidDevice();
        final BluetoothAdapter adapter=new BluetoothAdapter(fake);
        final Handler handler=new Handler();
        final BluetoothDevice host=new BluetoothDevice("Test PC","00:01:02:03:04:05");
        final HidTransport transport=new HidTransport(new Context(new BluetoothManager(adapter)),handler);
        Fixture(){adapter.bonded.add(host);transport.start();check(fake.sdp!=null,"actual registration constructed descriptor");check(!transport.ready(),"request success is not connection confirmation");fake.callback.onAppStatusChanged(null,true);transport.connect(host);fake.connection(host,BluetoothProfile.STATE_CONNECTED);check(transport.ready(),"connection callback enables transport");zero(last(this),"initial connected state releases buttons");fake.reports.clear();}
        void reconnect(){transport.connect(host);fake.connection(host,BluetoothProfile.STATE_CONNECTED);}
    }

    private static void descriptor(){
        byte[] d=HidTransport.REPORT_DESCRIPTOR;int i=0,size=0,count=0,id=0,min=0,max=0,page=0,bits=0,collections=0,inputs=0;List<Integer> usages=new ArrayList<>();
        while(i<d.length){
            int prefix=d[i++]&255;check(prefix!=254,"short descriptor item");int bytes=prefix&3;if(bytes==3)bytes=4;
            check(i+bytes<=d.length,"descriptor item has complete payload");int unsigned=0;for(int b=0;b<bytes;b++)unsigned|=(d[i++]&255)<<(8*b);
            int type=(prefix>>2)&3,tag=prefix>>4;int signed=bytes==1?(byte)unsigned:bytes==2?(short)unsigned:unsigned;
            if(type==1){if(tag==0)page=unsigned;else if(tag==1)min=signed;else if(tag==2)max=min<0?signed:unsigned;else if(tag==7)size=unsigned;else if(tag==8)id=unsigned;else if(tag==9)count=unsigned;}
            else if(type==2&&tag==0)usages.add(unsigned);
            else if(type==0){
                if(tag==10)collections++;if(tag==12)collections--;
                check(collections>=0,"descriptor collection balance");
                if(tag==8){
                    inputs++;check(id==HidTransport.REPORT_ID,"mouse report ID");
                    if(inputs==1){check(page==9&&size==1&&count==3&&min==0&&max==1&&unsigned==2,"three one-bit data buttons");}
                    if(inputs==2){check(size==5&&count==1&&unsigned==1,"five constant padding bits");}
                    if(inputs==3){check(page==1&&size==8&&count==3&&min==-127&&max==127&&unsigned==6,"signed8 relative X Y wheel");check(usages.equals(Arrays.asList(0x30,0x31,0x38)),"axis usages are X Y wheel");}
                    bits+=size*count;
                }
                usages.clear();
            }
        }
        check(collections==0,"all HID collections closed");check(inputs==3&&bits==32,"report payload is exactly four bytes");
    }
    private static void splitting(){
        Fixture f=new Fixture();
        int[][] cases={{1,-1,0},{127,-127,1},{128,-128,-1},{1000,-1750,260},{-777,300,0},{8128,-8128,-8128},{-8128,8128,8128},{0,0,0}};
        for(int[] c:cases){
            f.fake.reports.clear();f.transport.send(c[0],c[1],5,c[2]);int x=0,y=0,wheel=0;
            check(f.fake.reports.size()<=64,"bounded report splitting");
            for(BluetoothHidDevice.Report r:f.fake.reports){check(r.id==1&&r.data.length==4,"runtime report ID and length");check(r.data[0]==5,"held buttons preserved in every split");for(int n=1;n<4;n++)check(r.data[n]>=-127&&r.data[n]<=127,"never encodes forbidden -128");x+=r.data[1];y+=r.data[2];wheel+=r.data[3];}
            check(x==c[0]&&y==c[1]&&wheel==c[2],"positive/negative motion and wheel preserved losslessly");
        }
        f.transport.send(20,-3,255,0);check(last(f).data[0]==7,"only three button bits can be sent");f.transport.release();zero(last(f),"release clears all fields");
        f.transport.send(0,0,1,0);f.fake.callback.onGetReport(f.host,BluetoothHidDevice.REPORT_TYPE_INPUT,(byte)1,4);check(f.fake.replyId==1&&f.fake.reply.length==4&&f.fake.reply[0]==1,"GET_REPORT reflects current button state");
        f.fake.callback.onGetReport(f.host,BluetoothHidDevice.REPORT_TYPE_INPUT,(byte)99,4);check(f.fake.error==BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ,"unknown report rejected");
    }
    private static void safetyAndFailures(){
        Fixture f=new Fixture();f.transport.send(0,0,1,0);f.fake.reports.clear();f.transport.send(Integer.MIN_VALUE,2,1,0);
        check(!f.transport.ready()&&f.transport.rejectedReports==1,"oversized integer safely faults without abs overflow");check(f.fake.reports.size()==1,"safety stop sends only release");zero(last(f),"safety stop releases drag");
        int before=f.fake.reports.size();f.transport.send(5,5,1,0);check(f.fake.reports.size()==before,"fault cannot continue movement");
        f.reconnect();check(f.transport.ready(),"explicit reconnection clears fault");zero(last(f),"reconnect starts released");
        f.fake.reports.clear();f.fake.failNext=true;long rejected=f.transport.rejectedReports;f.transport.send(15,20,1,0);
        check(!f.transport.ready()&&f.transport.rejectedReports==rejected+1,"API false return faults transport");check(f.fake.reports.size()==1,"failed movement attempts release");zero(last(f),"failed movement cannot leave held button in next report");
        f.reconnect();f.fake.reports.clear();f.fake.throwNext=true;f.transport.send(1,1,1,0);check(!f.transport.ready(),"permission exception does not escape send");zero(last(f),"exception path attempts release");
    }
    private static void lifecycle(){
        Fixture f=new Fixture();check(f.transport.bondedHosts().size()==1,"paired host selection uses platform bonded devices");
        f.transport.send(10,0,1,0);f.fake.connection(f.host,BluetoothProfile.STATE_DISCONNECTED);check(!f.transport.ready()&&f.transport.disconnectEvents==1,"disconnect observed");check(f.handler.delayedCount()==1,"unexpected disconnect schedules bounded retry");
        int before=f.fake.reports.size();f.transport.send(999,999,1,1);check(f.fake.reports.size()==before,"disconnected movement never queued");
        int connects=f.fake.connectCalls;f.handler.runDelayed();check(f.fake.connectCalls==connects+1,"retry calls actual connection API");f.fake.connection(f.host,BluetoothProfile.STATE_CONNECTED);zero(last(f),"reconnection releases previously held buttons");
        f.fake.reports.clear();f.transport.send(1,2,0,0);check(f.fake.reports.size()==1&&last(f).data[1]==1&&last(f).data[2]==2,"no stale movement after reconnect");
        f.transport.send(0,0,1,0);f.transport.disconnect();zero(last(f),"explicit disconnect releases drag before close");check(!f.transport.ready()&&f.handler.delayedCount()==0,"explicit disconnect cancels retry");
        f.reconnect();f.fake.callback.onSetProtocol(f.host,BluetoothHidDevice.PROTOCOL_BOOT_MODE);check(f.transport.bootProtocol,"host can select boot protocol");f.fake.reports.clear();long rejects=f.transport.rejectedReports;f.transport.send(-128,255,3,1);
        int x=0,y=0;for(BluetoothHidDevice.Report r:f.fake.reports){check(r.id==0&&r.data.length==3,"boot report is ID0 with three bytes");check(r.data[0]==3,"boot holds buttons");x+=r.data[1];y+=r.data[2];}check(x==-128&&y==255,"boot movement remains lossless");check(f.transport.rejectedReports==rejects+1,"unsupported boot scroll reported");
        f.fake.callback.onSetProtocol(f.host,BluetoothHidDevice.PROTOCOL_REPORT_MODE);check(!f.transport.bootProtocol,"report protocol restored");zero(last(f),"protocol switch releases buttons");
        f.transport.send(0,0,1,0);f.fake.callback.onVirtualCableUnplug(f.host);zero(last(f),"virtual unplug attempts release");check(!f.transport.ready()&&f.handler.delayedCount()==0,"unplug disables auto-reconnect");
        f.reconnect();f.transport.close();check(!f.transport.ready()&&f.fake.unregisterCalls==1&&f.adapter.closed==1,"close unregisters and closes profile");before=f.fake.reports.size();f.transport.send(100,100,7,3);f.transport.start();check(f.fake.reports.size()==before&&!f.transport.ready(),"closed transport never emits future input");
        Fixture permission=new Fixture();permission.adapter.denyPermission=true;permission.transport.bondedHosts();check(permission.transport.status.contains("permission"),"paired device permission error is observable");
    }
    private static void pairing() {
        Fixture f=new Fixture();f.transport.disconnect();int before=f.fake.connectCalls;
        f.host.bondState=11;f.transport.connect(f.host);
        check(f.fake.connectCalls==before,"never initiate HID while SSP bond is in progress");
        f.host.bondState=12;f.transport.connect(f.host);
        check(f.fake.connectCalls==before+1,"manual connect allowed after bond completes");
        f.transport.beginPairing();
        check(!f.transport.isPairingPrepared(),"fresh pairing waits for new registration callback");
        check(f.handler.delayedCount()==0,"fresh pairing cancels outgoing retries");
        f.fake.callback.onAppStatusChanged(null,false);
        check(!f.transport.isPairingPrepared(),"unregister event cannot expose premature discovery readiness");
        before=f.fake.connectCalls;f.fake.callback.onAppStatusChanged(f.host,true);
        check(f.transport.isPairingPrepared(),"fresh registration enables host-initiated discovery");
        check(f.fake.connectCalls==before,"plugged-device callback does not initiate a competing HID connection");
    }
    public static void main(String[] args){descriptor();splitting();safetyAndFailures();lifecycle();pairing();System.out.println("HidTransport API-double tests: "+checks+" assertions passed. No Android/Bluetooth/Windows hardware was exercised.");}
}
