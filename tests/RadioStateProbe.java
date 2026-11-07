import com.caf.lccustomcity.radio.*;
import com.caf.lccustomcity.impl.CityRadioSites.Site;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import java.util.UUID;

public class RadioStateProbe {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception {
        String[] kinds={"city1","city2","city3","city4","medical","military","camp","police","industrial","residential"};
        for(String kind:kinds) {
            boolean city=kind.startsWith("city");int tier=city?Integer.parseInt(kind.substring(4)):2;
            Site site=new Site("minecraft:overworld/"+(city?"city/":"building/")+kind+"/-320/480","真实测试地点",city?"city":kind,tier,-320,480,city);
            var m=RadioStories.message(site,1800);
            check(m.x==-320&&m.z==480&&m.receivedAt==1800,"coordinates are source values");
            check(m.body.contains("X=-320，Z=480")&&!m.body.contains("{coordinates}")&&!m.body.contains("{name}"),"placeholders resolved");
            check(m.body.contains("重复录音")&&m.body.contains("录制时间不明"),"recording not live rescue promise");
            check(m.id.equals(site.id()),"stable instance dedupe id");
        }
        RadioData original=new RadioData();UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        var a=original.listener(first);a.enabled=false;a.scanReady=9800;a.nextReception=17000;a.reachedTier=2;a.failedScans=2;
        a.heard.add("previous-record-outside-history");
        var message=RadioStories.message(new Site("minecraft:overworld/city/test/1/2","乡村","city",1,1,2,true),1234);
        message.visited=true;a.history.add(message);a.heard.add(message.id);
        var saved=original.save(new CompoundTag(),null);
        var method=RadioData.class.getDeclaredMethod("load",CompoundTag.class,HolderLookup.Provider.class);method.setAccessible(true);
        RadioData loaded=(RadioData)method.invoke(null,saved,null);var copy=loaded.listener(first);
        check(!copy.enabled&&copy.scanReady==9800&&copy.nextReception==17000,"restart preserves opt out and cadence");
        check(copy.reachedTier==2,"sequential exploration persists through restart");
        check(copy.failedScans==2,"scan recognition aid survives restart");
        check(copy.history.size()==1&&copy.history.getFirst().visited,"history and visited survive restart");
        check(copy.history.getFirst().body.equals(message.body),"recording body survives restart exactly");
        check(copy.heard.contains("previous-record-outside-history"),"trimmed history does not allow repeat broadcast");
        check(loaded.listener(second).history.isEmpty()&&loaded.listener(second).enabled,"different player isolated");
        check(new RadioData().listener(first).history.isEmpty(),"different world data isolated");
        for(int reached=0;reached<=4;reached++) {
            for(int tier=1;tier<=4;tier++) {
                int cap=reached==0?2:reached<3?3:4;
                check(RadioReceptionRules.canHear(reached,tier)==(tier<=cap),"T1T2 share a band and T4 requires T3");
                check(RadioReceptionRules.arrive(reached,tier,false)==reached,"building signal never advances city sequence");
                check(RadioReceptionRules.arrive(reached,tier,true)==(tier<=cap?Math.max(reached,tier):reached),"arrival respects exploration bands");
            }
        }
        int valid=0;for(int roll=0;roll<100;roll++)if(RadioReceptionRules.scanSucceeds(roll))valid++;
        check(valid==60,"both scan success and failure branches reachable");
        for(int roll=0;roll<100;roll++)check(RadioReceptionRules.scanSucceeds(roll,2),"two failed attempts do not create an endless retry loop");
        check(RadioReceptionRules.automaticDelay(0)==36000&&RadioReceptionRules.automaticDelay(18000)==54000,"passive signal is30to45minutes");
        RadioData longRun=new RadioData();var journal=longRun.listener(first);journal.reachedTier=2;
        var destination=RadioStories.message(new Site("minecraft:overworld/city/小城/3000/4000","小城","city",3,3000,4000,true),1);
        check(journal.record(destination),"new city recording accepted");
        int regionalTurns=0;
        for(int i=0;i<360;i++) {
            if(i>=128&&RadioReceptionRules.regionalTurn(journal.receivedCount)) regionalTurns++;
            check(journal.record(RadioStories.message(new Site("minecraft:overworld/building/home/"+i,"民居","residential",1,i,0,false),i+2)),"new local recording accepted");
        }
        check(journal.history.size()==128,"history remains bounded after360localstories");
        check(journal.history.stream().anyMatch(m->m.id.equals(destination.id)),"unvisited city destination survives FIFO pressure");
        check(journal.receivedCount==361&&regionalTurns>60,"regional schedule continues beyond128and256records");
        check(!journal.record(destination)&&journal.receivedCount==361,"duplicate does not change schedule");
        RadioData restored=(RadioData)method.invoke(null,longRun.save(new CompoundTag(),null),null);
        var restoredJournal=restored.listener(first);
        check(restoredJournal.receivedCount==361&&restoredJournal.history.stream().anyMatch(m->m.id.equals(destination.id)),"destination and lifetime counter survive restart");
        restoredJournal.reachedTier=RadioReceptionRules.arrive(restoredJournal.reachedTier,destination.tier,true);
        check(RadioReceptionRules.canHear(restoredJournal.reachedTier,4),"retainedT3destination can still unlockT4afterlongplay");
        System.out.println("PASS native story and NBT persistence checks="+checks);
    }
}
