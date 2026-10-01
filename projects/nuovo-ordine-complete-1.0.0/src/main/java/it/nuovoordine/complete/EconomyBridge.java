package it.nuovoordine.complete;

import java.util.*;

final class EconomyBridge {
    private Object economy;
    boolean ready(){return provider()!=null;}
    private Object provider(){
        if(economy!=null)return economy;
        try{Class<?> bukkit=Class.forName("org.bukkit.Bukkit");Object sm=R.scall(bukkit,"getServicesManager");Class<?> eco=Class.forName("net.milkbowl.vault.economy.Economy");Object reg=R.call(sm,"getRegistration",eco);if(reg!=null)economy=R.call(reg,"getProvider");}catch(Throwable ignored){}
        return economy;
    }
    private Object offline(UUID uuid)throws Exception{return R.scall(Class.forName("org.bukkit.Bukkit"),"getOfflinePlayer",uuid);}
    double balance(UUID uuid){try{Object e=provider();if(e==null)return Double.NaN;return R.num(R.call(e,"getBalance",offline(uuid)));}catch(Exception ex){return Double.NaN;}}
    boolean withdraw(UUID uuid,double amount){if(amount<0)return false;try{Object e=provider();if(e==null)return false;Object r=R.call(e,"withdrawPlayer",offline(uuid),amount);return success(r);}catch(Exception ex){return false;}}
    boolean deposit(UUID uuid,double amount){if(amount<0)return false;try{Object e=provider();if(e==null)return false;Object r=R.call(e,"depositPlayer",offline(uuid),amount);return success(r);}catch(Exception ex){return false;}}
    private boolean success(Object response){try{return Boolean.TRUE.equals(R.call(response,"transactionSuccess"));}catch(Exception e){return response!=null;}}
}
