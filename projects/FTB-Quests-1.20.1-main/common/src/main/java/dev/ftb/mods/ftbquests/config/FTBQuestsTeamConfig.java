package dev.ftb.mods.ftbquests.config;

import dev.ftb.mods.ftblibrary.config.ConfigGroup;
import net.minecraft.server.MinecraftServer;

import java.util.regex.Pattern;

public class FTBQuestsTeamConfig {
    public static final ConfigGroup CONFIG = new ConfigGroup("ftbquests-teams");

    public static String API_BASE_URL = "http://nestworld.site:8000/api/v1/";

    public static void register() {
        ConfigGroup islandGroup = CONFIG.getOrCreateSubgroup("island_integration");

        islandGroup.addString("api_base_url", API_BASE_URL, v -> API_BASE_URL = v, "http://localhost:8000/api/v1/");
    }

    public static void load(MinecraftServer server) {
        // This is a simplified loader. A full implementation would use FTBLibraryServerConfig.load().
        // For this task, relying on the static default and registration is sufficient.
    }
}
