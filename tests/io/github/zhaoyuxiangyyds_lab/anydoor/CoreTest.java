package io.github.zhaoyuxiangyyds_lab.anydoor;
import java.util.*;
public class CoreTest {
    static int count;
    static void check(boolean b,String m) { if (!b) throw new AssertionError(m); count++; }
    static class Backend implements ProviderController.Backend {
        Set<String> installed=new HashSet<>(), pushed=new HashSet<>();
        String failAdd="",failEnable="",failRemove="",failPush=""; int adds;
        public void add(String p) throws Exception { adds++; if(p.equals(failAdd)) throw new Exception("add failure"); installed.add(p); }
        public void enable(String p) throws Exception { if(p.equals(failEnable)) throw new Exception("enable failure"); }
        public void remove(String p) throws Exception { if(p.equals(failRemove)) throw new Exception("remove failure"); installed.remove(p); }
        public void push(String p) throws Exception { if(p.equals(failPush)) throw new Exception("push failure"); pushed.add(p); }
    }
    public static void main(String[] args) {
        Backend b=new Backend(); ProviderController c=new ProviderController(b); long token=c.start();
        b.failAdd="network"; c.reconcile(token,true,true,10000); c.push(100);
        check(c.enabled("gps")&&!c.enabled("network"),"partial creation preserves GPS");
        check(b.pushed.equals(Collections.singleton("gps")),"GPS pushed after network failure");
        check(c.error().contains("network"),"successful GPS push does not erase network error");
        c.stop(); check(b.installed.isEmpty(),"partial creation cleaned on stop");
        int adds=b.adds; c.reconcile(token,true,true,20000); check(b.adds==adds,"late initialization cannot resurrect stopped providers");
        long next=c.start(); c.reconcile(token,true,true,20000); check(b.adds==adds,"old generation cannot affect new run");
        b.failAdd=""; c.reconcile(next,true,false,20000); c.push(200);
        check(!b.installed.contains("network")&&c.enabled("gps"),"network disabled before creation");
        c.reconcile(next,true,true,21000); check(c.enabled("network"),"network enabled while running");
        c.reconcile(next,true,false,22000); check(!b.installed.contains("network"),"network removed on toggle off");
        c.reconcile(next,false,false,23000); check(b.installed.isEmpty(),"driver disabled removes all providers");
        b.failEnable="gps"; c.reconcile(next,true,false,24000);
        check(c.owned("gps")&&!c.enabled("gps"),"record ownership before failed enable");
        adds=b.adds; c.reconcile(next,true,false,24100); check(b.adds==adds,"failed setup is throttled");
        b.failRemove="gps"; c.stop(); check(c.owned("gps")&&c.error().contains("清理"),"failed cleanup remains tracked");
        b.failRemove=""; c.stop(); check(!c.owned("gps"),"cleanup retried successfully");
        b.failEnable=""; next=c.start(); c.reconcile(next,true,true,30000);
        b.failPush="gps"; c.push(500);
        check(c.error().contains("gps")&&c.lastPush()==500,"per-provider push errors and real success timestamp");
        check(!c.enabled("gps"),"failed push invalidates provider for recovery");
        adds=b.adds; c.reconcile(next,true,true,30100); check(b.adds==adds,"push failure backoff");
        b.failPush=""; c.reconcile(next,true,true,35000); check(c.enabled("gps"),"failed provider recreated after backoff");
        c.stop(); c.restoreOwnership("network"); b.installed.add("network"); c.stop(); check(b.installed.isEmpty(),"restart cleans persisted ownership");

        Map<String,Object> m=new HashMap<>();m.put("_schema",1);m.put("_revision",10L);m.put("_wall",1000L);m.put("_elapsed",100L);
        m.put("lat","31");m.put("lng","121");m.put("started",true);
        ConfigSnapshot s=ConfigSnapshot.from(m);
        check(s!=null&&s.started(1000,100),"valid active snapshot");
        check(!s.started(17000,16100),"lease expires if driver heartbeat stops");
        check(!s.started(1000,50),"elapsed time reset invalidates old lease");
        // intentValid grace window (frozen-driver fallback): stale heartbeat still counts
        check(s.intentValid(1000,100),"intentValid accepts fresh heartbeat");
        check(s.intentValid(1801000,1800100),"intentValid accepts stale heartbeat at grace boundary");
        check(!s.intentValid(1802000,1801100),"intentValid rejects stale heartbeat past grace");
        m.put(Keys.LEASE_GRACE,"60000");ConfigSnapshot g=ConfigSnapshot.from(m);m.remove(Keys.LEASE_GRACE);
        check(g.intentValid(61000,60100),"configured grace window respected at boundary");
        check(!g.intentValid(62000,61100),"configured grace window expiry");
        m.put("started",false);check(!ConfigSnapshot.from(m).started(1000,100),"stop snapshot never activates");
        m.put("lat","NaN");check(ConfigSnapshot.from(m)==null,"NaN rejected");
        m.put("lat","91");check(ConfigSnapshot.from(m)==null,"invalid latitude rejected");
        m.put("lat","31");m.remove("_schema");check(ConfigSnapshot.from(m)==null,"legacy unversioned snapshot rejected");
        System.out.println("CoreTest: "+count+" assertions passed");
    }
}
