package net.minecraftforge.coremod.api;

import javax.script.ScriptEngine;
import java.nio.file.Files;
import java.nio.file.Path;

/** Only the resource/log bridge is stubbed; scripts run in Forge's Nashorn version. */
public final class ASMAPI {
    public static ScriptEngine engine;
    public static Path resources;
    public static int errors;
    public static Object loadData(String name) throws Exception {
        engine.put("testJson", Files.readString(resources.resolve(name)));
        return engine.eval("JSON.parse(testJson)");
    }
    public static void log(String level, String message) {
        if (level.equals("ERROR")) errors++;
        System.out.println(level + " " + message);
    }
}
