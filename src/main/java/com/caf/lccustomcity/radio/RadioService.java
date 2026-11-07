package com.caf.lccustomcity.radio;

import com.caf.lccustomcity.impl.CityRadioSites;
import com.caf.lccustomcity.impl.CityRadioSites.Site;
import com.google.gson.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

public final class RadioService {
    private static final Gson JSON = new Gson();
    private record Access(BlockPos position) {}
    private static final class Session {
        final Map<UUID,Access> stations=new HashMap<>();
        final Map<UUID,Long> requests=new HashMap<>();
        final Set<UUID> pending=new HashSet<>();
    }
    private static final Map<MinecraftServer,Session> SESSIONS=new WeakHashMap<>();
    private RadioService() {}
    public static void register(IEventBus bus) {
        bus.addListener(RadioNetwork::register);
        CityRadioSites.register();
        NeoForge.EVENT_BUS.addListener(RadioService::tick);
        NeoForge.EVENT_BUS.addListener(RadioService::blockUse);
        NeoForge.EVENT_BUS.addListener(RadioService::itemUse);
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class,e->SESSIONS.remove(e.getServer()));
    }
    private static Session session(ServerPlayer player) {return SESSIONS.computeIfAbsent(player.getServer(),ignored->new Session());}
    private static long time(ServerPlayer player) {return player.getServer().overworld().getGameTime();}
    private static String itemId(net.minecraft.world.item.ItemStack item) {return BuiltInRegistries.ITEM.getKey(item.getItem()).toString();}
    private static boolean handheld(ServerPlayer player) {
        if(ModList.get().isLoaded("mcphone") && com.november.mcphone.core.PhoneItem.isCarriedBy(player)) return true;
        for(var stack:player.getInventory().items) if(itemId(stack).equals("zombiekit:pocket_radio")) return true;
        return itemId(player.getOffhandItem()).equals("zombiekit:pocket_radio");
    }
    private static boolean receiver(ServerPlayer player) {
        if(handheld(player)) return true;
        Access access=session(player).stations.get(player.getUUID());
        return access!=null && player.blockPosition().distSqr(access.position)<=64
                && player.serverLevel().hasChunkAt(access.position)
                && BuiltInRegistries.BLOCK.getKey(player.serverLevel().getBlockState(access.position).getBlock()).toString().equals("doomsday_decoration:radio");
    }
    private static boolean eligible(ServerPlayer player) {return !player.isSpectator()&&player.serverLevel().dimension().equals(Level.OVERWORLD);}
    private static void blockUse(PlayerInteractEvent.RightClickBlock event) {
        if(event.getHand()!=InteractionHand.MAIN_HAND) return;
        if(!BuiltInRegistries.BLOCK.getKey(event.getLevel().getBlockState(event.getPos()).getBlock()).toString().equals("doomsday_decoration:radio")) return;
        if(event.getEntity().isShiftKeyDown()) return;
        event.setCanceled(true); event.setCancellationResult(InteractionResult.SUCCESS);
        if(event.getEntity() instanceof ServerPlayer player && eligible(player)) {
            session(player).stations.put(player.getUUID(),new Access(event.getPos().immutable()));
            send(player,"桌面收音机已接通。可扫描频道，收听记录与手机共用。",true);
        }
    }
    private static void itemUse(PlayerInteractEvent.RightClickItem event) {
        if(!itemId(event.getItemStack()).equals("zombiekit:pocket_radio")) return;
        event.setCanceled(true); event.setCancellationResult(InteractionResult.SUCCESS);
        if(event.getEntity() instanceof ServerPlayer player && eligible(player)) send(player,"手摇无线电已接通。",true);
    }
    public static void request(ServerPlayer player,String action,String id) {
        long now=time(player); Session session=session(player);
        long last=session.requests.getOrDefault(player.getUUID(),Long.MIN_VALUE/2);
        if(now-last<4) return;
        session.requests.put(player.getUUID(),now);
        if(!eligible(player)) {send(player,"请在主世界正常活动时收听。",false);return;}
        if(!receiver(player)) {send(player,"请携带手机或手摇无线电，或在桌面收音机旁收听。",false);return;}
        RadioData data=RadioData.get(player.getServer()); RadioData.Listener listener=data.listener(player.getUUID());
        switch(action) {
            case "toggle" -> {listener.enabled=!listener.enabled;data.setDirty();send(player,listener.enabled?"自动收听已开启。":"自动收听已关闭，仍可手动扫描。",false);}
            case "scan" -> {
                if(now<listener.scanReady) {send(player,"正在等待下一个播送间隙，约"+((listener.scanReady-now+19)/20)+"秒后可再扫描。",false);return;}
                if(session.pending.contains(player.getUUID())) {send(player,"正在辨认远方的信号……",false);return;}
                if(!RadioReceptionRules.scanSucceeds(player.getRandom().nextInt(100),listener.failedScans)) {
                    listener.failedScans++;
                    listener.scanReady=now+400;data.setDirty();
                    radioSound(player,"radio_static");
                    send(player,listener.failedScans>=2?"频率逐渐稳定。20秒后再试一次。":"只有底噪和听不清的断句。20秒后可重新搜台。",false);return;
                }
                listener.failedScans=0;listener.scanReady=now+1200;data.setDirty(); receive(player,true);
            }
            case "sync" -> send(player,"",false);
            default -> { }
        }
    }
    private static void tick(PlayerTickEvent.Post event) {
        if(!(event.getEntity() instanceof ServerPlayer player)||player.tickCount%100!=0||!eligible(player)) return;
        RadioData data=RadioData.get(player.getServer()); RadioData.Listener listener=data.listener(player.getUUID());
        boolean changed=false;
        for(var message:listener.history) {
            if(distance(player,message.x,message.z)<=96L*96) {
                if(!message.visited) {message.visited=true;changed=true;}
                int reached=RadioReceptionRules.arrive(listener.reachedTier,message.tier,message.id.contains("/city/"));
                if(reached!=listener.reachedTier) {listener.reachedTier=reached;changed=true;}
            }
        }
        if(changed) data.setDirty();
        if(!listener.enabled||!receiver(player)) return;
        long now=time(player);
        if(listener.nextReception==0) {listener.nextReception=now+RadioReceptionRules.automaticDelay(player.getRandom().nextInt(18001));data.setDirty();return;}
        if(now>=listener.nextReception&&!session(player).pending.contains(player.getUUID())) receive(player,false);
    }
    private static long distance(ServerPlayer p,int x,int z) {long dx=(long)p.getBlockX()-x,dz=(long)p.getBlockZ()-z;return dx*dx+dz*dz;}
    private static void receive(ServerPlayer player,boolean manual) {
        var data=RadioData.get(player.getServer());var listener=data.listener(player.getUUID());
        listener.nextReception=time(player)+RadioReceptionRules.automaticDelay(player.getRandom().nextInt(18001));data.setDirty();
        List<Site> nearby=nearby(player,listener);
        // Interleave local stories with regional relay calls so exploration can lead out of the current city.
        if(!nearby.isEmpty()&&!RadioReceptionRules.regionalTurn(listener.receivedCount)) {deliver(player,nearby.getFirst());return;}
        Session session=session(player); UUID uuid=player.getUUID();
        if(!session.pending.add(uuid)) return;
        if(manual) send(player,"正在辨认远方的信号……",false);
        var server=player.getServer();var level=player.serverLevel();
        CityRadioSites.requestCities(level,cities->{
            if(SESSIONS.get(server)!=session) return;
            session.pending.remove(uuid);
            ServerPlayer current=server.getPlayerList().getPlayer(uuid);
            if(current==null||current.serverLevel()!=level||!eligible(current)||!receiver(current)) return;
            var history=RadioData.get(server).listener(uuid);
            Set<Integer> knownTiers=new HashSet<>();history.history.stream().filter(m->m.id.contains("/city/")).forEach(m->knownTiers.add(m.tier));
            Site selected=cities.stream().filter(s->!history.heard.contains(s.id())&&RadioReceptionRules.canHear(history.reachedTier,s.tier()))
                    .min(Comparator.comparingInt((Site s)->knownTiers.contains(s.tier())?1:0).thenComparingInt(Site::tier)
                            .thenComparingLong(s->distance(current,s.x(),s.z())).thenComparing(Site::id)).orElse(null);
            List<Site> refreshed=nearby(current,history);
            if(selected==null && !refreshed.isEmpty()) selected=refreshed.getFirst();
            if(selected!=null) deliver(current,selected);
            else if(manual) send(current,history.reachedTier<3?"暂时没有新的清晰信号。可先寻访“找城市”中已有的联络区。":"暂时只有重复录音。可先寻访已有地点，或换个位置收听。",false);
        });
    }
    private static List<Site> nearby(ServerPlayer player,RadioData.Listener listener) {
        return CityRadioSites.cachedSites(player.serverLevel()).stream()
                .filter(s->RadioReceptionRules.canHear(listener.reachedTier,s.tier())&&!listener.heard.contains(s.id())&&distance(player,s.x(),s.z())<=2000L*2000)
                .sorted(Comparator.comparingInt((Site s)->s.kind().equals("residential")?1:0)
                        .thenComparingLong(s->distance(player,s.x(),s.z())).thenComparing(Site::id)).toList();
    }
    private static void deliver(ServerPlayer player,Site site) {
        var data=RadioData.get(player.getServer());var listener=data.listener(player.getUUID());
        var message=RadioStories.message(site,time(player));
        if(!listener.record(message)) return;
        data.setDirty();
        player.displayClientMessage(Component.literal("[无线电] 收到一段"+message.channel+"录音，打开无线电查看。"),false);
        radioSound(player,"radio_response");
        send(player,"收到新录音："+message.title,false);
    }
    private static void radioSound(ServerPlayer player,String name) {
        var sound=BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.fromNamespaceAndPath("zombiekit",name));
        if(sound!=null) player.playNotifySound(sound,SoundSource.PLAYERS,0.25F,1.0F);
    }
    private static void send(ServerPlayer player,String status,boolean open) {
        var listener=RadioData.get(player.getServer()).listener(player.getUUID());
        JsonObject root=new JsonObject();root.addProperty("enabled",listener.enabled);root.addProperty("status",status);
        root.addProperty("scanRemaining",Math.max(0,(listener.scanReady-time(player)+19)/20));
        root.addProperty("failureCount",listener.failedScans);
        int cap=RadioReceptionRules.maximumSignalTier(listener.reachedTier);
        root.addProperty("band",cap>=4?"乡村／小镇／小城／大城":cap>=3?"乡村／小镇／小城":"乡村／小镇");
        JsonArray records=new JsonArray();
        for(int i=listener.history.size()-1;i>=0;i--) records.add(JSON.toJsonTree(listener.history.get(i)));
        root.add("records",records);RadioNetwork.send(player,JSON.toJson(root),open);
    }
}
