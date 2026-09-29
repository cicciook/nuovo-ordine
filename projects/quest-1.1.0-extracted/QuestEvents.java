package com.nuovoordine.quests;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Server authoritative quest progress hooks.
 *
 * Default 1.1 quests focus on Lootr scavenging and PvP. The legacy mob-kill
 * and active-time hooks are intentionally retained so existing/custom configs
 * keep working after the update.
 */
@Mod.EventBusSubscriber(modid="noquests",bus=Mod.EventBusSubscriber.Bus.FORGE)
public final class QuestEvents {
 private record Position(double x,double z){}
 private static final class LootSeen {
  String dailyKey="",weeklyKey="";
  final Set<String> daily=new HashSet<>(),weekly=new HashSet<>();
 }
 private static final Map<String,Position> positions=new HashMap<>();
 private static final Map<String,Integer> ticks=new HashMap<>();
 private static final Map<String,LootSeen> lootSeen=new HashMap<>();
 private static final Map<String,Double> pvpDamageLife=new HashMap<>();
 private static final Map<String,Long> pvpKillCooldown=new HashMap<>();
 private static int flushTicks;
 private static long lastError;
 private static final long SAME_VICTIM_KILL_COOLDOWN_MS=10L*60L*1000L;

 static void error(Exception e){if(System.nanoTime()-lastError>30_000_000_000L){lastError=System.nanoTime();System.getLogger("NoQuests").log(System.Logger.Level.ERROR,"Errore progressi quest",e);}}
 static boolean player(Object p)throws Exception{return p!=null&&Class.forName("net.minecraft.server.level.ServerPlayer").isInstance(p);}
 static Object bukkit(Object p)throws Exception{return ShopRuntime.call(p,"getBukkitEntity",new Class<?>[0]);}
 static String id(Object p)throws Exception{return ShopRuntime.call(bukkit(p),"getUniqueId",new Class<?>[0]).toString();}
 static boolean survival(Object p)throws Exception {
  String mode=ShopRuntime.call(bukkit(p),"getGameMode",new Class<?>[0]).toString();return mode.equals("SURVIVAL")||mode.equals("ADVENTURE");
 }
 static Object sourceEntity(Object source)throws Exception {
  if(source==null)return null;
  Object entity=null;
  try{entity=ShopRuntime.method(source.getClass(),"m_7639_","getEntity").invoke(source);}catch(Exception ignored){}
  if(player(entity))return entity;
  if(entity!=null){
   try{Object owner=ShopRuntime.method(entity.getClass(),"m_37282_","getOwner").invoke(entity);if(player(owner))return owner;}catch(Exception ignored){}
   try{Object owner=entity.getClass().getMethod("getShooter").invoke(entity);if(player(owner))return owner;}catch(Exception ignored){}
  }
  try{
   Object direct=ShopRuntime.method(source.getClass(),"m_7640_","getDirectEntity").invoke(source);
   if(player(direct))return direct;
   if(direct!=null)try{Object owner=ShopRuntime.method(direct.getClass(),"m_37282_","getOwner").invoke(direct);if(player(owner))return owner;}catch(Exception ignored){}
  }catch(Exception ignored){}
  return entity;
 }
 static String pair(String a,String b){return a+"|"+b;}
 static void clearVictimLife(String victim){pvpDamageLife.keySet().removeIf(k->k.endsWith("|"+victim));}

 @SubscribeEvent public static void death(LivingDeathEvent event){
  try{
   Object victim=event.getClass().getMethod("getEntity").invoke(event);
   Object source=event.getClass().getMethod("getSource").invoke(event);
   Object killer=sourceEntity(source);
   if(player(victim)){
    String victimId=id(victim);clearVictimLife(victimId);
    if(!player(killer)||!survival(killer)||!survival(victim))return;
    String killerId=id(killer);if(killerId.equals(victimId))return;
    String pair=pair(killerId,victimId);long now=System.currentTimeMillis();long last=pvpKillCooldown.getOrDefault(pair,0L);
    if(now-last>=SAME_VICTIM_KILL_COOLDOWN_MS){
     pvpKillCooldown.put(pair,now);
     ShopRuntime.get().progress(killerId,"pvp:kill",1);
    }
    return;
   }
   // Backwards compatibility for custom PvE quest definitions.
   if(!player(killer)||!survival(killer))return;
   String killerId=id(killer);Object type=ShopRuntime.method(victim.getClass(),"m_6095_","getType").invoke(victim);
   Object reg=Class.forName("net.minecraftforge.registries.ForgeRegistries").getField("ENTITY_TYPES").get(null);
   Object key=Class.forName("net.minecraftforge.registries.IForgeRegistry").getMethod("getKey",Object.class).invoke(reg,type);
   QuestService s=ShopRuntime.get();s.progress(killerId,"kill:"+key,1);
   if(Class.forName("net.minecraft.world.entity.monster.Enemy").isInstance(victim))s.progress(killerId,"kill:hostile",1);
  }catch(Exception e){error(e);}
 }

 @SubscribeEvent public static void hurt(LivingHurtEvent event){
  try{
   Object victim=event.getClass().getMethod("getEntity").invoke(event);if(!player(victim)||!survival(victim))return;
   Object source=event.getClass().getMethod("getSource").invoke(event);Object killer=sourceEntity(source);
   if(!player(killer)||!survival(killer))return;
   String attacker=id(killer),victimId=id(victim);if(attacker.equals(victimId))return;
   Object amountObject=event.getClass().getMethod("getAmount").invoke(event);double amount=((Number)amountObject).doubleValue();if(!Double.isFinite(amount)||amount<=0)return;
   String key=pair(attacker,victimId);double before=pvpDamageLife.getOrDefault(key,0d);double after=Math.min(20d,before+amount);pvpDamageLife.put(key,after);
   int credit=(int)Math.floor(after)-(int)Math.floor(before);
   if(credit>0)ShopRuntime.get().progress(attacker,"pvp:damage",credit);
  }catch(Exception e){error(e);}
 }

 static LootSeen tracking(String playerId,QuestService service)throws Exception {
  LootSeen s=lootSeen.get(playerId);if(s!=null)return normalize(s,service);
  s=new LootSeen();Path file=trackingFile(playerId,service);
  if(Files.exists(file))for(String line:Files.readAllLines(file,StandardCharsets.UTF_8)){
   int tab=line.indexOf('\t');if(tab<0)continue;String kind=line.substring(0,tab),value=line.substring(tab+1);
   try{value=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8);}catch(IllegalArgumentException bad){continue;}
   switch(kind){case "DK"->s.dailyKey=value;case "WK"->s.weeklyKey=value;case "D"->s.daily.add(value);case "W"->s.weekly.add(value);default->{}}
  }
  lootSeen.put(playerId,s);return normalize(s,service);
 }
 static LootSeen normalize(LootSeen s,QuestService service){
  String dk=service.period(false),wk=service.period(true);
  if(!dk.equals(s.dailyKey)){s.dailyKey=dk;s.daily.clear();}
  if(!wk.equals(s.weeklyKey)){s.weeklyKey=wk;s.weekly.clear();}
  return s;
 }
 static Path trackingFile(String playerId,QuestService service)throws Exception {
  Path dir=service.root.resolve("loot-tracking");Files.createDirectories(dir);return dir.resolve(playerId+".txt");
 }
 static String enc(String s){return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));}
 static void saveTracking(String playerId,LootSeen s,QuestService service)throws Exception {
  StringBuilder out=new StringBuilder();out.append("DK\t").append(enc(s.dailyKey)).append('\n').append("WK\t").append(enc(s.weeklyKey)).append('\n');
  for(String x:new TreeSet<>(s.daily))out.append("D\t").append(enc(x)).append('\n');
  for(String x:new TreeSet<>(s.weekly))out.append("W\t").append(enc(x)).append('\n');
  QuestService.atomic(trackingFile(playerId,service),out.toString());
 }
 static boolean isLootrBlock(Object level,Object pos)throws Exception {
  Class<?> blockPos=Class.forName("net.minecraft.core.BlockPos");
  Object state=ShopRuntime.method(level.getClass(),"m_8055_","getBlockState",blockPos).invoke(level,pos);
  Object block=ShopRuntime.method(state.getClass(),"m_60734_","getBlock").invoke(state);
  Object reg=Class.forName("net.minecraftforge.registries.ForgeRegistries").getField("BLOCKS").get(null);
  Object key=Class.forName("net.minecraftforge.registries.IForgeRegistry").getMethod("getKey",Object.class).invoke(reg,block);
  return key!=null&&key.toString().startsWith("lootr:");
 }
 static String lootToken(Object player,Object pos)throws Exception {
  Object world=ShopRuntime.call(bukkit(player),"getWorld",new Class<?>[0]);Object uid=ShopRuntime.call(world,"getUID",new Class<?>[0]);
  return uid+"|"+pos;
 }
 @SubscribeEvent public static void loot(PlayerInteractEvent.RightClickBlock event){
  try{
   Object p=event.getClass().getMethod("getEntity").invoke(event);if(!player(p)||!survival(p))return;
   Object level=event.getClass().getMethod("getLevel").invoke(event);Object pos=event.getClass().getMethod("getPos").invoke(event);
   if(!isLootrBlock(level,pos))return;
   String playerId=id(p),token=lootToken(p,pos);QuestService service=ShopRuntime.get();LootSeen seen=tracking(playerId,service);
   boolean daily=seen.daily.add(token),weekly=seen.weekly.add(token);if(!daily&&!weekly)return;
   saveTracking(playerId,seen,service);
   if(daily)service.progress(playerId,"loot:lootr_daily",1);
   if(weekly)service.progress(playerId,"loot:lootr_weekly",1);
  }catch(Exception e){error(e);}
 }

 @SubscribeEvent public static void tick(TickEvent.PlayerTickEvent event){
  if(event.phase!=TickEvent.Phase.END)return;
  try{
   Object p=event.getClass().getField("player").get(event);if(!player(p))return;
   String id=id(p);int n=ticks.getOrDefault(id,0)+1;ticks.put(id,n);if(n%20!=0)return;
   Object b=bukkit(p);Object loc=ShopRuntime.call(b,"getLocation",new Class<?>[0]);
   double x=(double)ShopRuntime.call(loc,"getX",new Class<?>[0]),z=(double)ShopRuntime.call(loc,"getZ",new Class<?>[0]);
   Position old=positions.put(id,new Position(x,z));boolean dead=(boolean)ShopRuntime.call(b,"isDead",new Class<?>[0]);
   if(old!=null&&!dead&&survival(p)){
    double d=(x-old.x)*(x-old.x)+(z-old.z)*(z-old.z);if(d>=0.01&&d<=400)ShopRuntime.get().progress(id,"active_seconds",1);
   }
  }catch(Exception e){error(e);}
 }
 @SubscribeEvent public static void serverTick(TickEvent.ServerTickEvent event){
  if(event.phase!=TickEvent.Phase.END)return;
  if(++flushTicks%200==0){
   ShopRuntime.flush();long cutoff=System.currentTimeMillis()-SAME_VICTIM_KILL_COOLDOWN_MS*2;pvpKillCooldown.entrySet().removeIf(e->e.getValue()<cutoff);
  }
 }
 @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){
  try{Object p=event.getClass().getMethod("getEntity").invoke(event);if(player(p)){String id=id(p);ShopRuntime.get().release(id);positions.remove(id);ticks.remove(id);lootSeen.remove(id);pvpDamageLife.keySet().removeIf(k->k.startsWith(id+"|")||k.endsWith("|"+id));}}catch(Exception e){error(e);}
 }
 @SubscribeEvent public static void stopping(ServerStoppingEvent event){ShopRuntime.flush();positions.clear();ticks.clear();lootSeen.clear();pvpDamageLife.clear();pvpKillCooldown.clear();flushTicks=0;}
}
