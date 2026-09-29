package it.nuovoordine.market;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import java.util.*;

/** Version adapter verified against the pack's Xaero Minimap 26.5.0 API. */
final class XaeroMeetings {
    record Mark(Object set,Object waypoint){}
    static final Map<String,JsonObject> meetings=new HashMap<>();
    static final Map<String,Mark> active=new HashMap<>();
    static final Set<String> enabled=new HashSet<>();
    static Object call(Object o,String n)throws Exception{return o.getClass().getMethod(n).invoke(o);}
    static void sync(JsonArray list,long serverNow){
        meetings.clear();long local=System.currentTimeMillis();
        for(var e:list){var m=e.getAsJsonObject().deepCopy();m.addProperty("localExpires",local+Math.max(0,m.get("expires").getAsLong()-serverNow));meetings.put(m.get("id").getAsString(),m);}
        enabled.retainAll(meetings.keySet());tick();
    }
    static String enable(String id){if(!meetings.containsKey(id))return "Appuntamento scaduto o non accettato";try{Class.forName("xaero.common.XaeroMinimapSession");}catch(Exception e){return "Installa Xaero Minimap insieme a World Map";}enabled.add(id);tick();return "Waypoint attivato nella dimensione dell'appuntamento";}
    static void remove(String id){Mark m=active.remove(id);if(m!=null)try{m.set.getClass().getMethod("remove",Class.forName("xaero.common.minimap.waypoints.Waypoint")).invoke(m.set,m.waypoint);}catch(Exception ignored){}}
    static void tick(){
        var mc=Minecraft.getInstance();String dimension=mc.level==null?"":mc.level.dimension().location().toString();
        for(String id:new HashSet<>(active.keySet())){var m=meetings.get(id);if(m==null||!enabled.contains(id)||m.get("localExpires").getAsLong()<=System.currentTimeMillis()||!dimension.equals(m.get("dimension").getAsString()))remove(id);}
        for(String id:new HashSet<>(enabled)){
            var m=meetings.get(id);if(m==null||m.get("localExpires").getAsLong()<=System.currentTimeMillis()){enabled.remove(id);continue;}
            if(active.containsKey(id)||!dimension.equals(m.get("dimension").getAsString()))continue;
            try{
                Object session=Class.forName("xaero.common.XaeroMinimapSession").getMethod("getCurrentSession").invoke(null);if(session==null)continue;
                Object manager=call(session,"getWaypointsManager");Object world=call(call(manager,"getWorldManager"),"getAutoWorld");if(world==null)continue;
                Object set=call(world,"getCurrentWaypointSet");Class<?> w=Class.forName("xaero.common.minimap.waypoints.Waypoint");
                Object point=w.getConstructor(int.class,int.class,int.class,String.class,String.class,int.class).newInstance(m.get("x").getAsInt(),m.get("y").getAsInt(),m.get("z").getAsInt(),"Scambio: "+m.get("title").getAsString(),"$",6);
                w.getMethod("setTemporary",boolean.class).invoke(point,true);set.getClass().getMethod("add",w).invoke(set,point);active.put(id,new Mark(set,point));
            }catch(Exception e){enabled.remove(id);BrowserBridge.reportError("Waypoint Xaero non disponibile: "+e.getClass().getSimpleName());}
        }
    }
    static void clear(){for(String id:new HashSet<>(active.keySet()))remove(id);meetings.clear();enabled.clear();}
}
