package com.onedayonemasterpiece.repeatit;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.onedayonemasterpiece.repeatit.core.*;
import java.time.*;
import java.util.*;

/** One SQLite owner. Reaction, pending closure, outbox event and next plan commit together. */
public final class Store extends SQLiteOpenHelper {
    public static final Gson JSON=new GsonBuilder().registerTypeAdapter(Instant.class,(JsonSerializer<Instant>)(v,t,c)->new JsonPrimitive(v.toString()))
        .registerTypeAdapter(Instant.class,(JsonDeserializer<Instant>)(v,t,c)->Instant.parse(v.getAsString())).create();
    private static final long SAFE_GAP_MS=Duration.ofMinutes(3).toMillis();
    private static final long MAX_JITTER_MS=Duration.ofMinutes(10).toMillis();
    private static Store instance;
    static Clock clock=Clock.systemDefaultZone();
    private static Instant now(){return Instant.now(clock);}
    public static synchronized Store get(Context c){if(instance==null)instance=new Store(c.getApplicationContext());return instance;}
    private Store(Context c){super(c,"repeat-it.db",null,1);setWriteAheadLoggingEnabled(true);}
    @Override public void onConfigure(SQLiteDatabase db){db.setForeignKeyConstraintsEnabled(true);}
    @Override public void onCreate(SQLiteDatabase d){
        d.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY,v TEXT NOT NULL)");
        d.execSQL("CREATE TABLE cards(k TEXT PRIMARY KEY,body TEXT NOT NULL,path TEXT NOT NULL)");
        d.execSQL("CREATE TABLE states(k TEXT PRIMARY KEY,body TEXT NOT NULL)");
        d.execSQL("CREATE TABLE pending(mode TEXT PRIMARY KEY,pid TEXT UNIQUE NOT NULL,body TEXT NOT NULL)");
        d.execSQL("CREATE TABLE events(id TEXT PRIMARY KEY,mode TEXT NOT NULL,body TEXT NOT NULL,batch TEXT,verified INTEGER NOT NULL DEFAULT 0)");
        d.execSQL("CREATE TABLE batches(id TEXT PRIMARY KEY,mode TEXT NOT NULL,body TEXT NOT NULL,verified INTEGER NOT NULL DEFAULT 0)");
        d.execSQL("CREATE TABLE cache(path TEXT PRIMARY KEY,etag TEXT NOT NULL,body TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase d,int oldV,int newV){throw new IllegalStateException("explicit migration required; never wipe");}
    public synchronized String value(String key,String fallback){try(Cursor c=getReadableDatabase().rawQuery("SELECT v FROM kv WHERE k=?",new String[]{key})){return c.moveToFirst()?c.getString(0):fallback;}}
    public synchronized void put(String k,String v){ContentValues x=new ContentValues();x.put("k",k);x.put("v",v);getWritableDatabase().insertWithOnConflict("kv",null,x,SQLiteDatabase.CONFLICT_REPLACE);}
    public synchronized String device(){String id=value("device","");if(id.isEmpty()){id="android-"+UUID.randomUUID();put("device",id);}return id;}
    public synchronized String mode(){return value("mode","normal");}
    public synchronized boolean sound(){return value("sound","false").equals("true");}
    public synchronized Engine.Window window(){return new Engine.Window(LocalTime.parse(value("window_start","07:40")),LocalTime.parse(value("window_end","00:30")),ZoneId.systemDefault());}
    public synchronized List<Engine.Card> cards(){List<Engine.Card> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT body FROM cards ORDER BY k",null)){while(c.moveToNext())out.add(JSON.fromJson(c.getString(0),Engine.Card.class));}return out;}
    public synchronized List<Engine.Plan> plans(){return JSON.fromJson(value("plans","[]"),new TypeToken<List<Engine.Plan>>(){}.getType());}
    public synchronized Engine.State state(Engine.Card c,String mode){try(Cursor row=getReadableDatabase().rawQuery("SELECT body FROM states WHERE k=?",new String[]{mode+":"+c.learningKey()})){return row.moveToFirst()?JSON.fromJson(row.getString(0),Engine.State.class):new Engine.State();}}
    public synchronized Engine.Decision decision(){return JSON.fromJson(value("schedule_"+mode(),"{}"),Engine.Decision.class);}

    private Engine.Card cardByKey(String key){for(Engine.Card c:cards())if(c.key().equals(key))return c;return null;}
    private Engine.Plan planFor(Engine.Card card){if(card==null)return null;for(Engine.Plan p:plans())if(p.active&&p.deck.equals(card.deck))return p;return null;}
    private static long stableHash(String value){long h=0xcbf29ce484222325L;for(int i=0;i<value.length();i++){h^=value.charAt(i);h*=0x100000001b3L;}return h;}
    private static Instant later(Instant a,Instant b){return a.isAfter(b)?a:b;}

    /** Global human pacing floor plus deterministic bounded jitter; never creates a second pending card. */
    private void pace(Engine.Decision d,Instant now,Map<String,Engine.State> states){
        if(d.due==null||d.cardKey==null)return;Engine.Card card=cardByKey(d.cardKey);if(card==null)return;Engine.State state=states.getOrDefault(card.learningKey(),new Engine.State());
        Instant floor=window().next(now);String last=value("last_answer_at","");
        if(!last.isEmpty())try{floor=later(floor,window().next(Instant.parse(last).plusMillis(SAFE_GAP_MS)));}catch(RuntimeException ignored){}
        Instant eligible=state.eligible==null?floor:window().next(state.eligible);Instant minimum=later(floor,eligible);Instant due=later(d.due,minimum);
        long span=Math.max(0,Duration.between(now,due).toMillis());long radius=Math.min(MAX_JITTER_MS,span/12);
        if(radius>0){
            long width=Math.addExact(Math.multiplyExact(radius,2),1);long offset=Math.floorMod(stableHash(card.learningKey()+":"+state.contacts+":"+state.weakDebt+":"+state.lastReaction),width)-radius;
            Instant candidate=due.plusMillis(offset);if(candidate.isBefore(minimum))candidate=minimum;candidate=window().next(candidate);
            Engine.Plan plan=planFor(card);
            if(plan!=null&&plan.deadline!=null&&plan.deadline.isAfter(now)&&!candidate.isBefore(plan.deadline)){
                Instant latest=plan.deadline.minusSeconds(1);candidate=latest.isAfter(minimum)?latest:minimum;d.feasibility="risk_from_safe_pacing_or_jitter";
            }
            due=candidate;
        }
        d.due=due;
    }

    private synchronized void replan(Instant now){
        String mode=mode();Engine.Decision d;
        if(!mode.equals("normal")){
            d=new Engine.Decision();List<Engine.Card> all=cards();all.removeIf(c->!c.active);int position=Integer.parseInt(value("test_position_"+mode,"0"));
            if(position<all.size()){d.cardKey=all.get(position).key();d.due=now.plusMillis(mode.equals("user_demo")?300000:1000);d.remaining=all.size()-position;}
            else d.feasibility="test_session_complete";
        }else{
            Map<String,Engine.State> states=new HashMap<>();List<Long> latency=new ArrayList<>();
            for(Engine.Card c:cards())states.put(c.learningKey(),state(c,mode));
            try(Cursor rows=getReadableDatabase().rawQuery("SELECT body FROM events WHERE mode='normal' ORDER BY rowid DESC LIMIT 100",null)){
                while(rows.moveToNext()){JsonObject e=JsonParser.parseString(rows.getString(0)).getAsJsonObject();if(e.has("latency_ms"))latency.add(e.get("latency_ms").getAsLong());}
            }
            d=Engine.next(cards(),plans(),states,now,window(),latency);pace(d,now,states);
        }
        put("schedule_"+mode,JSON.toJson(d));
    }
    public synchronized void recompute(){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{replan(now());db.setTransactionSuccessful();}finally{db.endTransaction();}}

    public synchronized void settings(Map<String,Object> data){
        if(Contract.positive(data.get("schema_version"),100)!=1)throw new IllegalArgumentException("settings_schema");Map<String,Object>w=Contract.object(data.get("allowed_window"));
        if(!"07:40".equals(w.get("start"))||!"00:30".equals(w.get("end"))||!"device_local".equals(w.get("timezone")))throw new IllegalArgumentException("owner_window_mismatch");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{put("window_start","07:40");put("window_end","00:30");db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    public synchronized void setPlans(List<Engine.Plan> plans){if(JsonParser.parseString(value("plans","[]")).equals(JsonParser.parseString(JSON.toJson(plans))))return;SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{put("plans",JSON.toJson(plans));
        for(Engine.Card card:cards())for(Engine.Plan p:plans)if(p.active&&p.deck.equals(card.deck)){Engine.State state=state(card,"normal");if(state.last!=null){state=Engine.retarget(state,p,window());ContentValues row=new ContentValues();row.put("k","normal:"+card.learningKey());row.put("body",JSON.toJson(state));db.insertWithOnConflict("states",null,row,SQLiteDatabase.CONFLICT_REPLACE);}}
        replan(now());db.setTransactionSuccessful();}finally{db.endTransaction();}}

    /** Non-authoritative import used by synthetic tests. */
    public synchronized List<String> importCards(Map<String,List<Engine.Card>> documents){return importCards(documents,null);}
    /** Remote paths make successful GitHub sync authoritative while failed files remain last-known-good. */
    public synchronized List<String> importCards(Map<String,List<Engine.Card>> documents,Set<String> remotePaths){
        List<String> errors=new ArrayList<>();Map<String,List<Engine.Card>> grouped=new TreeMap<>();Map<String,String> paths=new HashMap<>();
        documents.forEach((p,cs)->cs.forEach(c->{grouped.computeIfAbsent(c.key(),k->new ArrayList<>()).add(c);paths.put(c.key(),p);}));Map<String,Engine.Card> old=new HashMap<>();cards().forEach(c->old.put(c.key(),c));
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            boolean changed=false;
            if(remotePaths!=null){
                try(Cursor rows=db.rawQuery("SELECT k,path FROM cards",null)){while(rows.moveToNext()){String k=rows.getString(0),path=rows.getString(1);boolean delete=!remotePaths.contains(path)||(documents.containsKey(path)&&!paths.containsKey(k));if(delete){db.delete("cards","k=?",new String[]{k});changed=true;}}}
            }
            for(Map.Entry<String,List<Engine.Card>> entry:grouped.entrySet()){
                if(entry.getValue().size()!=1){errors.add("duplicate_identity:"+entry.getKey());continue;}Engine.Card c=entry.getValue().get(0),before=old.get(c.key());
                if(before!=null&&(c.revision<before.revision||c.meaning<before.meaning)){errors.add("revision_rollback:"+c.key());continue;}
                if(before!=null&&c.revision==before.revision&&(!c.text.equals(before.text)||!c.title.equals(before.title)||c.meaning!=before.meaning)){errors.add("revision_not_incremented:"+c.key());continue;}
                if(before!=null&&JsonParser.parseString(JSON.toJson(before)).equals(JsonParser.parseString(JSON.toJson(c))))continue;
                changed=true;ContentValues row=new ContentValues();row.put("k",c.key());row.put("body",JSON.toJson(c));row.put("path",paths.get(c.key()));db.insertWithOnConflict("cards",null,row,SQLiteDatabase.CONFLICT_REPLACE);
            }
            if(changed)replan(now());db.setTransactionSuccessful();
        }finally{db.endTransaction();}return errors;
    }

    public static final class Pending {public String id,mode;public Engine.Card card;public long created,shown;public boolean alertDecided,soundAllowed;}
    public synchronized Pending pending(){try(Cursor c=getReadableDatabase().rawQuery("SELECT body FROM pending WHERE mode=?",new String[]{mode()})){return c.moveToFirst()?JSON.fromJson(c.getString(0),Pending.class):null;}}
    private void savePending(Pending p){ContentValues x=new ContentValues();x.put("mode",p.mode);x.put("pid",p.id);x.put("body",JSON.toJson(p));getWritableDatabase().insertWithOnConflict("pending",null,x,SQLiteDatabase.CONFLICT_REPLACE);}
    public synchronized Pending prepare(Instant now){
        Pending p=pending();if(p!=null)return p;Engine.Decision d=decision();if(d.due==null||d.cardKey==null||now.isBefore(d.due))return null;
        for(Engine.Card c:cards())if(c.active&&c.key().equals(d.cardKey)){p=new Pending();p.id=UUID.randomUUID().toString();p.mode=mode();p.card=c;p.created=now.toEpochMilli();p.soundAllowed=sound();savePending(p);return p;}
        replan(now);return null;
    }
    public synchronized boolean markPresentation(String id,boolean overlay,boolean activeUnlocked){
        Pending p=pending();if(p==null||!p.id.equals(id))return false;boolean signal=!p.alertDecided&&activeUnlocked&&p.soundAllowed;p.alertDecided=true;if(overlay&&p.shown==0)p.shown=clock.millis();savePending(p);return signal;
    }
    /** Once actually shown, a pending card remains answerable across the night; the gate controls only NEW presentations. */
    public synchronized boolean answer(String id,String reaction){
        Pending p=pending();Instant now=now();if(p==null||!p.id.equals(id)||p.shown==0)return false;if(!Set.of("remember","repeat").contains(reaction))return false;
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            Engine.State before=state(p.card,p.mode),after=before;boolean credited=false;
            if(p.mode.equals("normal")){
                Engine.Plan plan=planFor(p.card);if(plan!=null){after=Engine.answer(before,plan,reaction,Instant.ofEpochMilli(p.shown),now,window());credited=reaction.equals("remember")&&after.contacts>before.contacts;}
                ContentValues state=new ContentValues();state.put("k",p.mode+":"+p.card.learningKey());state.put("body",JSON.toJson(after));db.insertWithOnConflict("states",null,state,SQLiteDatabase.CONFLICT_REPLACE);put("last_answer_at",now.toString());
            }else put("test_position_"+p.mode,String.valueOf(Integer.parseInt(value("test_position_"+p.mode,"0"))+1));
            db.delete("pending","pid=?",new String[]{id});replan(now);
            Map<String,Object> event=new LinkedHashMap<>();event.put("schema_version",1);event.put("event_id",id+":response");event.put("device_id",device());event.put("mode",p.mode);event.put("type","response");event.put("presentation_id",id);event.put("deck_id",p.card.deck);event.put("card_id",p.card.id);event.put("revision",p.card.revision);event.put("meaning_revision",p.card.meaning);event.put("reaction",reaction);event.put("shown_at",Instant.ofEpochMilli(p.shown).toString());event.put("answered_at",now.toString());event.put("offset",OffsetDateTime.now(clock).getOffset().toString());event.put("credited",credited);event.put("latency_ms",Math.max(0,now.toEpochMilli()-p.shown));event.put("next_plan",decision());
            ContentValues row=new ContentValues();row.put("id",id+":response");row.put("mode",p.mode);row.put("body",JSON.toJson(event));db.insertOrThrow("events",null,row);db.setTransactionSuccessful();return true;
        }finally{db.endTransaction();}
    }
    public synchronized void setMode(String mode){
        if(!Set.of("normal","agent_debug","user_demo").contains(mode))throw new IllegalArgumentException("invalid_mode");SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            if(mode.equals("normal"))db.delete("pending","mode != 'normal'",null);else{db.delete("pending","mode != 'normal'",null);put("test_position_"+mode,"0");}
            put("mode",mode);replan(now());db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized void forceDue(){if(!mode().equals("agent_debug"))throw new SecurityException("agent_debug_only");Engine.Decision d=decision();if(d.cardKey==null)throw new IllegalStateException("test_session_complete");d.due=now();put("schedule_"+mode(),JSON.toJson(d));}

    public synchronized String[] cache(String path){try(Cursor c=getReadableDatabase().rawQuery("SELECT etag,body FROM cache WHERE path=?",new String[]{path})){return c.moveToFirst()?new String[]{c.getString(0),c.getString(1)}:null;}}
    public synchronized void cache(String path,String etag,String body){ContentValues v=new ContentValues();v.put("path",path);v.put("etag",etag==null?"":etag);v.put("body",body);getWritableDatabase().insertWithOnConflict("cache",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    public static final class Batch {public String id,mode,body;}
    public synchronized List<Batch> batches(){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            for(String mode:List.of("normal","agent_debug","user_demo")){
                List<String> ids=new ArrayList<>();StringBuilder body=new StringBuilder();try(Cursor c=db.rawQuery("SELECT id,body FROM events WHERE batch IS NULL AND mode=? ORDER BY rowid LIMIT 64",new String[]{mode})){while(c.moveToNext()){ids.add(c.getString(0));body.append(c.getString(1)).append('\n');}}
                if(!ids.isEmpty()){String id=UUID.randomUUID().toString();ContentValues row=new ContentValues();row.put("id",id);row.put("mode",mode);row.put("body",body.toString());db.insertOrThrow("batches",null,row);ContentValues b=new ContentValues();b.put("batch",id);for(String e:ids)db.update("events",b,"id=?",new String[]{e});}
            }db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        List<Batch> list=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT id,mode,body FROM batches WHERE verified=0 ORDER BY rowid",null)){while(c.moveToNext()){Batch b=new Batch();b.id=c.getString(0);b.mode=c.getString(1);b.body=c.getString(2);list.add(b);}}return list;
    }
    public synchronized void verified(String id){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{ContentValues v=new ContentValues();v.put("verified",1);db.update("batches",v,"id=?",new String[]{id});db.update("events",v,"batch=?",new String[]{id});db.setTransactionSuccessful();}finally{db.endTransaction();}}
    public synchronized String summary(){
        Map<String,Object> out=new LinkedHashMap<>();out.put("schema_version",1);out.put("device_id",device());out.put("self_report_not_memory_test",true);List<JsonObject> events=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT body FROM events WHERE mode='normal' AND verified=1 ORDER BY rowid",null)){while(c.moveToNext())events.add(JsonParser.parseString(c.getString(0)).getAsJsonObject());}
        Map<String,Map<String,Integer>> counts=new TreeMap<>();for(JsonObject e:events){String key=e.get("deck_id").getAsString()+"/"+e.get("card_id").getAsString()+"@"+e.get("meaning_revision").getAsString();Map<String,Integer> m=counts.computeIfAbsent(key,k->new TreeMap<>());String r=e.get("reaction").getAsString();m.put(r,m.getOrDefault(r,0)+1);if(e.get("credited").getAsBoolean()){m.put("contacts",m.getOrDefault("contacts",0)+1);m.put("successful_remembers",m.getOrDefault("successful_remembers",0)+1);}}
        out.put("verified_response_events",events.size());out.put("cards",counts);out.put("next_plan",JSON.fromJson(value("schedule_normal","{}"),JsonObject.class));return JSON.toJson(out)+"\n";
    }
    public synchronized int eventCount(boolean verified){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM events"+(verified?" WHERE verified=1":""),null)){c.moveToFirst();return c.getInt(0);}}
}
