package it.nuovoordine.complete;

import java.lang.reflect.*;
import java.util.*;

final class TownyBridge {
    static final class Identity {String town="",nation=""; String faction(){return !nation.isBlank()?nation:town;}}
    Identity identity(Object player){
        Identity out=new Identity();try{
            UUID uuid=R.uuid(player);String name=R.name(player);Class<?> apiC=Class.forName("com.palmergames.bukkit.towny.TownyAPI");Object api=R.scall(apiC,"getInstance");Object resident=null,bp=null;
            try{bp=R.scall(Class.forName("org.bukkit.Bukkit"),"getPlayer",uuid);}catch(Throwable ignored){}
            for(Method m:apiC.getMethods())if(m.getName().equals("getResident")&&m.getParameterCount()==1){Class<?> t=m.getParameterTypes()[0];Object arg=t==UUID.class?uuid:t==String.class?name:(bp!=null&&t.isInstance(bp)?bp:null);if(arg!=null)try{resident=m.invoke(api,arg);if(resident!=null)break;}catch(Throwable ignored){}}
            if(resident==null)return out;Object town=null;try{town=R.call(resident,"getTownOrNull");}catch(Exception ignored){}if(town==null)try{if(Boolean.TRUE.equals(R.call(resident,"hasTown")))town=R.call(resident,"getTown");}catch(Exception ignored){}
            if(town==null)return out;out.town=String.valueOf(R.call(town,"getName"));Object nation=null;try{nation=R.call(town,"getNationOrNull");}catch(Exception ignored){}if(nation==null)try{if(Boolean.TRUE.equals(R.call(town,"hasNation")))nation=R.call(town,"getNation");}catch(Exception ignored){}if(nation!=null)out.nation=String.valueOf(R.call(nation,"getName"));
        }catch(Throwable ignored){}return out;
    }
    String faction(Object player){Identity i=identity(player);if(!i.faction().isBlank())return i.faction();try{Object team=R.call(player,"getTeam");if(team!=null){String n=String.valueOf(R.call(team,"getName"));if(!n.isBlank())return n;}}catch(Exception ignored){}return R.name(player);}
    boolean allied(Object a,Object b){Identity x=identity(a),y=identity(b);if(!x.town.isBlank()&&x.town.equalsIgnoreCase(y.town))return true;return !x.nation.isBlank()&&x.nation.equalsIgnoreCase(y.nation);}
}
