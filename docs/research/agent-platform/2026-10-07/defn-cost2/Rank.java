import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.util.*;
public class Rank {
  public static void main(String[] args) throws Exception {
    Map<String,Long> self=new HashMap<>(), incl=new HashMap<>(), bins=new HashMap<>(), nativeOwners=new HashMap<>(); long total=0; int n=0, trunc=0, max=0;
    try(var r=new RecordingFile(Path.of(args[0]))) {
      while(r.hasMoreEvents()) {
        var e=r.readEvent(); if(!e.getEventType().getName().equals("jdk.ObjectAllocationSample"))continue;
        long w=e.getLong("weight"); total+=w; n++;
        var st=e.getStackTrace(); if(st==null)continue; if(st.isTruncated())trunc++; max=Math.max(max,st.getFrames().size());
        Set<String> classes=new HashSet<>(); Set<String> seen=new HashSet<>(); String owner="other";
        for(var f:st.getFrames()) {
          String c=f.getMethod().getType().getName(); classes.add(c);
          if(!c.startsWith("seon."))continue;
          String[] parts=c.split("\\$"); String name=parts.length>1?parts[0]+"/"+parts[1].replace('_','-'):c;
          if(seen.add(name))incl.merge(name,w,Long::sum);
          if(owner.equals("other") && !name.startsWith("seon.instrument/") && !name.equals("seon.db/with-declarations") && !name.startsWith("seon.schema/call-"))owner=name;
        }
        self.merge(owner,w,Long::sum);
        String[][] rules={
          {"projection", "seon.db/carried-projection", "seon.db/speculative-projection", "seon.db/declaration-content-key", "seon.schema/load-projection", "seon.schema/projection-from-entities"},
          {"arity-gate", "seon.db/report-arity-comparison"},
          {"reach-digests", "seon.db/stored-call-reach", "seon.db/builder-digest", "seon.fn/call-reach", "seon.fn/reach-digests", "seon.fn/definition-digest"},
          {"fault-window", "seon.turn/fault-window"},
          {"run-reads", "seon.turn/current-run", "seon.turn/run-observation"},
          {"context-acquisition", "seon.sci.eval/regenerate-agent-context-BANG-", "seon.sci.eval/base-bindings", "seon.sci.eval/acquire-program-BANG-"},
          {"kondo-analysis", "seon.fn.analyzer/invoke-kondo", "seon.fn/runtime-analysis-batch"},
          {"auto-check", "seon.test.accretion/generatable-QMARK-", "seon.test.check/check", "seon.test/run"},
          {"scheduling", "seon.test/select", "seon.test/schedule"},
          {"contract-install", "seon.sci.eval/install-function-contract-BANG-", "seon.call_preparation/contract-arities"},
          {"sci-evaluation", "seon.sci.eval/evaluate"},
          {"printing", "seon.batch/print-into", "seon.render.value/unit", "seon.render.value/show", "seon.print/value"},
          {"recording", "seon.turn.batch/record-facts", "seon.turn/close-folded-call"},
          {"settlement", "seon.turn/settle-batch-BANG-", "seon.turn.batch/settle"},
          {"writer", "seon.db/prepared-write", "seon.db/validate-write-report", "seon.db/write-report-error"}
        };
        String bin="other";
        outer: for(String[] rule:rules) for(int j=1;j<rule.length;j++) if(seen.contains(rule[j])) {bin=rule[0];break outer;}
        if(bin.equals("other")) {
          if(classes.stream().anyMatch(c->c.startsWith("datahike.")||c.startsWith("konserve.")))bin="native-store";
          else if(seen.stream().anyMatch(c->c.startsWith("seon.pass_probe/")||c.startsWith("seon.profile.measurement/")))bin="measurement";
        }
        bins.merge(bin,w,Long::sum);
        if(bin.equals("native-store")) {
          String nativeOwner="other";
          for(var f:st.getFrames()) {
            String c=f.getMethod().getType().getName();
            if(c.startsWith("datahike.")||c.startsWith("konserve.")||c.startsWith("superv.")||c.startsWith("taoensso.nippy")) {nativeOwner=c;break;}
          }
          nativeOwners.merge(nativeOwner,w,Long::sum);
        }
      }
    }
    System.out.println("total="+total+" samples="+n+" truncated="+trunc+" max-depth="+max);
    for(var map:List.of(bins,nativeOwners,self,incl)) {
      System.out.println(map==bins?"DISJOINT BINS":map==nativeOwners?"NATIVE OWNERS":map==self?"SELF":"INCLUSIVE");
      map.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).forEach(e->System.out.printf("%10.2f %s%n",e.getValue()/1e6,e.getKey()));
    }
  }
}
