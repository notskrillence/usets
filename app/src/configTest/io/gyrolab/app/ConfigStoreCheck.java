package io.gyrolab.app;
import io.gyrolab.core.MotionEngine.Config;
import java.lang.reflect.Field;
import org.json.JSONObject;
public class ConfigStoreCheck {
 static int passed;
 static void check(boolean b,String label){if(!b)throw new AssertionError(label);passed++;}
 interface Action {void run() throws Exception;}
 static void rejects(Action a,String label)throws Exception{try{a.run();throw new AssertionError(label);}catch(IllegalArgumentException expected){passed++;}}
 public static void main(String[] args)throws Exception{
  Config c=new Config();c.mode=11;c.axisX=2;c.axisY=1;c.invertX=true;c.biasZ=-.003;c.customPrediction=true;c.gripRollDegrees=-25;c.blend=.9;
  Config loaded=ConfigStore.decode(ConfigStore.encode(c));
  for(Field f:Config.class.getFields())check(f.get(c).equals(f.get(loaded)),"roundtrip "+f.getName());
  String encoded=ConfigStore.encode(c);
  rejects(()->ConfigStore.decode("{}"),"empty");
  rejects(()->ConfigStore.decode("not json"),"malformed");
  rejects(()->ConfigStore.decode(encoded.replace("\"version\": 1","\"version\": 2")),"schema version");
  JSONObject r=new JSONObject(encoded);r.getJSONObject("config").put("gainX","900");rejects(()->ConfigStore.decode(r.toString()),"numeric string");
  r.getJSONObject("config").put("gainX",900).put("invertX",1);rejects(()->ConfigStore.decode(r.toString()),"numeric boolean");
  r.getJSONObject("config").put("invertX",true).put("mode",1.25);rejects(()->ConfigStore.decode(r.toString()),"fractional int");
  r.getJSONObject("config").put("mode",0).put("axisX",1).put("axisY",1);rejects(()->ConfigStore.decode(r.toString()),"equal axes");
  r.getJSONObject("config").put("axisY",0).put("predictionMs",41);rejects(()->ConfigStore.decode(r.toString()),"unsafe prediction");
  r.getJSONObject("config").put("predictionMs",12).remove("beta");rejects(()->ConfigStore.decode(r.toString()),"missing field");
  r.getJSONObject("config").put("beta",.25).put("unknown",true);rejects(()->ConfigStore.decode(r.toString()),"unknown field");
  for(Field f:Config.class.getFields())if(f.getType()==double.class){Config bad=c.copy();f.setDouble(bad,Double.NaN);rejects(()->ConfigStore.encode(bad),"nonfinite "+f.getName());}
  check(loaded.customPrediction,"boolean survives");
  System.out.println("ConfigStore checks passed: "+passed);
 }
}
