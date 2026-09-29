package it.nuovoordine.pvp;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.*;
import net.minecraftforge.fml.*;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import java.util.function.Consumer;

@Mod("nopvp")
public class PvpMod {
    static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.DoubleValue SCALE,CAP;
    static final ForgeConfigSpec.IntValue WINDOW;
    static final ForgeConfigSpec.ConfigValue<List<? extends String>> SNIPERS;
    static {var b=new ForgeConfigSpec.Builder();SCALE=b.comment("Moltiplicatore danno finale contro giocatori").defineInRange("damageMultiplier",0.55,0.01,1.0);CAP=b.comment("Massimo danno per raffica, frazione della salute massima. Non rende immortali i feriti.").defineInRange("maxBurstHealthFraction",0.60,0.05,0.95);WINDOW=b.comment("Raggruppa pellet e danno perforante in questa finestra di tick").defineInRange("burstWindowTicks",2,1,10);SNIPERS=b.comment("Eccezioni cecchini Superb/altre mod. TACZ legge automaticamente il tipo sniper dal gunpack.").defineListAllowEmpty("sniperIds",List.of("superbwarfare:awm","superbwarfare:m98b","superbwarfare:ntw_20","superbwarfare:svd"),x->x instanceof String);SPEC=b.build();}
    record Hit(UUID bullet,UUID target,long tick,boolean sniperHead){}
    static final Map<String,Hit> hits=new HashMap<>();static final DamageBudget budget=new DamageBudget();
    public PvpMod(){ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON,SPEC);MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST,this::damage);MinecraftForge.EVENT_BUS.addListener(this::stop);
        hook("com.tacz.guns.api.event.common.EntityHurtByGunEvent$Pre",this::tacz);
        hook("com.atsuishio.superbwarfare.api.event.ProjectileHitEvent$HitEntity",this::superb);
    }
    @SuppressWarnings({"unchecked","rawtypes"}) static void hook(String name,Consumer<Object> consumer){try{Class cls=Class.forName(name);MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,cls,(Consumer<Event>)consumer::accept);}catch(ClassNotFoundException ignored){}catch(LinkageError ex){System.getLogger("nopvp").log(System.Logger.Level.WARNING,"Adapter non disponibile: "+name,ex);}}
    static Object get(Object e,String name)throws Exception{return e.getClass().getMethod(name).invoke(e);}
    static boolean taczSniper(Object id)throws Exception{Class<?> api=Class.forName("com.tacz.guns.api.TimelessAPI");Optional<?> gun=(Optional<?>)api.getMethod("getCommonGunIndex",ResourceLocation.class).invoke(null,id);return gun.isPresent()&&"sniper".equalsIgnoreCase(String.valueOf(get(gun.get(),"getType")));}
    void remember(Entity bullet,Entity target,boolean exception){if(!(target instanceof Player)||target.level().isClientSide)return;long tick=target.level().getGameTime();hits.put(bullet.getUUID()+":"+target.getUUID(),new Hit(bullet.getUUID(),target.getUUID(),tick,exception));if(hits.size()>2048)hits.entrySet().removeIf(e->tick-e.getValue().tick>2);}
    void tacz(Object e){try{Entity bullet=(Entity)get(e,"getBullet"),target=(Entity)get(e,"getHurtEntity");Object id=get(e,"getGunId");boolean head=(boolean)get(e,"isHeadShot");remember(bullet,target,head&&(taczSniper(id)||SNIPERS.get().contains(id.toString())));}catch(Exception ex){System.getLogger("nopvp").log(System.Logger.Level.DEBUG,"TACZ hit senza eccezione",ex);}}
    void superb(Object e){try{Entity bullet=(Entity)get(e,"getProjectile"),target=(Entity)get(e,"getTarget");String id=String.valueOf(get(bullet,"getGunItemId"));remember(bullet,target,(boolean)get(e,"isHeadshot")&&SNIPERS.get().contains(id));}catch(Exception ex){System.getLogger("nopvp").log(System.Logger.Level.DEBUG,"Superb hit senza eccezione",ex);}}
    void damage(LivingDamageEvent e){
        if(!(e.getEntity() instanceof Player victim)||victim.level().isClientSide)return;
        Entity direct=e.getSource().getDirectEntity(),attacker=e.getSource().getEntity();
        String type=e.getSource().typeHolder().unwrapKey().map(k->k.location().getNamespace()).orElse("");
        boolean weapon=attacker instanceof LivingEntity||type.equals("tacz")||type.equals("superbwarfare");if(!weapon)return;
        long tick=victim.level().getGameTime();Hit h=direct==null?null:hits.get(direct.getUUID()+":"+victim.getUUID());
        if(h!=null&&h.tick==tick&&h.sniperHead)return;
        String key=victim.getUUID()+":"+(attacker==null?"environment-weapon":attacker.getUUID());
        e.setAmount(budget.apply(key,tick,e.getAmount(),victim.getMaxHealth(),SCALE.get().floatValue(),CAP.get().floatValue(),WINDOW.get()));
    }
    void stop(ServerStoppedEvent e){hits.clear();budget.clear();}
}
