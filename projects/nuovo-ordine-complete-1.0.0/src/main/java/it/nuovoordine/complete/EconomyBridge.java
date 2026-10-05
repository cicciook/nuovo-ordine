package it.nuovoordine.complete;

import java.math.BigDecimal;
import java.util.UUID;

final class EconomyBridge {
    private Class<?> economyApi;
    boolean ready(){return api()!=null;}
    private Class<?> api(){
        if(economyApi!=null)return economyApi;
        try{
            Class<?> bukkit=Class.forName("org.bukkit.Bukkit");
            Object pm=bukkit.getMethod("getPluginManager").invoke(null);
            Object essentials=pm.getClass().getMethod("getPlugin",String.class).invoke(pm,"Essentials");
            if(essentials==null||!Boolean.TRUE.equals(essentials.getClass().getMethod("isEnabled").invoke(essentials)))return null;
            economyApi=essentials.getClass().getClassLoader().loadClass("com.earth2me.essentials.api.Economy");
            return economyApi;
        }catch(Throwable ignored){return null;}
    }
    double balance(UUID uuid){
        try{
            Class<?> api=api();if(api==null)return Double.NaN;
            BigDecimal value=(BigDecimal)api.getMethod("getMoneyExact",UUID.class).invoke(null,uuid);
            return value.doubleValue();
        }catch(Throwable ex){return Double.NaN;}
    }
    boolean withdraw(UUID uuid,double amount){
        if(amount<0||!Double.isFinite(amount))return false;
        try{
            Class<?> api=api();if(api==null)return false;
            BigDecimal value=BigDecimal.valueOf(amount);
            BigDecimal current=(BigDecimal)api.getMethod("getMoneyExact",UUID.class).invoke(null,uuid);
            if(current.compareTo(value)<0)return false;
            api.getMethod("subtract",UUID.class,BigDecimal.class).invoke(null,uuid,value);
            return true;
        }catch(Throwable ex){return false;}
    }
    boolean deposit(UUID uuid,double amount){
        if(amount<0||!Double.isFinite(amount))return false;
        try{
            Class<?> api=api();if(api==null)return false;
            api.getMethod("add",UUID.class,BigDecimal.class).invoke(null,uuid,BigDecimal.valueOf(amount));
            return true;
        }catch(Throwable ex){return false;}
    }
}
