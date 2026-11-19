package ac.navro.proxy.command;

import ac.navro.proxy.alert.AlertManager;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;


public final class ConsoleCommands {
    private ConsoleCommands() { }
    public static void start(CommandRuntime runtime,AlertManager alerts) {
        var sender=new NavroCommand.Sender() {
            public UUID identity(){return AlertManager.CONSOLE_UUID;}
            public boolean hasPermission(String node){return true;}
            public void sendMessage(String text){System.out.println(text);}
            public boolean isPlayer(){return false;}
        };
        Thread thread=new Thread(()-> {
            try(var input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))) {
                for(String line;(line=input.readLine())!=null;) NavroCommand.handle(line,sender,alerts,runtime);
            } catch(IOException e) { System.err.println("Console command input closed: "+e.getMessage()); }
        },"navro-console-commands");
        thread.setDaemon(true);thread.start();
    }
}
