package com.caf.lccustomcity.radio;

import com.caf.lccustomcity.impl.CityRadioSites.Site;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Story templates describe old recordings, never promise an unimplemented rescue event. */
public final class RadioStories {
    private record Story(String channel,String title,String text) {}
    private static final Map<String,List<Story>> STORIES = load();
    private RadioStories() {}
    private static Map<String,List<Story>> load() {
        try(var input=RadioStories.class.getResourceAsStream("/caf_radio_stories.json")) {
            if(input==null) throw new IllegalStateException("Missing radio story resource");
            JsonObject root=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String,List<Story>> result=new HashMap<>();
            root.entrySet().forEach(group->{
                List<Story> entries=new ArrayList<>();
                for(JsonElement element:group.getValue().getAsJsonArray()) {
                    JsonObject s=element.getAsJsonObject();
                    entries.add(new Story(s.get("channel").getAsString(),s.get("title").getAsString(),s.get("text").getAsString()));
                }
                result.put(group.getKey(),List.copyOf(entries));
            });
            return Map.copyOf(result);
        } catch(IOException e) {throw new UncheckedIOException(e);}
    }
    public static RadioData.Message message(Site site,long now) {
        String key=site.city()?"city"+site.tier():site.kind();
        List<Story> candidates=STORIES.get(key);
        if(candidates==null||candidates.isEmpty()) throw new IllegalArgumentException("No radio stories for "+key);
        Story story=candidates.get(Math.floorMod(site.id().hashCode(),candidates.size()));
        String coordinates="X="+site.x()+"，Z="+site.z();
        String text=story.text.replace("{name}",site.name()).replace("{coordinates}",coordinates);
        text+="\n\n[重复录音 · 录制时间不明] 这是旧广播中的联络位置，现场状态需要自行确认。";
        return new RadioData.Message(site.id(),story.channel,story.title+" · "+site.name(),text,site.x(),site.z(),site.tier(),now);
    }
}
