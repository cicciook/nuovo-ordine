package it.nuovoordine.pvp;
import java.util.*;
/** Caps the aggregate final damage per attacker/victim in a short projectile burst. */
public final class DamageBudget {
    private record Window(long tick,float spent){}
    private final Map<String,Window> windows=new HashMap<>();
    public float apply(String key,long tick,float damage,float maximumHealth,float scale,float capFraction,int windowTicks){
        if(!Float.isFinite(damage)||damage<0)return 0;
        Window w=windows.get(key);if(w==null||tick-w.tick>=windowTicks||tick<w.tick)w=new Window(tick,0);
        float allowed=Math.max(0,Math.min(damage*scale,maximumHealth*capFraction-w.spent));windows.put(key,new Window(w.tick,w.spent+allowed));
        if(windows.size()>4096)windows.entrySet().removeIf(e->tick-e.getValue().tick>windowTicks);
        return allowed;
    }
    public void clear(){windows.clear();}
}
