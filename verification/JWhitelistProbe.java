import jcn.jwl.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.*;
import java.util.function.Consumer;

public final class JWhitelistProbe extends JavaPlugin {
 private void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 private ConsoleCommandSender console(String name,List<String> output){
  return (ConsoleCommandSender)Proxy.newProxyInstance(getClassLoader(),new Class[]{ConsoleCommandSender.class},(proxy,method,args)->{
   if(method.getName().equals("getName"))return name;
   if(method.getName().equals("hasPermission")||method.getName().equals("isOp"))return true;
   if(method.getName().equals("sendMessage")){if(args!=null)for(Object arg:args){if(arg instanceof String s)output.add(s);else if(arg instanceof String[] lines)output.addAll(Arrays.asList(lines));}return null;}
   if(method.getName().equals("getServer"))return getServer();
   if(method.getName().equals("toString"))return name;
   return null;
  });
 }
 @Override public void onEnable(){new Thread(()->{
  try{
   JWhitelist plugin=(JWhitelist)getServer().getPluginManager().getPlugin("JWhitelist");
   var db=plugin.getDatabaseManager();
   Set<String> original=new HashSet<>(db.getAllPlayerNameInWhitelist());
   check(!original.isEmpty(),"non-empty fixture whitelist");
   for(String name:original)check(db.isPlayerWhitelisted(name.toUpperCase(Locale.ROOT)),"case folded import");
   db.addPlayerToWhitelist("jwl_verify_expiring",1);Thread.sleep(10);
   check(!db.isPlayerWhitelisted("jwl_verify_expiring"),"expiry denies login");
   check(db.getTimeLeftInWhitelist("jwl_verify_expiring")==-2,"expiry info sentinel");
   db.cleanupWhitelist();check(db.getTimeLeftInWhitelist("jwl_verify_expiring")==-3,"expiry cleanup");
   ExecutorService readers=Executors.newFixedThreadPool(4);
   List<Future<?>> futures=new ArrayList<>();
   String permanent=original.iterator().next();
   for(int n=0;n<4;n++)futures.add(readers.submit(()->{for(int i=0;i<100;i++)check(db.isPlayerWhitelisted(permanent),"concurrent login");}));
   for(int i=0;i<20;i++){db.addPlayerToWhitelist("jwl_verify_"+i,-1);db.removePlayerFromWhitelist("jwl_verify_"+i);}
   for(Future<?> future:futures)future.get();readers.shutdown();
   var listener=new JWhitelistListener(plugin);
   var allowed=new AsyncPlayerPreLoginEvent(permanent,InetAddress.getLoopbackAddress(),UUID.randomUUID());listener.onPlayerPreLogin(allowed);
   check(allowed.getLoginResult()==AsyncPlayerPreLoginEvent.Result.ALLOWED,"prelogin allow");
   var denied=new AsyncPlayerPreLoginEvent("jwl_missing",InetAddress.getLoopbackAddress(),UUID.randomUUID());listener.onPlayerPreLogin(denied);
   check(denied.getLoginResult()==AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,"prelogin deny");
   var banned=new AsyncPlayerPreLoginEvent(permanent,InetAddress.getLoopbackAddress(),UUID.randomUUID());banned.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,"ban");listener.onPlayerPreLogin(banned);
   check(banned.getLoginResult()==AsyncPlayerPreLoginEvent.Result.KICK_BANNED,"preserved ban");
   List<String> messages=new ArrayList<>();var sender=console("bridge-probe",messages);
   var command=new JWhitelistCommand(plugin);
   command.onCommand(sender,null,"jwl",new String[]{"add","jwl_bridge_probe"});
   check(db.isPlayerWhitelisted("jwl_bridge_probe"),"bridge commit before return");
   check(messages.stream().anyMatch(s->s.contains("Player jwl_bridge_probe added to whitelist.")),"bridge synchronous confirmation");
   command.onCommand(sender,null,"jwl",new String[]{"remove","jwl_bridge_probe"});
   command.onCommand(sender,null,"jwl",new String[]{"clear","1d"});
   command.onCommand(console("other-admin",new ArrayList<>()),null,"jwl",new String[]{"confirm"});
   check(new HashSet<>(db.getAllPlayerNameInWhitelist()).equals(original),"other sender cannot clear");
   AtomicBoolean ownerScheduled=new AtomicBoolean();AtomicBoolean actionRan=new AtomicBoolean();
   Player fake=(Player)Proxy.newProxyInstance(getClassLoader(),new Class[]{Player.class},(proxy,method,args)->{
    if(method.getName().equals("isOnline"))return true;
    if(method.getName().equals("getScheduler")){
     return Proxy.newProxyInstance(getClassLoader(),new Class[]{method.getReturnType()},(sp,sm,sa)->{
      if(sm.getName().equals("run")){ownerScheduled.set(true);((Consumer<Object>)sa[1]).accept(null);}return null;
     });
    }return null;
   });
   plugin.runAtPlayer(fake,()->actionRan.set(true));
   check(ownerScheduled.get()&&actionRan.get(),"player scheduler routing");
   plugin.reloadConfiguration();
   check(new HashSet<>(db.getAllPlayerNameInWhitelist()).equals(original),"database reconnect persistence");
   java.nio.file.Path configPath=plugin.getDataFolder().toPath().resolve("config.yml");
   byte[] goodConfig=java.nio.file.Files.readAllBytes(configPath);
   try {
    String invalid=new String(goodConfig,java.nio.charset.StandardCharsets.UTF_8).replace("name: whitelist","name: nonexistent-folder/whitelist");
    check(!invalid.equals(new String(goodConfig,java.nio.charset.StandardCharsets.UTF_8)),"SQLite fixture config");
    java.nio.file.Files.writeString(configPath,invalid);
    try { plugin.reloadConfiguration(); throw new AssertionError("broken DB unexpectedly loaded"); }
    catch(IllegalStateException expected) { }
    check(!plugin.isDatabaseReady(),"failed reload is unavailable");
    var unavailable=new AsyncPlayerPreLoginEvent(permanent,InetAddress.getLoopbackAddress(),UUID.randomUUID());
    listener.onPlayerPreLogin(unavailable);
    check(unavailable.getLoginResult()==AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,"database failure denies login");
   } finally { java.nio.file.Files.write(configPath,goodConfig);plugin.reloadConfiguration(); }
   getLogger().info("JWL VERIFICATION PASSED: fixture names, case folding, expiry, concurrent DB reads/writes, prelogin allow/deny and bans, synchronous bridge command acknowledgement, player scheduler, reload persistence.");
  }catch(Throwable t){getLogger().log(java.util.logging.Level.SEVERE,"JWL VERIFICATION FAILED",t);}
 },"JWhitelist-verification").start();}
}
