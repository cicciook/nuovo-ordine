package it.nuovoordine.market;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;

/** All access is confined to the server thread. Listings are advertisements, never escrow. */
public final class MarketService {
    public record Actor(String id, String name, String item, String dimension, int x, int y, int z) {}
    private final Path file;
    private final LongSupplier clock;
    private JsonObject state;
    public MarketService(Path file, LongSupplier clock) throws Exception {
        this.file=file; this.clock=clock;
        state=Files.exists(file)?JsonParser.parseString(Files.readString(file)).getAsJsonObject():new JsonObject();
        if(!state.has("posts"))state.add("posts",new JsonArray());
    }
    private String text(JsonObject o,String k,int max) {
        if(!o.has(k)||!o.get(k).isJsonPrimitive()||!o.get(k).getAsJsonPrimitive().isString())throw new IllegalArgumentException("Campo mancante: "+k);
        String s=o.get(k).getAsString().strip();
        if(s.isEmpty()||s.length()>max||s.chars().anyMatch(c->c<32&&c!='\n'))throw new IllegalArgumentException("Campo non valido: "+k);
        return s;
    }
    private static String str(JsonObject o,String k){return o.get(k).getAsString();}
    private JsonObject post(JsonObject s,String id){for(JsonElement e:s.getAsJsonArray("posts")){JsonObject p=e.getAsJsonObject();if(str(p,"id").equals(id))return p;}throw new IllegalArgumentException("Annuncio non trovato o scaduto");}
    private JsonObject thread(JsonObject p,Actor a,JsonObject q,boolean create){
        String buyer=str(p,"owner").equals(a.id)?text(q,"buyer",36):a.id;
        if(buyer.equals(str(p,"owner")))throw new IllegalArgumentException("Non puoi rispondere al tuo annuncio");
        JsonObject ts=p.getAsJsonObject("threads");
        if(!ts.has(buyer)){
            if(!create||str(p,"owner").equals(a.id))throw new IllegalArgumentException("Conversazione inesistente");
            if(ts.size()>=30)throw new IllegalArgumentException("Troppe conversazioni su questo annuncio");
            JsonObject t=new JsonObject();t.addProperty("buyer",buyer);t.addProperty("name",a.name);t.add("messages",new JsonArray());ts.add(buyer,t);
        }
        return ts.getAsJsonObject(buyer);
    }
    private void save(JsonObject next)throws Exception{
        Files.createDirectories(file.getParent());Path temp=file.resolveSibling(file.getFileName()+".tmp");
        Files.writeString(temp,next.toString(),StandardCharsets.UTF_8);
        try{Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException ex){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
        state=next;
    }
    public JsonObject handle(Actor a,JsonObject q)throws Exception{
        String action=text(q,"action",20);long now=clock.getAsLong();
        JsonObject next=state.deepCopy();JsonArray posts=next.getAsJsonArray("posts");
        posts.asList().removeIf(e->e.getAsJsonObject().get("expires").getAsLong()<now);
        boolean changed=false;
        if(action.equals("create")){
            if(a.item==null||a.item.isBlank())throw new IllegalArgumentException("Tieni in mano l'oggetto da pubblicizzare");
            long own=posts.asList().stream().filter(e->str(e.getAsJsonObject(),"owner").equals(a.id)).count();
            if(own>=10||posts.size()>=2000)throw new IllegalArgumentException("Limite annunci raggiunto");
            JsonObject p=new JsonObject();p.addProperty("id",UUID.randomUUID().toString());p.addProperty("owner",a.id);p.addProperty("seller",a.name);
            p.addProperty("title",text(q,"title",80));p.addProperty("description",text(q,"description",1500));
            p.addProperty("price",text(q,"price",160));p.addProperty("item",a.item);p.addProperty("expires",now+7L*86400000);
            p.add("threads",new JsonObject());posts.add(p);changed=true;
        } else if(Set.of("reply","meet","accept","cancelMeet","close").contains(action)){
            JsonObject p=post(next,text(q,"post",36));
            if(action.equals("close")){
                if(!str(p,"owner").equals(a.id))throw new IllegalArgumentException("Solo il venditore può chiudere l'annuncio");
                posts.remove(p);
            }else{
                JsonObject t=thread(p,a,q,action.equals("reply"));
                if(action.equals("reply")){
                    JsonArray messages=t.getAsJsonArray("messages");
                    if(messages.size()>=100)throw new IllegalArgumentException("Limite messaggi raggiunto");
                    JsonObject m=new JsonObject();m.addProperty("author",a.id);m.addProperty("name",a.name);m.addProperty("text",text(q,"text",800));m.addProperty("time",now);messages.add(m);
                }else if(action.equals("meet")){
                    int minutes=q.has("minutes")?q.get("minutes").getAsInt():30;
                    if(minutes<5||minutes>180)throw new IllegalArgumentException("Durata da 5 a 180 minuti");
                    JsonObject m=new JsonObject();m.addProperty("id",UUID.randomUUID().toString());m.addProperty("proposer",a.id);m.addProperty("dimension",a.dimension);m.addProperty("x",a.x);m.addProperty("y",a.y);m.addProperty("z",a.z);m.addProperty("expires",now+minutes*60000L);m.addProperty("accepted",false);t.add("meeting",m);
                }else if(action.equals("accept")){
                    if(!t.has("meeting"))throw new IllegalArgumentException("Nessun appuntamento");
                    JsonObject m=t.getAsJsonObject("meeting");
                    if(str(m,"proposer").equals(a.id)||m.get("expires").getAsLong()<=now)throw new IllegalArgumentException("Deve accettare l'altro partecipante; verifica la scadenza");
                    m.addProperty("accepted",true);
                }else t.remove("meeting");
            }
            changed=true;
        }else if(!Set.of("list","view","meetings").contains(action))throw new IllegalArgumentException("Azione sconosciuta");
        if(changed)save(next);
        JsonObject out=new JsonObject();out.addProperty("ok",true);out.addProperty("me",a.id);out.addProperty("now",now);
        if(action.equals("view")){
            JsonObject p=post(next,text(q,"post",36)).deepCopy();JsonObject all=p.getAsJsonObject("threads");
            if(!str(p,"owner").equals(a.id)){JsonObject own=new JsonObject();if(all.has(a.id))own.add(a.id,all.get(a.id));p.add("threads",own);}out.add("post",p);
        }else if(!action.equals("meetings")){
            int page=q.has("page")?q.get("page").getAsInt():0;page=Math.max(0,Math.min(page,100));
            List<JsonObject> visible=new ArrayList<>();String search=q.has("search")?text(q,"search",80).toLowerCase(Locale.ROOT):"";
            for(int i=posts.size()-1;i>=0;i--){JsonObject p=posts.get(i).getAsJsonObject();if(!search.isEmpty()&&!(str(p,"title")+str(p,"item")).toLowerCase(Locale.ROOT).contains(search))continue;JsonObject summary=p.deepCopy();summary.remove("threads");visible.add(summary);}
            JsonArray list=new JsonArray();for(int i=page*20;i<Math.min(visible.size(),page*20+20);i++)list.add(visible.get(i));out.add("posts",list);out.addProperty("total",visible.size());out.addProperty("page",page);
        }
        JsonArray meetings=new JsonArray();
        for(JsonElement e:posts){JsonObject p=e.getAsJsonObject();for(var entry:p.getAsJsonObject("threads").entrySet()){
            if(!str(p,"owner").equals(a.id)&&!entry.getKey().equals(a.id))continue;
            JsonObject t=entry.getValue().getAsJsonObject();if(!t.has("meeting"))continue;JsonObject m=t.getAsJsonObject("meeting");
            if(m.get("expires").getAsLong()>now&&m.get("accepted").getAsBoolean()){JsonObject cp=m.deepCopy();cp.addProperty("title",str(p,"title"));meetings.add(cp);}
        }}out.add("meetings",meetings);return out;
    }
}
