package com.skyblock.dynamic;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;

public class Config {
    private static final String CONFIG_NAME = "skyblock_island_data.toml";
    private static CommentedFileConfig config;

    public static void loadConfig() {
        File configFile = new File(FMLPaths.CONFIGDIR.get().toFile(), CONFIG_NAME);
        config = CommentedFileConfig.builder(configFile).autosave().build();
        config.load();
    }

    public static String getOwnerUuid() {
        return config.get("owner_uuid");
    }

    public static String getApiBaseUrl() {
        return config.get("api_base_url");
    }

    public static String getTeamId() {
        return config.get("team_id");
    }
}