package it.nuovoordine.complete;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;

final class Models {
    static final class Loc implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        String dimension; double x,y,z; String name;
        Loc() {}
        Loc(String name,String dimension,double x,double y,double z){this.name=name;this.dimension=dimension;this.x=x;this.y=y;this.z=z;}
        double dist2(String dim,double px,double py,double pz){if(!Objects.equals(dimension,dim)) return Double.POSITIVE_INFINITY; double dx=x-px,dy=y-py,dz=z-pz;return dx*dx+dy*dy+dz*dz;}
    }
    static final class Point implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        String id,name,owner="",candidate=""; Loc loc; double radius; int progress;
        Point(String id,String name,Loc loc,double radius){this.id=id;this.name=name;this.loc=loc;this.radius=radius;}
    }
    static final class Rep implements Serializable { @Serial private static final long serialVersionUID = 1L; int civil,criminal,military; }
    static final class Stats implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        String lastName=""; long kills,deaths,contracts,loot,convoys,airdrops,strategicCaptures,vehiclesBought,vehiclesSold; double moneySpent,moneyEarned,mtsKm;
    }
    static final class Injury implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        long bleedUntil,legUntil,headUntil,lastBleedDamageAt,lastHitAt; int severity; String zone="torso";
        boolean active(long now){return bleedUntil>now||legUntil>now||headUntil>now;}
    }
    enum ContractStatus { OPEN, ACCEPTED, COMPLETED, CANCELLED, EXPIRED }
    static final class Contract implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        String id,type,creatorName,creatorFaction="",acceptorName="",acceptorFaction="",target="",description="";
        UUID creator,acceptor; double reward; long createdAt,expiresAt,completedAt; ContractStatus status=ContractStatus.OPEN;
    }
    static final class Route implements Serializable { @Serial private static final long serialVersionUID = 1L; String id,name; Loc start,end; }
    enum ConvoyPhase { NONE, RECRUITING, ENROUTE, CONTESTED }
    static final class Convoy implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        ConvoyPhase phase=ConvoyPhase.NONE; String routeId="",driverName="",driverFaction=""; UUID driver; long recruitEndsAt,startedAt,expiresAt; Loc contestedAt;
    }
    static final class Airdrop implements Serializable { @Serial private static final long serialVersionUID = 1L; String pointId=""; long spawnedAt,expiresAt; boolean claimed; }
    static final class VehicleAsset implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        String key,displayName,plate; long acquiredAt,lastSpawnAt,insuranceUntil; double km; int condition=100; boolean destroyed;
    }
    static final class UsedListing implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        String id,vehicleKey,vehicleName,plate,sellerName; UUID seller; double price; long createdAt,expiresAt;
    }
    static final class Treatment implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        UUID player; long endsAt; String type; String dimension; double x,y,z;
    }
    static final class State implements Serializable {
        @Serial private static final long serialVersionUID = 3L;
        int version=3;
        Map<String,Point> points=new LinkedHashMap<>(); Map<String,Long> influence=new LinkedHashMap<>(); Map<String,Integer> legacy=new LinkedHashMap<>();
        Map<UUID,Rep> reputation=new HashMap<>(); Map<UUID,Stats> stats=new HashMap<>(); Map<UUID,Injury> injuries=new HashMap<>(); Map<String,Contract> contracts=new LinkedHashMap<>();
        Map<String,Loc> airdropPoints=new LinkedHashMap<>(); Airdrop airdrop=new Airdrop(); Map<String,Route> routes=new LinkedHashMap<>(); Convoy convoy=new Convoy();
        Map<UUID,Map<String,VehicleAsset>> vehicles=new HashMap<>(); Map<String,UsedListing> usedListings=new LinkedHashMap<>(); Map<String,Long> pvpCooldown=new HashMap<>();
        long nextStrategicEventAt,eventEndsAt,nextAirdropAt,nextConvoyAt,lastScoreAt,lastMaintenanceAt,totalMoneySunk; String eventPoint="";
    }
    private Models(){}
}
