package io.github.zhaoyuxiangyyds_lab.anydoor.xposed;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import org.json.JSONObject;
import android.os.SystemClock;
import de.robv.android.xposed.XSharedPreferences;
public class StateTest {
    static int count; static Path mirror=Paths.get(System.getProperty("test.mirror"));
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);count++;}
    static Map<String,Object> values(long version,boolean started,String lat) {
        Map<String,Object> m=new HashMap<>();m.put("_schema",1);m.put("_revision",version);
        m.put("_wall",System.currentTimeMillis());m.put("_elapsed",SystemClock.tick);
        m.put("started",started);m.put("lat",lat);m.put("lng","121");return m;
    }
    static void reset()throws Exception{Files.deleteIfExists(mirror);XSharedPreferences.fail=false;XSharedPreferences.failRead=false;XSharedPreferences.values.clear();SystemClock.tick+=20000;}
    static void tick(){SystemClock.tick+=1000;}
    static void write(Map<String,Object> v)throws Exception{Files.write(mirror,new JSONObject(v).toString().getBytes("UTF-8"));}
    public static void main(String[] args)throws Exception{
        reset();XSharedPreferences.fail=true;write(values(1,true,"11"));SpoofState st=new SpoofState();
        check(st.started()&&st.channel().equals("root"),"constructor failure does not prevent root fallback");
        reset();XSharedPreferences.values.putAll(values(1,true,"11"));st=new SpoofState();
        check(st.started()&&st.channel().equals("prefs"),"API values usable without direct file access");
        tick();XSharedPreferences.failRead=true;write(values(2,false,"22"));
        check(!st.started()&&st.channel().equals("root")&&st.num("lat",0)==22,"failed primary yields to fresh root stop");
        reset();XSharedPreferences.values.putAll(values(1,true,"11"));write(values(2,false,"22"));st=new SpoofState();
        check(!st.started()&&st.revision()==2,"stale readable primary cannot override newer stop");
        reset();write(values(1,true,"11"));st=new SpoofState();check(st.started(),"root control");
        Files.delete(mirror);tick();check(!st.started()&&!st.configReadable(),"missing mirror invalidates cached active state");
        reset();write(values(1,true,"11"));FileTime mt=Files.getLastModifiedTime(mirror);st=new SpoofState();check(st.num("lat",0)==11,"first fix");
        tick();write(values(2,true,"22"));Files.setLastModifiedTime(mirror,mt);check(st.num("lat",0)==22,"equal mtime does not hide changes");
        tick();write(values(1,true,"11"));check(!st.configReadable(),"reject version rollback");
        reset();write(values(1,true,"11"));st=new SpoofState();check(st.started(),"lease starts active");
        SystemClock.tick+=16000;check(st.started(),"grace window keeps spoofing past lease expiry");
        SystemClock.tick+=1800000;check(!st.started(),"grace window expiry stops spoofing");
        reset();Files.write(mirror,"{broken".getBytes("UTF-8"));st=new SpoofState();check(!st.started()&&!st.configReadable(),"truncated JSON does not activate");
        tick();write(values(1,true,"33"));check(st.started(),"recovers when valid mirror appears");
        reset();XSharedPreferences.fail=true;st=new SpoofState();check(!st.started(),"no source means stopped");
        SystemClock.tick+=6000;XSharedPreferences.fail=false;XSharedPreferences.values.putAll(values(2,true,"33"));check(st.started(),"constructor retried after transient failure");
        reset();Map<String,Object> v=values(1,true,"11");v.put("exempt","com.example.client");v.put("privacy",true);write(v);
        st=new SpoofState(false,"com.example.client");check(!st.started()&&!st.privacy(),"client exemption applies to location and privacy");
        tick();v=values(2,true,"11");v.put("app_hook",false);write(v);check(!st.started(),"client hook switch honored");
        System.out.println("StateTest: "+count+" assertions passed");
    }
}
