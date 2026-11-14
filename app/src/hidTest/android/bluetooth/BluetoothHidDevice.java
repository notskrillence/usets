package android.bluetooth;
import java.util.*;import java.util.concurrent.Executor;
/** Records API submissions only; no Bluetooth, Android service or physical host is simulated. */
public class BluetoothHidDevice implements BluetoothProfile {
 public static final byte SUBCLASS1_MOUSE=(byte)0x80,REPORT_TYPE_INPUT=1,ERROR_RSP_UNSUPPORTED_REQ=3,PROTOCOL_BOOT_MODE=0,PROTOCOL_REPORT_MODE=1;
 public static class Callback {public void onAppStatusChanged(BluetoothDevice d,boolean registered){}public void onConnectionStateChanged(BluetoothDevice d,int state){}public void onGetReport(BluetoothDevice d,byte type,byte id,int size){}public void onSetReport(BluetoothDevice d,byte type,byte id,byte[] data){}public void onSetProtocol(BluetoothDevice d,byte protocol){}public void onVirtualCableUnplug(BluetoothDevice d){}}
 public static class Report {public final int id;public final byte[] data;Report(int id,byte[] bytes){this.id=id;data=bytes.clone();}}
 public final List<Report> reports=new ArrayList<>();public Callback callback;public BluetoothHidDeviceAppSdpSettings sdp;public boolean failNext,throwNext,registrationAccepted=true;public int connectCalls,disconnectCalls,unregisterCalls,replyId=-1,error=-1;public byte[] reply;private int state=STATE_DISCONNECTED;
 public boolean registerApp(BluetoothHidDeviceAppSdpSettings s,Object in,Object out,Executor e,Callback c){sdp=s;callback=c;return registrationAccepted;}
 public boolean connect(BluetoothDevice d){connectCalls++;return true;}
 public boolean disconnect(BluetoothDevice d){disconnectCalls++;state=STATE_DISCONNECTED;return true;}
 public int getConnectionState(BluetoothDevice d){return state;}
 public boolean sendReport(BluetoothDevice d,int id,byte[] data){if(throwNext){throwNext=false;throw new SecurityException("test denial");}if(failNext){failNext=false;return false;}reports.add(new Report(id,data));return true;}
 public boolean unregisterApp(){unregisterCalls++;return true;}
 public boolean replyReport(BluetoothDevice d,byte type,byte id,byte[] bytes){replyId=id;reply=bytes.clone();return true;}
 public boolean reportError(BluetoothDevice d,byte code){error=code;return true;}
 public void connection(BluetoothDevice d,int state){this.state=state;callback.onConnectionStateChanged(d,state);}
}
