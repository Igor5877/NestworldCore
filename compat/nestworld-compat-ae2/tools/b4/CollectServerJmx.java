import com.sun.tools.attach.VirtualMachine;
import java.io.*;
import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.management.*;
import javax.management.remote.*;

/** Neutral test-server-only collector. No AE2 addon classes, commands, or instrumentation. */
public class CollectServerJmx {
    static final String MC_NAME="net.minecraft.server:type=Server";
    static final class Ring {
        long[] previous; int cursor=-1; long samples; int ambiguousPolls; boolean valid=true;
        final List<String> reasons=new ArrayList<>();
        Ring(long[] first){previous=first.clone();}
        int[] accept(long[] next,long gapNanos) {
            if(next.length!=previous.length){bad("ring-length-changed");previous=next.clone();cursor=-1;return new int[0];}
            int n=next.length,k=0,starts=0,start=-1;boolean[] changed=new boolean[n];
            for(int i=0;i<n;i++){changed[i]=next[i]!=previous[i];if(changed[i])k++;}
            // A ring could wrap after a long observer pause. No tick counter exists to disambiguate it.
            if(gapNanos>=n*50_000_000L)bad("poll-gap-at-least-ring-duration-at-20tps");
            if(k==0){previous=next.clone();return new int[0];}
            if(k==n){bad("all-ring-slots-changed-wrap-ambiguous");previous=next.clone();cursor=-1;return new int[0];}
            for(int i=0;i<n;i++)if(changed[i]&&!changed[(i+n-1)%n]){starts++;start=i;}
            if(starts!=1){bad("noncontiguous-ring-changes");previous=next.clone();cursor=-1;return new int[0];}
            if(cursor>=0&&start!=(cursor+1)%n)bad("ring-cursor-skipped-or-identical-value-overwrite");
            int[] indices=new int[k];
            for(int j=0;j<k;j++){int i=(start+j)%n;if(!changed[i]){bad("ring-order-ambiguous");previous=next.clone();cursor=-1;return new int[0];}indices[j]=i;}
            cursor=(start+k-1)%n;previous=next.clone();samples+=k;return indices;
        }
        void bad(String reason){valid=false;ambiguousPolls++;if(!reasons.contains(reason))reasons.add(reason);}
    }
    record Gc(long count,long timeMs) {}
    record Snapshot(long[] ring,long cpuBefore,long cpuAfter,long startedNs,long endedNs,double averageMs) {}

    static long mainId(ThreadMXBean threads)throws Exception{
        long[] ids=threads.getAllThreadIds();ThreadInfo[] infos=threads.getThreadInfo(ids,0);long found=-1;
        for(ThreadInfo info:infos)if(info!=null&&"Server thread".equals(info.getThreadName())){if(found!=-1)throw new IllegalStateException("Multiple Server threads");found=info.getThreadId();}
        if(found<0)throw new IllegalStateException("Server thread not found");return found;
    }
    static Snapshot snapshot(MBeanServerConnection conn,ObjectName mc,ThreadMXBean threads,long id)throws Exception{
        long begin=System.nanoTime(),before=threads.getThreadCpuTime(id);
        AttributeList attributes=conn.getAttributes(mc,new String[]{"tickTimes","averageTickTime"});
        long[] ring=null;double average=Double.NaN;
        for(Attribute a:attributes.asList()){
            if(a.getName().equals("tickTimes"))ring=((long[])a.getValue()).clone();
            else if(a.getName().equals("averageTickTime")&&a.getValue() instanceof Number n)average=n.doubleValue();
        }
        long after=threads.getThreadCpuTime(id),end=System.nanoTime();
        if(ring==null||ring.length<2)throw new IllegalStateException("Minecraft tickTimes missing/invalid");
        if(before<0||after<before)throw new IllegalStateException("MAIN CPU counter unsupported/reset");
        return new Snapshot(ring,before,after,begin,end,average);
    }
    static Map<String,Gc> gc(MBeanServerConnection conn)throws Exception{
        Map<String,Gc> out=new TreeMap<>();
        for(ObjectName name:conn.queryNames(new ObjectName("java.lang:type=GarbageCollector,name=*"),null)){
            AttributeList attrs=conn.getAttributes(name,new String[]{"CollectionCount","CollectionTime"});long count=-1,time=-1;
            for(Attribute a:attrs.asList())if(a.getValue() instanceof Number n){if(a.getName().equals("CollectionCount"))count=n.longValue();if(a.getName().equals("CollectionTime"))time=n.longValue();}
            out.put(name.getKeyProperty("name"),new Gc(count,time));
        }return out;
    }
    static double percentile(long[] sorted,double p){return sorted[Math.min(sorted.length-1,(int)Math.floor(sorted.length*p))]/1e6;}
    static String q(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\"";}
    static String number(double v){return Double.isFinite(v)?String.format(Locale.ROOT,"%.9f",v):"null";}
    static void selfTest(){
        Ring r=new Ring(new long[]{1,2,3,4,5});check(Arrays.equals(r.accept(new long[]{1,2,30,40,5},200_000_000),new int[]{2,3})&&r.valid,"initial sequence");
        check(Arrays.equals(r.accept(new long[]{10,2,30,40,50},200_000_000),new int[]{4,0})&&r.valid,"wrap sequence");
        r.accept(new long[]{10,2,31,40,50},200_000_000);check(!r.valid,"cursor gap detected");
        r=new Ring(new long[]{1,2,3,4,5});r.accept(new long[]{10,2,30,4,5},100_000_000);check(!r.valid,"disjoint sequence detected");
        r=new Ring(new long[]{1,2,3,4,5});r.accept(new long[]{10,20,30,40,50},100_000_000);check(!r.valid,"full wrap ambiguous");
        r=new Ring(new long[]{1,2,3,4,5});r.accept(new long[]{1,2,3,4,5},300_000_000);check(!r.valid,"observer gap detected");
        System.out.println("ring self-tests PASS");
    }
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        if(args.length==1&&args[0].equals("--self-test")){selfTest();return;}
        if(args.length<3||args.length>4)throw new IllegalArgumentException("Usage: java --add-modules jdk.attach CollectServerJmx.java <test-java-pid> <seconds> <output-prefix> [poll-ms=200]");
        String pid=args[0];long seconds=Long.parseLong(args[1]),pollMs=args.length==4?Long.parseLong(args[3]):200;
        if(seconds<1||seconds>7200||pollMs<50||pollMs>1000)throw new IllegalArgumentException("Invalid duration/poll interval");
        Path cwd=Path.of("/proc",pid,"cwd").toRealPath();
        if(cwd.equals(Path.of("/root/project-infinity")))throw new IllegalArgumentException("This collector refuses the live server; use a separate test JVM");
        Path prefix=Path.of(args[2]).toAbsolutePath();Files.createDirectories(prefix.getParent());
        for(String suffix:new String[]{"-ticks.csv","-polls.csv","-summary.json"})if(Files.exists(Path.of(prefix+suffix)))throw new IllegalArgumentException("Refusing overwrite "+prefix+suffix);
        String address;boolean startedAgent=false;VirtualMachine vm=VirtualMachine.attach(pid);
        try{address=vm.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");if(address==null){address=vm.startLocalManagementAgent();startedAgent=true;}}finally{vm.detach();}
        try(JMXConnector jmx=JMXConnectorFactory.connect(new JMXServiceURL(address))){
            MBeanServerConnection conn=jmx.getMBeanServerConnection();ObjectName mc=new ObjectName(MC_NAME);
            if(!conn.isRegistered(mc))throw new IllegalStateException("Minecraft neutral bean missing; server.properties enable-jmx-monitoring=true is required");
            ThreadMXBean threads=ManagementFactory.newPlatformMXBeanProxy(conn,ManagementFactory.THREAD_MXBEAN_NAME,ThreadMXBean.class);
            if(!threads.isThreadCpuTimeSupported()||!threads.isThreadCpuTimeEnabled())throw new IllegalStateException("Thread CPU counters must already be supported/enabled");
            long id=mainId(threads);Map<String,Gc> gcStart=gc(conn);Snapshot first=snapshot(conn,mc,threads,id),last=first;Ring ring=new Ring(first.ring);
            long start=first.endedNs,deadline=start+seconds*1_000_000_000L,nextPoll=start+pollMs*1_000_000L,maxGap=0,maxRead=first.endedNs-first.startedNs;int polls=0;
            List<Long> samples=new ArrayList<>();List<String> readErrors=new ArrayList<>();String startedAt=Instant.now().toString();
            try(BufferedWriter ticks=Files.newBufferedWriter(Path.of(prefix+"-ticks.csv"),StandardCharsets.UTF_8);BufferedWriter observations=Files.newBufferedWriter(Path.of(prefix+"-polls.csv"),StandardCharsets.UTF_8)){
                ticks.write("observed_elapsed_ns,ring_slot,tick_duration_ns\n");observations.write("elapsed_ns,changed_slots,average_tick_time_ms,main_cpu_ns,read_ns,sequence_valid\n");
                while(System.nanoTime()<deadline){
                    long wait=nextPoll-System.nanoTime();if(wait>0)Thread.sleep(wait/1_000_000L,(int)(wait%1_000_000L));
                    try{
                        Snapshot now=snapshot(conn,mc,threads,id);long gap=now.endedNs-last.endedNs;maxGap=Math.max(maxGap,gap);maxRead=Math.max(maxRead,now.endedNs-now.startedNs);
                        int[] changed=ring.accept(now.ring,gap);for(int slot:changed){long ns=now.ring[slot];if(ns<=0){ring.bad("nonpositive-tick-duration");continue;}samples.add(ns);ticks.write((now.endedNs-start)+","+slot+","+ns+"\n");}
                        observations.write((now.endedNs-start)+","+changed.length+","+number(now.averageMs)+","+now.cpuAfter+","+(now.endedNs-now.startedNs)+","+ring.valid+"\n");
                        if(now.cpuBefore<last.cpuBefore){ring.bad("main-cpu-counter-reset");}
                        last=now;polls++;
                    }catch(Exception failed){ring.bad("jmx-read-error");readErrors.add(failed.getClass().getSimpleName()+": "+failed.getMessage());}
                    nextPoll+=pollMs*1_000_000L;if(nextPoll<System.nanoTime())nextPoll=System.nanoTime()+pollMs*1_000_000L;
                }
            }
            Map<String,Gc> gcEnd=gc(conn);ThreadInfo finalInfo=threads.getThreadInfo(id,0);boolean sameThread=finalInfo!=null&&finalInfo.getThreadName().equals("Server thread");
            long[] sorted=samples.stream().mapToLong(Long::longValue).sorted().toArray();long n=sorted.length;double wall=(last.endedNs-first.endedNs)/1e9;
            double cpuLow=n==0?Double.NaN:Math.max(0,last.cpuBefore-first.cpuAfter)/1e6/n,cpuHigh=n==0?Double.NaN:(last.cpuAfter-first.cpuBefore)/1e6/n;
            StringBuilder json=new StringBuilder("{\n");
            json.append("  \"startedAt\": ").append(q(startedAt)).append(",\n  \"testCwd\": ").append(q(cwd.toString())).append(",\n  \"pid\": ").append(q(pid));
            json.append(",\n  \"targetSeconds\": ").append(seconds).append(",\n  \"observedSeconds\": ").append(number(wall)).append(",\n  \"pollMs\": ").append(pollMs).append(",\n  \"polls\": ").append(polls);
            json.append(",\n  \"samples\": ").append(n).append(",\n  \"absoluteTickCountAvailable\": false,\n  \"ringSequenceValidated\": ").append(ring.valid).append(",\n  \"ambiguousPolls\": ").append(ring.ambiguousPolls);
            json.append(",\n  \"sameMainThread\": ").append(sameThread).append(",\n  \"qualified\": ").append(ring.valid&&sameThread&&n>=100&&readErrors.isEmpty());
            json.append(",\n  \"maxPollGapMs\": ").append(number(maxGap/1e6)).append(",\n  \"maxReadMs\": ").append(number(maxRead/1e6));
            json.append(",\n  \"MSPTavg\": ").append(number(n==0?Double.NaN:Arrays.stream(sorted).average().orElse(Double.NaN)/1e6));
            for(double p:new double[]{.50,.95,.99})json.append(",\n  \"MSPTp").append((int)(p*100)).append("\": ").append(number(n==0?Double.NaN:percentile(sorted,p)));
            json.append(",\n  \"MSPTmax\": ").append(number(n==0?Double.NaN:sorted[sorted.length-1]/1e6));
            json.append(",\n  \"observedTicksPerSecond\": ").append(number(n/wall));
            json.append(",\n  \"MAINcpuMsPerObservedTick\": ").append(number((cpuLow+cpuHigh)/2)).append(",\n  \"MAINcpuBoundaryLowMsPerTick\": ").append(number(cpuLow)).append(",\n  \"MAINcpuBoundaryHighMsPerTick\": ").append(number(cpuHigh));
            json.append(",\n  \"managementAgentStartedByCollector\": ").append(startedAgent).append(",\n  \"gcDeltas\": {");boolean comma=false;
            for(var entry:gcEnd.entrySet()){
                Gc b=gcStart.get(entry.getKey()),e=entry.getValue();if(comma)json.append(',');comma=true;
                json.append("\n    ").append(q(entry.getKey())).append(": {\"collectionCount\": ").append(b==null||b.count<0||e.count<0?"null":e.count-b.count).append(", \"collectionTimeMs\": ").append(b==null||b.timeMs<0||e.timeMs<0?"null":e.timeMs-b.timeMs).append('}');
            }json.append("\n  },\n  \"invalidReasons\": [");
            for(int i=0;i<ring.reasons.size();i++){if(i>0)json.append(',');json.append(q(ring.reasons.get(i)));}json.append("],\n  \"readErrors\": [");
            for(int i=0;i<readErrors.size();i++){if(i>0)json.append(',');json.append(q(readErrors.get(i)));}json.append("],\n  \"limitations\": ").append(q("Neutral Minecraft bean exposes no absolute tick counter. Validated contiguous changed slots provide observed completed-tick samples; identical-nanosecond overwrites and an undetectable full wrap cannot be ruled out mathematically. CPU is whole Server-thread counter delta normalized by observed completed ticks, includes between-tick work, and has partial-tick boundary uncertainty. GC collection time is an MXBean aggregate, not pause quantiles. JMX polling overhead is present equally in all test variants; use read latency and sequence validity to reject poor windows.")).append("\n}\n");
            Files.writeString(Path.of(prefix+"-summary.json"),json.toString(),StandardCharsets.UTF_8);System.out.println(json);
        }
    }
}
