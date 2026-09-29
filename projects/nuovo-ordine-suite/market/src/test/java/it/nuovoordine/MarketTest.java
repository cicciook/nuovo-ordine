package it.nuovoordine;
import it.nuovoordine.market.MarketService;
import com.google.gson.*;
import org.junit.*;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicLong;
public class MarketTest {
    MarketService s;Path file;AtomicLong now;String id;
    final MarketService.Actor seller=new MarketService.Actor("11111111-1111-1111-1111-111111111111","Seller","Diamante ×1","minecraft:overworld",1,70,2);
    final MarketService.Actor buyer=new MarketService.Actor("22222222-2222-2222-2222-222222222222","Buyer","Ferro","minecraft:overworld",3,70,4);
    final MarketService.Actor other=new MarketService.Actor("33333333-3333-3333-3333-333333333333","Other","Ferro","minecraft:overworld",3,70,4);
    JsonObject q(String action){JsonObject q=new JsonObject();q.addProperty("action",action);if(id!=null)q.addProperty("post",id);q.addProperty("buyer",buyer.id());return q;}
    @Before public void setup()throws Exception{now=new AtomicLong(1_000_000);file=Files.createTempDirectory("market-test").resolve("market.json");s=new MarketService(file,now::get);JsonObject q=q("create");q.addProperty("title","Diamante");q.addProperty("description","Vendita");q.addProperty("price","1000 dollari");id=s.handle(seller,q).getAsJsonArray("posts").get(0).getAsJsonObject().get("id").getAsString();}
    void reply(MarketService.Actor actor,String text)throws Exception{var q=q("reply");q.addProperty("text",text);s.handle(actor,q);}
    @Test public void persistence()throws Exception{var loaded=new MarketService(file,now::get);assertEquals(id,loaded.handle(seller,q("view")).getAsJsonObject("post").get("id").getAsString());}
    @Test public void privateConversations()throws Exception{reply(buyer,"secret");assertFalse(s.handle(other,q("view")).toString().contains("secret"));assertTrue(s.handle(seller,q("view")).toString().contains("secret"));}
    @Test public void cannotImpersonateBuyer()throws Exception{reply(buyer,"secret");reply(other,"hello");var result=s.handle(other,q("view"));assertFalse(result.toString().contains("secret"));assertTrue(result.toString().contains("hello"));}
    @Test(expected=IllegalArgumentException.class) public void cannotCloseOthers()throws Exception{s.handle(buyer,q("close"));}
    @Test(expected=IllegalArgumentException.class) public void cannotSelfReply()throws Exception{var q=q("reply");q.addProperty("buyer",seller.id());q.addProperty("text","hi");s.handle(seller,q);}
    @Test public void handshakeAndCancellation()throws Exception{reply(buyer,"hi");s.handle(buyer,q("meet"));assertEquals(0,s.handle(seller,q("meetings")).getAsJsonArray("meetings").size());s.handle(seller,q("accept"));assertEquals(1,s.handle(buyer,q("meetings")).getAsJsonArray("meetings").size());assertEquals(0,s.handle(other,q("meetings")).getAsJsonArray("meetings").size());s.handle(buyer,q("cancelMeet"));assertEquals(0,s.handle(seller,q("meetings")).getAsJsonArray("meetings").size());}
    @Test(expected=IllegalArgumentException.class) public void noSelfAcceptance()throws Exception{reply(buyer,"hi");s.handle(buyer,q("meet"));s.handle(buyer,q("accept"));}
    @Test public void expiry()throws Exception{reply(buyer,"hi");s.handle(buyer,q("meet"));s.handle(seller,q("accept"));now.addAndGet(31*60000);assertEquals(0,s.handle(seller,q("meetings")).getAsJsonArray("meetings").size());now.addAndGet(8L*86400000);assertEquals(0,s.handle(seller,q("list")).getAsJsonArray("posts").size());}
    @Test public void failedWriteDoesNotMutate()throws Exception{Files.delete(file);Files.createDirectory(file);Files.writeString(file.resolve("block"),"x");try{s.handle(seller,q("close"));fail();}catch(java.io.IOException expected){}assertEquals(id,s.handle(seller,q("view")).getAsJsonObject("post").get("id").getAsString());}
    @Test(expected=IllegalArgumentException.class) public void oversizedReply()throws Exception{reply(buyer,"x".repeat(801));}
    @Test public void closeCancelsMeeting()throws Exception{reply(buyer,"hi");s.handle(buyer,q("meet"));s.handle(seller,q("accept"));s.handle(seller,q("close"));assertEquals(0,s.handle(buyer,q("meetings")).getAsJsonArray("meetings").size());}
    @Test public void pageDoesNotLeakReplies()throws Exception{reply(buyer,"TOP_SECRET");assertFalse(s.handle(other,q("list")).toString().contains("TOP_SECRET"));}
}
