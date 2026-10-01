package it.nuovoordine.complete;

import java.lang.reflect.*;
import java.util.*;

final class R {
    static Object call(Object target,String name,Object...args) throws Exception {
        if(target==null) throw new NullPointerException(name+" target");
        Method best=null;
        for(Method m:target.getClass().getMethods()){
            if(!m.getName().equals(name)||m.getParameterCount()!=args.length) continue;
            if(compatible(m.getParameterTypes(),args)){best=m;break;}
        }
        if(best==null) throw new NoSuchMethodException(target.getClass().getName()+"."+name+"/"+args.length);
        try{return best.invoke(target,args);}catch(InvocationTargetException e){Throwable c=e.getCause();if(c instanceof Exception ex)throw ex;if(c instanceof Error er)throw er;throw e;}
    }
    static Object scall(Class<?> type,String name,Object...args)throws Exception{
        for(Method m:type.getMethods())if(Modifier.isStatic(m.getModifiers())&&m.getName().equals(name)&&m.getParameterCount()==args.length&&compatible(m.getParameterTypes(),args))return m.invoke(null,args);
        throw new NoSuchMethodException(type.getName()+"."+name);
    }
    static Object field(Object target,String...names)throws Exception{for(String n:names){try{Field f=target.getClass().getField(n);return f.get(target);}catch(NoSuchFieldException ignored){try{Field f=target.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(target);}catch(NoSuchFieldException ignored2){}}}throw new NoSuchFieldException(Arrays.toString(names));}
    static boolean compatible(Class<?>[] types,Object[] args){for(int i=0;i<types.length;i++){if(args[i]==null){if(types[i].isPrimitive())return false;continue;}Class<?> t=wrap(types[i]);if(!t.isInstance(args[i])&&!numberAssignable(t,args[i].getClass()))return false;}return true;}
    static Class<?> wrap(Class<?> t){if(!t.isPrimitive())return t;if(t==boolean.class)return Boolean.class;if(t==byte.class)return Byte.class;if(t==short.class)return Short.class;if(t==int.class)return Integer.class;if(t==long.class)return Long.class;if(t==float.class)return Float.class;if(t==double.class)return Double.class;if(t==char.class)return Character.class;return t;}
    static boolean numberAssignable(Class<?> t,Class<?> a){return Number.class.isAssignableFrom(t)&&Number.class.isAssignableFrom(a);}
    static double num(Object x){return ((Number)x).doubleValue();} static long lng(Object x){return ((Number)x).longValue();}
    static String name(Object player){try{return String.valueOf(call(call(player,"getName"),"getString"));}catch(Exception e){return String.valueOf(player);}}
    static UUID uuid(Object player){try{return (UUID)call(player,"getUUID");}catch(Exception e){return UUID.nameUUIDFromBytes(name(player).getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
    static String dimension(Object player)throws Exception{Object level;try{level=call(player,"serverLevel");}catch(Exception e){level=call(player,"level");}Object key=call(level,"dimension");return String.valueOf(call(key,"location"));}
    static Models.Loc loc(Object player)throws Exception{return new Models.Loc("",dimension(player),num(call(player,"getX")),num(call(player,"getY")),num(call(player,"getZ")));}
    static boolean alive(Object p){try{return Boolean.TRUE.equals(call(p,"isAlive"));}catch(Exception e){return true;}} static boolean spectator(Object p){try{return Boolean.TRUE.equals(call(p,"isSpectator"));}catch(Exception e){return false;}}
    static Object component(String text)throws Exception{Class<?> c=Class.forName("net.minecraft.network.chat.Component");try{return c.getMethod("literal",String.class).invoke(null,text);}catch(NoSuchMethodException e){return c.getMethod("m_237113_",String.class).invoke(null,text);}}
    static String root(Throwable t){while(t.getCause()!=null)t=t.getCause();return t.getMessage()==null?t.getClass().getSimpleName():t.getMessage();}
    private R(){}
}
