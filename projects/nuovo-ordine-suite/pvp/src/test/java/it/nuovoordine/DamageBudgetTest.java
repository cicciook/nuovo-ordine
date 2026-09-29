package it.nuovoordine;
import it.nuovoordine.pvp.DamageBudget;
import org.junit.Test;import static org.junit.Assert.*;
public class DamageBudgetTest {
    @Test public void bodyShotCap(){assertEquals(12,new DamageBudget().apply("a",0,1000,20,.55f,.6f,2),.001);}
    @Test public void pelletsShareCap(){var b=new DamageBudget();float total=0;for(int i=0;i<30;i++)total+=b.apply("a",0,5,20,.55f,.6f,2);assertEquals(12,total,.001);}
    @Test public void armorPiercingPartsShareCap(){var b=new DamageBudget();float total=b.apply("a",0,20,20,.55f,.6f,2)+b.apply("a",0,20,20,.55f,.6f,2);assertEquals(12,total,.001);}
    @Test public void nextBurstCanKillWounded(){var b=new DamageBudget();b.apply("a",0,100,20,.55f,.6f,2);assertEquals(12,b.apply("a",2,100,20,.55f,.6f,2),.001);}
    @Test public void attackersIndependent(){var b=new DamageBudget();b.apply("a",0,100,20,.55f,.6f,2);assertEquals(12,b.apply("b",0,100,20,.55f,.6f,2),.001);}
    @Test public void lowDamageScales(){assertEquals(2.75,new DamageBudget().apply("a",0,5,20,.55f,.6f,2),.001);}
    @Test public void nonFiniteDenied(){assertEquals(0,new DamageBudget().apply("a",0,Float.NaN,20,.55f,.6f,2),0);}
    @Test public void tickReset(){var b=new DamageBudget();b.apply("a",99,100,20,.55f,.6f,2);assertEquals(12,b.apply("a",0,100,20,.55f,.6f,2),.001);}
}
