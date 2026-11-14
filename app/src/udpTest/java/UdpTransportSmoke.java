import android.os.Handler;
import io.gyrolab.app.UdpTransport;
import java.net.*;
import java.nio.*;
public class UdpTransportSmoke {
 static int checks;
 static void check(boolean b){++checks;if(!b)throw new AssertionError("check "+checks);}
 static ByteBuffer receive(DatagramSocket server)throws Exception{
  byte[] bytes=new byte[100];DatagramPacket p=new DatagramPacket(bytes,bytes.length);server.receive(p);
  check(p.getLength()==40);ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
  check(b.getInt()==0x474c5831);return b;
 }
 public static void main(String[]args)throws Exception{
  DatagramSocket server=new DatagramSocket(26761,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(2000);
  Handler h=new Handler();UdpTransport u=new UdpTransport(h);
  check(!u.ready());u.connect("127.0.0.1");check(u.ready());check(u.status.contains("host unconfirmed"));
  ByteBuffer b=receive(server);int session=b.getInt();check(b.getInt()==0);check(b.getLong()>0);
  check(b.getInt()==0&&b.getInt()==0&&b.getInt()==0&&b.getInt()==0&&b.getInt()==2);check(h.delayed.size()==1);
  u.send(-123,125,5,-2);b=receive(server);check(b.getInt()==session);check(b.getInt()==1);b.getLong();
  check(b.getInt()==-123&&b.getInt()==125&&b.getInt()==5&&b.getInt()==-2&&b.getInt()==0);
  h.delayed.remove(0).run();b=receive(server);b.getInt();check(b.getInt()==2);b.getLong();
  check(b.getInt()==0&&b.getInt()==0&&b.getInt()==5&&b.getInt()==0&&b.getInt()==1);check(h.delayed.size()==1);
  u.send(32768,0,0,0);check(u.rejectedReports==1);
  u.release();b=receive(server);b.getInt();check(b.getInt()==3);b.getLong();check(b.getInt()==0&&b.getInt()==0&&b.getInt()==0&&b.getInt()==0&&b.getInt()==2);
  u.close();b=receive(server);b.getInt();check(b.getInt()==4);b.getLong();check(b.getInt()==0&&b.getInt()==0&&b.getInt()==0&&b.getInt()==0&&b.getInt()==2);
  check(!u.ready());check(h.delayed.isEmpty());check(u.reportsSubmitted==5);server.close();
  System.out.println("PASS: "+checks+" actual UDP loopback checks with explicit Android scheduling/clock JVM doubles (not Android execution)");
 }
}
