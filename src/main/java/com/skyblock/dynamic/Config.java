package com.skyblock.dynamic;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

/**
 * Manages the configuration for the SkyBlock mod.
 */
@Mod.EventBusSubscriber(modid = "skyblock", bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // Define a pattern for basic URL validation (simplified)
    private static final Pattern URL_PATTERN = Pattern.compile("^https?://[a-zA-Z0-9.-]+(:[0-9]{1,5})?(/.*)?$");

    private static final ForgeConfigSpec.ConfigValue<String> API_BASE_URL = BUILDER
            .comment("The base URL for the SkyBlock API (e.g., http://localhost:8000/api/v1)")
            .define("apiBaseUrl", "http://localhost:8000/api/v1", Config::validateUrl);

    private static final ForgeConfigSpec.IntValue API_REQUEST_TIMEOUT_SECONDS = BUILDER
            .comment("Timeout in seconds for API requests to the SkyBlock API.")
            .defineInRange("apiRequestTimeoutSeconds", 10, 5, 60);

    private static final ForgeConfigSpec.ConfigValue<String> OWNER_UUID = BUILDER
            .comment("The UUID of the island owner.")
            .define("ownerUuid", "", obj -> obj instanceof String);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    private static String apiBaseUrl;
    private static int apiRequestTimeoutSeconds;
    private static String ownerUuid;

    private static boolean validateUrl(final Object obj) {
        if (obj instanceof final String urlString) {
            return !StringUtils.isBlank(urlString) && URL_PATTERN.matcher(urlString).matches();
        }
        return false;
    }

    public static String getApiBaseUrl() {
        if (apiBaseUrl == null) {
            apiBaseUrl = API_BASE_URL.get();
        }
        return apiBaseUrl;
    }

    public static int getApiRequestTimeoutSeconds() {
        return apiRequestTimeoutSeconds > 0 ? apiRequestTimeoutSeconds : 10;
    }

    public static String getOwnerUuid() {
        if (ownerUuid == null) {
            ownerUuid = OWNER_UUID.get();
        }
        return ownerUuid;
    }

    public static void bake() {
        apiBaseUrl = API_BASE_URL.get();
        apiRequestTimeoutSeconds = API_REQUEST_TIMEOUT_SECONDS.get();
        ownerUuid = OWNER_UUID.get();

        if (apiBaseUrl != null && !apiBaseUrl.endsWith("/")) {
            apiBaseUrl += "/";
        }
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            bake();
        }
    }

    @SubscribeEvent
    static void onReload(final ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            bake();
        }
    }
}