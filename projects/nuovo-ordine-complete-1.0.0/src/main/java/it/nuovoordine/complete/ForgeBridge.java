package it.nuovoordine.complete;

import java.lang.reflect.*;
import java.util.function.*;

final class ForgeBridge {
    @SuppressWarnings({"unchecked","rawtypes"})
    static boolean on(String eventClass, Consumer<Object> handler){
        try{
            Object bus=Class.forName("net.minecraftforge.common.MinecraftForge").getField("EVENT_BUS").get(null);
            Class<?> priorityType=Class.forName("net.minecraftforge.eventbus.api.EventPriority");
            Object normal=Enum.valueOf((Class<? extends Enum>)priorityType.asSubclass(Enum.class),"NORMAL");
            Class<?> event=Class.forName(eventClass);
            for(Method m:bus.getClass().getMethods()){
                if(!m.getName().equals("addListener")||m.getParameterCount()!=4)continue;Class<?>[] a=m.getParameterTypes();
                if(a[0].getName().equals(priorityType.getName())&&a[1]==boolean.class&&a[2]==Class.class&&Consumer.class.isAssignableFrom(a[3])){m.invoke(bus,normal,false,event,handler);return true;}
            }
        }catch(Throwable ignored){}
        return false;
    }
    static Object literal(String name)throws Exception{return Class.forName("net.minecraft.commands.Commands").getMethod("literal",String.class).invoke(null,name);}
    static Object greedyArg(String name)throws Exception{Class<?> s=Class.forName("com.mojang.brigadier.arguments.StringArgumentType");Object greedy=s.getMethod("greedyString").invoke(null);Class<?> at=Class.forName("com.mojang.brigadier.arguments.ArgumentType");return Class.forName("net.minecraft.commands.Commands").getMethod("argument",String.class,at).invoke(null,name,greedy);}
    static void then(Object parent,Object child)throws Exception{for(Method m:parent.getClass().getMethods())if(m.getName().equals("then")&&m.getParameterCount()==1){m.invoke(parent,child);return;}throw new NoSuchMethodException("then");}
    static void executes(Object builder,CommandBody body)throws Exception{
        Class<?> cmd=Class.forName("com.mojang.brigadier.Command");Object proxy=Proxy.newProxyInstance(cmd.getClassLoader(),new Class[]{cmd},(p,m,a)->{
            if(m.getName().equals("run")){Object ctx=a[0];return body.run(R.call(ctx,"getSource"),ctx);}if(m.getName().equals("hashCode"))return System.identityHashCode(p);if(m.getName().equals("equals"))return p==a[0];if(m.getName().equals("toString"))return "NuovoOrdineCompleteCommand";return 0;});
        for(Method m:builder.getClass().getMethods())if(m.getName().equals("executes")&&m.getParameterCount()==1){m.invoke(builder,proxy);return;}throw new NoSuchMethodException("executes");
    }
    static String arg(Object ctx,String name){try{return String.valueOf(R.call(ctx,"getArgument",name,String.class));}catch(Exception e){return "";}}
    interface CommandBody{int run(Object source,Object context)throws Exception;}
    private ForgeBridge(){}
}
