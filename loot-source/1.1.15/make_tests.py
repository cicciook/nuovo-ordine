from pathlib import Path
p=Path('loot-work/tests');p.mkdir(exist_ok=True)
files={
'net/minecraftforge/event/entity/player/PlayerContainerEvent.java':'package net.minecraftforge.event.entity.player; public class PlayerContainerEvent { public static class Open {} }',
'net/minecraft/world/item/ArmorItem.java':'package net.minecraft.world.item; public class ArmorItem {public String id; public ArmorItem(String s){id=s;} }',
'net/minecraft/resources/ResourceLocation.java':'package net.minecraft.resources; public record ResourceLocation(String id){public String toString(){return id;}}',
'net/minecraftforge/registries/ForgeRegistries.java':'''package net.minecraftforge.registries; import java.util.*; import net.minecraft.world.item.ArmorItem; public class ForgeRegistries { public static Registry ITEMS=new Registry(); public static class Registry {public Map<String,Object> map=new LinkedHashMap<>(); public Collection<Object> getValues(){return map.values();} public String getKey(Object o){for(var e:map.entrySet())if(e.getValue()==o)return e.getKey();return null;}public Object getValue(net.minecraft.resources.ResourceLocation key){return map.get(key.toString());}}}''',
'net/minecraft/nbt/TagParser.java':'''package net.minecraft.nbt; public class TagParser {public static String parseTag(String s){if(s.contains("\\\\\\\""))throw new IllegalArgumentException("Escaped SNBT");return s;}}''',
'net/minecraft/world/item/ItemStack.java':'''package net.minecraft.world.item; public class ItemStack {public Object item;public int count; public String tag;public ItemStack(Object i,int c){item=i;count=c;}public void setTag(String t){tag=t;}public boolean isEmpty(){return item==null||count==0;}public String getTag(){return tag;} }''',
'TestLoot.java':'''import java.nio.file.*;import java.lang.reflect.*;import java.util.*;import net.minecraftforge.registries.ForgeRegistries;import net.minecraft.world.item.*;
public class TestLoot {
 static Class<?> C;static Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=C.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);}
 static Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static Object global(String n)throws Exception{Field f=C.getDeclaredField(n);f.setAccessible(true);return f.get(null);}
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static class Chest {public ItemStack[] slots=new ItemStack[27];public Chest(){Arrays.fill(slots,new ItemStack(null,0));}public int getContainerSize(){return slots.length;}public ItemStack getItem(int i){return slots[i];}public void setItem(int i,ItemStack s){slots[i]=s;}public void setChanged(){} }
 public static void main(String[] args)throws Exception{
  for(String s:Files.readAllLines(Path.of(args[0])))ForgeRegistries.ITEMS.map.put(s,new ArmorItem(s));
  ForgeRegistries.ITEMS.map.put("survival_instinct:tier_iv_kevlar",new Object());
  C=Class.forName("com.cicciook.lootrmoreloot.LootOpenEvents");((Random)global("RNG")).setSeed(1234567L);
  Set<String> allowed=new HashSet<>();int total=0;
  for(String pool:List.of("COMMON","KEVLAR","AMMO","WEAPONS","TEMPLATES","ACCESSORIES"))for(Object e:(Object[])global(pool)){
   String id=(String)field(e,"itemId"),tag=(String)field(e,"snbt");ForgeRegistries.ITEMS.map.putIfAbsent(id,new Object());
   if(tag!=null)check(!tag.contains("\\\\\\\""),"bad SNBT "+tag);
   if(pool.equals("WEAPONS")){String key=id+":"+tag;check(!key.matches(".*(rpg7|:rpg[\\\"]|:minigun|:ai_awp|:m95|:m107|:awm|:m_98b|:ntw_20|:sentinel).*"),"Excluded weapon "+key);allowed.add(key);total++;}
   if(pool.equals("AMMO"))check((int)field(e,"min")>=20,"small ammo count");
  }
  check(total==94,"weapon/launcher ammo count "+total);
  Set<String> high=new HashSet<>(),low=new HashSet<>();
  for(int i=0;i<15000;i++)for(boolean h:new boolean[]{false,true}){Object a=call("pickSurvivalArmor",new Class[]{boolean.class},h);check(a instanceof ArmorItem,"not armor");(h?high:low).add(((ArmorItem)a).id);}
  check(high.size()==44,"high armor "+high.size());check(low.size()==35,"low armor "+low.size());
  check(high.contains("survival_instinct:exo_heavy_black_chestplate"),"missing Exo chestplate");check(low.contains("survival_instinct:green_recluit_armor_chestplate"),"missing Recluit chestplate");
  for(int i=0;i<3000;i++){
   Chest chest=new Chest();ItemStack original=new ItemStack(new Object(),64);chest.slots[0]=original;
   call("injectBalancedLoot",new Class[]{Object.class},chest);check(chest.slots[0]==original,"overwritten existing loot");
   for(int s=1;s<27;s++)if(!chest.slots[s].isEmpty()){check(chest.slots[s].tag.contains("lootr_more_loot_injected_1112"),"missing new marker");}
  }
  Chest full=new Chest();Arrays.fill(full.slots,new ItemStack(new Object(),1));check(Boolean.FALSE.equals(call("injectBalancedLoot",new Class[]{Object.class},full)),"full chest changed");
  check(global("SEEN_FILE").toString().endsWith("seen_1112.txt"),"old seen file");
  String merged=(String)call("mergeMarker",new Class[]{String.class},"{GunId:\\"tacz:m320\\"}");check(merged.contains("injected_1112"),"old merged marker");
  System.out.println("PASS: Java 17 verification; 94 weapon/ammo entries; 44 high and 35 low armor pieces; 3000 injections preserve existing loot; full chest retry; marker migration; >=20 ordinary ammo; SNBT escaping.");
 }
}'''
}
for name,s in files.items():
 f=p/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(s)
import zipfile,json
z=zipfile.ZipFile('loot-input/survival_instinct-1.0.2-forge-1.20.1.jar');d=json.loads(z.read('assets/survival_instinct/lang/en_us.json'))
(p/'armor.txt').write_text('\n'.join('survival_instinct:'+k.split('.')[-1] for k in d if k.startswith('item.survival_instinct.') and k.endswith(('_helmet','_chestplate','_leggings','_boots'))))
