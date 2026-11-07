package com.caf.lccustomcity.radio;

import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-world, per-player reception history; unrelated to the player's editable phone notes. */
public final class RadioData extends SavedData {
    public static final int HISTORY_LIMIT = 128;
    public static final class Listener {
        public boolean enabled = true;
        public long nextReception;
        public long scanReady;
        public int reachedTier;
        public long receivedCount;
        public int failedScans;
        public final List<Message> history = new ArrayList<>();
        public final Set<String> heard = new HashSet<>();
        public boolean record(Message message) {
            if(!heard.add(message.id)) return false;
            receivedCount++;
            history.add(message);
            trimHistory();
            return true;
        }
        private void trimHistory() {
            while(history.size()>HISTORY_LIMIT) {
                int remove=-1;
                for(int i=0;i<history.size();i++) {
                    Message candidate=history.get(i);
                    if(!candidate.id.contains("/city/")||candidate.visited) {remove=i;break;}
                }
                // Normally there are at most10 city calls in this pack. If a custom
                // world has more than128, keep at least one unresolved destination per tier.
                if(remove<0) {
                    for(int i=0;i<history.size()&&remove<0;i++)
                        for(int j=i+1;j<history.size();j++)
                            if(history.get(i).tier==history.get(j).tier) {remove=i;break;}
                }
                if(remove<0) break;
                history.remove(remove);
            }
        }
    }
    public static final class Message {
        public String id, channel, title, body;
        public int x, z, tier;
        public long receivedAt;
        public boolean visited;
        public Message(String id, String channel, String title, String body, int x, int z, int tier, long receivedAt) {
            this.id=id; this.channel=channel; this.title=title; this.body=body;
            this.x=x; this.z=z; this.tier=tier; this.receivedAt=receivedAt;
        }
    }
    private final Map<UUID, Listener> listeners = new HashMap<>();
    public Listener listener(UUID id) { return listeners.computeIfAbsent(id, ignored -> new Listener()); }
    public static RadioData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(RadioData::new, RadioData::load, net.minecraft.util.datafix.DataFixTypes.LEVEL), "caf_radio_history");
    }
    private static RadioData load(CompoundTag tag, HolderLookup.Provider registries) {
        RadioData data = new RadioData();
        for (Tag raw : tag.getList("Listeners", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag)raw;
            if (!row.hasUUID("Player")) continue;
            Listener listener = new Listener(); listener.enabled = row.getBoolean("Enabled");
            listener.nextReception = row.getLong("NextReception");
            listener.scanReady = row.getLong("ScanReady");
            listener.reachedTier = Math.max(0,Math.min(4,row.getInt("ReachedTier")));
            listener.receivedCount = Math.max(0,row.getLong("ReceivedCount"));
            listener.failedScans = Math.max(0,Math.min(2,row.getInt("FailedScans")));
            for (Tag value : row.getList("Heard", Tag.TAG_STRING)) listener.heard.add(value.getAsString());
            for (Tag entry : row.getList("Messages", Tag.TAG_COMPOUND)) {
                CompoundTag m = (CompoundTag)entry;
                Message msg = new Message(m.getString("Id"),m.getString("Channel"),m.getString("Title"),m.getString("Body"),
                        m.getInt("X"),m.getInt("Z"),m.getInt("Tier"),m.getLong("ReceivedAt"));
                msg.visited=m.getBoolean("Visited"); listener.history.add(msg); listener.heard.add(msg.id);
            }
            listener.receivedCount=Math.max(listener.receivedCount,listener.history.size());
            listener.trimHistory();
            data.listeners.put(row.getUUID("Player"), listener);
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag rows = new ListTag();
        listeners.forEach((uuid, listener) -> {
            CompoundTag row = new CompoundTag(); row.putUUID("Player",uuid); row.putBoolean("Enabled",listener.enabled);
            row.putLong("NextReception",listener.nextReception);
            row.putLong("ScanReady",listener.scanReady);
            row.putInt("ReachedTier",listener.reachedTier);
            row.putLong("ReceivedCount",listener.receivedCount);
            row.putInt("FailedScans",listener.failedScans);
            ListTag heard = new ListTag(); listener.heard.stream().sorted().forEach(id -> heard.add(StringTag.valueOf(id))); row.put("Heard",heard);
            ListTag messages = new ListTag();
            for(Message msg:listener.history) {
                CompoundTag m = new CompoundTag(); m.putString("Id",msg.id); m.putString("Channel",msg.channel); m.putString("Title",msg.title); m.putString("Body",msg.body);
                m.putInt("X",msg.x); m.putInt("Z",msg.z); m.putInt("Tier",msg.tier); m.putLong("ReceivedAt",msg.receivedAt); m.putBoolean("Visited",msg.visited); messages.add(m);
            }
            row.put("Messages",messages); rows.add(row);
        });
        tag.putInt("Format",1); tag.put("Listeners",rows); return tag;
    }
}
