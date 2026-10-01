package it.nuovoordine.complete;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class Config {
    private final Path path; private Properties p=new Properties();
    Config(Path path){this.path=path;load();}
    static Properties defaults(){
        Properties x=new Properties();
        x.setProperty("captureSeconds","120"); x.setProperty("strategic.defaultRadius","25"); x.setProperty("strategic.scoreEverySeconds","60"); x.setProperty("strategic.scorePerPoint","10");
        x.setProperty("event.everyMinutes","45"); x.setProperty("event.durationMinutes","15"); x.setProperty("event.scoreMultiplier","2"); x.setProperty("season.targetScore","2500"); x.setProperty("season.autoReset","true");
        x.setProperty("pvp.repeatVictimMinutes","10"); x.setProperty("pvp.ignoreSameTownOrNation","true");
        x.setProperty("injury.enabled","true"); x.setProperty("injury.minHitDamage","6.0"); x.setProperty("injury.bleedChance","0.35"); x.setProperty("injury.durationSeconds","90"); x.setProperty("injury.treatmentSeconds","8");
        x.setProperty("medical.minCivilRep","20"); x.setProperty("medical.cost","1500"); x.setProperty("medical.bandageCost","250");
        x.setProperty("contract.expireHours","168"); x.setProperty("contract.cancelFeePercent","2.0");
        x.setProperty("airdrop.everyMinutes","90"); x.setProperty("airdrop.durationMinutes","20"); x.setProperty("airdrop.rewardMoney","2500"); x.setProperty("airdrop.rewardCommands","give {player} minecraft:bread 8;give {player} minecraft:iron_ingot 8");
        x.setProperty("convoy.everyMinutes","120"); x.setProperty("convoy.recruitMinutes","3"); x.setProperty("convoy.durationMinutes","30"); x.setProperty("convoy.rewardMoney","10000"); x.setProperty("convoy.stealRewardMoney","7500"); x.setProperty("convoy.influenceReward","100"); x.setProperty("convoy.requireMtsVehicle","true");
        x.setProperty("garage.spawnCooldownSeconds","300"); x.setProperty("garage.spawnFee","250"); x.setProperty("garage.recoveryFee","5000"); x.setProperty("garage.insuranceDays","7"); x.setProperty("garage.insurancePrice","2500");
        x.setProperty("used.taxPercent","5.0"); x.setProperty("used.listingHours","168");
        x.setProperty("hub.enabled","true"); x.setProperty("hub.bind","0.0.0.0"); x.setProperty("hub.port","8765"); x.setProperty("hub.cors","*");
        x.setProperty("maintenance.intervalMinutes","60"); x.setProperty("state.saveEverySeconds","15");
        return x;
    }
    synchronized void load(){
        Properties n=defaults(); try{if(path.getParent()!=null)Files.createDirectories(path.getParent()); if(Files.isRegularFile(path))try(Reader r=Files.newBufferedReader(path,StandardCharsets.UTF_8)){n.load(r);} else try(Writer w=Files.newBufferedWriter(path,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)){n.store(w,"Nuovo Ordine Complete 1.0.0");}}catch(IOException ignored){} p=n;
    }
    String s(String k,String d){return p.getProperty(k,d);} boolean b(String k,boolean d){return Boolean.parseBoolean(s(k,Boolean.toString(d)));}
    int i(String k,int d,int min,int max){try{return Math.max(min,Math.min(max,Integer.parseInt(s(k,Integer.toString(d)))));}catch(Exception e){return d;}}
    long l(String k,long d,long min,long max){try{return Math.max(min,Math.min(max,Long.parseLong(s(k,Long.toString(d)))));}catch(Exception e){return d;}}
    double d(String k,double def,double min,double max){try{return Math.max(min,Math.min(max,Double.parseDouble(s(k,Double.toString(def)))));}catch(Exception e){return def;}}
}
