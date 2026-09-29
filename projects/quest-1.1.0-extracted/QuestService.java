package com.nuovoordine.quests;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Pure server-side quest engine. No client supplied progress, money or rewards. */
final class QuestService {
 static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
 interface Port {
  String id() throws Exception;
  boolean admin() throws Exception;
  boolean valid(String nbt);
  int[] reserve(List<String> items) throws Exception;
  boolean deposit(long money) throws Exception;
  void deliver(List<String> items,int[] slots) throws Exception;
 }
 record Quest(String id,String title,String event,int target,long money){}
 static final class Period {
  String key; List<Quest> quests=new ArrayList<>(); Map<String,Integer> progress=new HashMap<>();
  Set<String> claimed=new HashSet<>(); boolean bonus; long bonusMoney;
 }
 static final class State {Period daily,weekly; String pending; long pendingMoney; List<String> pendingItems;}
 static final class Reward {String name;List<String> items;}
 static final class Config {
  String timezone="Europe/Rome"; long dailyMoney=2500,weeklyMoney=10000,dailyBonus=10000,weeklyBonus=100000;
  List<Quest> daily=new ArrayList<>(),weekly=new ArrayList<>();List<Reward> weapons=new ArrayList<>(),armor=new ArrayList<>();
 }
 final Path root; final Clock clock; Config config; final Map<String,State> states=new HashMap<>(); final Set<String> dirty=new HashSet<>();
 QuestService(Path root,Clock clock)throws Exception {this.root=root;this.clock=clock;Files.createDirectories(root.resolve("players"));reload();}
 void reload()throws Exception {
  Path p=root.resolve("config.json");
  if(!Files.exists(p)){try(var in=QuestService.class.getResourceAsStream("/default-config.json")){if(in==null)throw new IllegalStateException("Config assente");Files.copy(in,p);}}
  Config next=GSON.fromJson(Files.readString(p),Config.class);validate(next);config=next;
 }
 static void validate(Config c){
  Objects.requireNonNull(c);ZoneId.of(c.timezone);
  for(long n:new long[]{c.dailyMoney,c.weeklyMoney,c.dailyBonus,c.weeklyBonus})if(n<0||n>1000000000L)throw new IllegalArgumentException("Importi non validi");
  for(List<Quest> list:List.of(c.daily,c.weekly)){
   if(list.size()!=3)throw new IllegalArgumentException("Servono esattamente tre quest per periodo");Set<String> ids=new HashSet<>();
   for(Quest q:list)if(q==null||q.id==null||!q.id.matches("[a-z0-9_]{1,40}")||!ids.add(q.id)||q.target<1||q.target>10000000||q.title==null||q.title.length()>180||q.event==null||!q.event.matches("kill:[a-z0-9_:]+|active_seconds|loot:lootr_(daily|weekly)|pvp:(kill|damage)"))throw new IllegalArgumentException("Quest non valida");
  }
  if(c.weapons.stream().anyMatch(r->r==null||r.items==null||r.items.size()!=1)||c.armor.stream().anyMatch(r->r==null||r.items==null||r.items.size()!=4))throw new IllegalArgumentException("Arma: 1 oggetto; set completo: 4 oggetti");
  for(List<Reward> pool:List.of(c.weapons,c.armor))for(Reward r:pool)if(r==null||r.name==null||r.items==null||r.items.isEmpty()||r.items.size()>4||r.items.stream().anyMatch(n->n==null||n.length()>8000))throw new IllegalArgumentException("Premio non valido");
 }
 String period(boolean weekly){LocalDate d=LocalDate.now(clock.withZone(ZoneId.of(config.timezone)));return (weekly?d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)):d).toString();}
 Period fresh(boolean weekly){Period p=new Period();p.key=period(weekly);p.bonusMoney=weekly?config.weeklyBonus:config.dailyBonus;for(Quest q:weekly?config.weekly:config.daily)p.quests.add(new Quest(q.id,q.title,q.event,q.target,weekly?config.weeklyMoney:config.dailyMoney));return p;}
 State state(String id)throws Exception {
  UUID.fromString(id);State s=states.get(id);
  if(s==null){Path p=file(id);s=Files.exists(p)?GSON.fromJson(Files.readString(p),State.class):new State();if(s==null)throw new IllegalStateException("Salvataggio giocatore non valido");states.put(id,s);}
  // Never discard an ambiguous transaction at a reset boundary.
  // 1.1 also replaces still-assigned stock 1.0 PvE periods after the config migration.
  if(s.pending==null){
   if(s.daily==null||!period(false).equals(s.daily.key)||legacyAssigned(s.daily,false)){s.daily=fresh(false);dirty.add(id);}
   if(s.weekly==null||!period(true).equals(s.weekly.key)||legacyAssigned(s.weekly,true)){s.weekly=fresh(true);dirty.add(id);}
  }
  return s;
 }
 static boolean legacyQuestList(List<Quest> list){
  if(list==null||list.size()!=3)return false;Set<String> ids=new HashSet<>();for(Quest q:list)if(q!=null)ids.add(q.id);return ids.equals(Set.of("zombies","hostiles","patrol"));
 }
 boolean legacyAssigned(Period period,boolean weekly){return period!=null&&legacyQuestList(period.quests)&&!legacyQuestList(weekly?config.weekly:config.daily);}

 Path file(String id){return root.resolve("players").resolve(id+".json");}
 static void atomic(Path p,String text)throws Exception {
  Path tmp=p.resolveSibling(p.getFileName()+".tmp");byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
  try(FileChannel c=FileChannel.open(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())c.write(b);c.force(true);}
  try{Files.move(tmp,p,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,p,StandardCopyOption.REPLACE_EXISTING);}
 }
 void save(String id,State s)throws Exception{atomic(file(id),GSON.toJson(s));dirty.remove(id);}
 void flush()throws Exception{for(String id:new ArrayList<>(dirty))save(id,states.get(id));}
 void release(String id)throws Exception{State s=states.get(id);if(s!=null){save(id,s);states.remove(id);}}
 void progress(String id,String event,int count)throws Exception {
  if(count<=0)return;State s=state(id);if(s.pending!=null)return;
  for(Period p:List.of(s.daily,s.weekly))for(Quest q:p.quests)if(q.event.equals(event)){int old=p.progress.getOrDefault(q.id,0);int n=(int)Math.min(q.target,(long)old+count);if(n!=old){p.progress.put(q.id,n);dirty.add(id);}}
 }
 static boolean blocked(String n){String x=n.toLowerCase(Locale.ROOT);return java.util.regex.Pattern.compile("rpg|minigun|m107|m95|ai_awp|ntw_20|m_98b|sentinel|:awm").matcher(x).find();}
 List<Reward> available(List<Reward> pool,Port port,boolean guns){return pool.stream().filter(r->(!guns||r.items.stream().noneMatch(QuestService::blocked))&&r.items.stream().allMatch(port::valid)).toList();}
 List<String> pick(boolean weekly,Port port)throws Exception{
  List<Reward> guns=available(config.weapons,port,true),armor=weekly?available(config.armor,port,false):List.of();
  List<Reward> pool=weekly&&!armor.isEmpty()&&(guns.isEmpty()||java.util.concurrent.ThreadLocalRandom.current().nextBoolean())?armor:guns;
  if(pool.isEmpty())throw new IllegalArgumentException("Nessun premio compatibile disponibile: controllare TACZ / Superb Warfare / Survival Instinct e config.json.");
  return new ArrayList<>(pool.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.size())).items);
 }
 JsonObject handle(JsonObject in,Port port)throws Exception{
  String action=in.has("action")?in.get("action").getAsString():"state";
  if(action.equals("reload")){if(!port.admin())throw new IllegalArgumentException("Riservato agli OP");reload();}
  else if(action.equals("settings")) {
   if(!port.admin())throw new IllegalArgumentException("Riservato agli OP");
   Config next=GSON.fromJson(in.get("config"),Config.class);validate(next);
   atomic(root.resolve("config.json"),GSON.toJson(next));config=next;
  }
  else if(!action.equals("state")&&!action.equals("claim"))throw new IllegalArgumentException("Azione non valida");
  String id=port.id();State s=state(id);
  if(action.equals("claim")){
   if(s.pending!=null)throw new IllegalArgumentException("Pagamento in verifica: contatta un amministratore. Non verra ripetuto automaticamente.");
   String kind=in.get("period").getAsString();if(!kind.equals("daily")&&!kind.equals("weekly"))throw new IllegalArgumentException("Periodo non valido");
   boolean weekly=kind.equals("weekly");Period p=weekly?s.weekly:s.daily;String key=in.get("id").getAsString();long money;List<String> items=List.of();
   if(!in.has("periodKey")||!in.get("periodKey").getAsString().equals(p.key))throw new IllegalArgumentException("Il periodo e cambiato: aggiorna la pagina.");
   if(key.equals("bonus")){
    if(p.bonus)throw new IllegalArgumentException("Bonus gia riscosso");
    if(p.quests.stream().anyMatch(q->p.progress.getOrDefault(q.id,0)<q.target))throw new IllegalArgumentException("Completa tutte e tre le quest");
    money=p.bonusMoney;items=pick(weekly,port);
   }else{Quest q=p.quests.stream().filter(x->x.id.equals(key)).findFirst().orElseThrow(()->new IllegalArgumentException("Quest sconosciuta"));
    if(p.claimed.contains(key))throw new IllegalArgumentException("Premio gia riscosso");if(p.progress.getOrDefault(key,0)<q.target)throw new IllegalArgumentException("Quest incompleta");money=q.money;
   }
   int[] slots=port.reserve(items);s.pending=kind+":"+p.key+":"+key;s.pendingMoney=money;s.pendingItems=items;
   save(id,s); // Durable intent BEFORE any external side effects. Ambiguous failures stay locked.
   if(money>0&&!port.deposit(money)){s.pending=null;s.pendingItems=null;save(id,s);throw new IllegalArgumentException("TNE ha rifiutato il pagamento: nessun premio consegnato, puoi riprovare.");}
   port.deliver(items,slots);
   if(key.equals("bonus"))p.bonus=true;else p.claimed.add(key);
   s.pending=null;s.pendingItems=null;s.pendingMoney=0;
   try{save(id,s);}catch(Exception e){s.pending="CHECK_DISK:"+kind+":"+p.key+":"+key;throw e;}
  }
  JsonObject out=new JsonObject();out.addProperty("ok",true);out.addProperty("admin",port.admin());out.addProperty("timezone",config.timezone);
  out.addProperty("dailyBonus",s.daily.bonusMoney);out.addProperty("weeklyBonus",s.weekly.bonusMoney);if(port.admin())out.add("config",GSON.toJsonTree(config));out.add("state",GSON.toJsonTree(s));
  ZonedDateTime now=ZonedDateTime.now(clock.withZone(ZoneId.of(config.timezone)));
  out.addProperty("dailyReset",now.toLocalDate().plusDays(1).atStartOfDay(now.getZone()).toInstant().toString());
  out.addProperty("weeklyReset",now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(now.getZone()).toInstant().toString());
  out.addProperty("guns",available(config.weapons,port,true).size());out.addProperty("armor",available(config.armor,port,false).size());return out;
 }
}
