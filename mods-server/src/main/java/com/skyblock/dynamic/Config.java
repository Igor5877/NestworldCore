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
@Mod.EventBusSubscriber(modid = SkyBlockMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD) // Fixed MODID reference
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


    static final ForgeConfigSpec SPEC = BUILDER.build();

    private static String apiBaseUrl;
    private static int apiRequestTimeoutSeconds; // Field to store the baked value

    /**
     * Validates a URL.
     *
     * @param obj The object to validate.
     * @return True if the object is a valid URL, false otherwise.
     */
    private static boolean validateUrl(final Object obj) {
        if (obj instanceof final String urlString) {
            return !StringUtils.isBlank(urlString) && URL_PATTERN.matcher(urlString).matches();
        }
        return false;
    }
    
    /**
     * Gets the base URL for the SkyBlock API.
     *
     * @return The base URL for the SkyBlock API.
     */
    public static String getApiBaseUrl() {
        if (apiBaseUrl == null) {
            // Attempt to load it if not baked yet (e.g. direct call before config event)
            // This is a fallback, ideally `bake` is called by the event system.
            apiBaseUrl = API_BASE_URL.get();
        }
        return apiBaseUrl;
    }

    /**
     * Gets the timeout in seconds for API requests.
     *
     * @return The timeout in seconds for API requests.
     */
    public static int getApiRequestTimeoutSeconds() {
        // Return the baked value. If called before baking, this might be 0 or the previous value.
        // SkyBlockMod's HTTP_CLIENT initialization is static, but the timeout for the *request*
        // is applied when the request is built, by which time Config.bake() should have run via events.
        return apiRequestTimeoutSeconds > 0 ? apiRequestTimeoutSeconds : 10; // Fallback to 10 if not baked or invalid
    }

    /**
     * Bakes the configuration values into static fields.
     */
    public static void bake() {
        apiBaseUrl = API_BASE_URL.get();
        apiRequestTimeoutSeconds = API_REQUEST_TIMEOUT_SECONDS.get();
        // Ensure trailing slash for base URL consistency
        if (apiBaseUrl != null && !apiBaseUrl.endsWith("/")) {
            apiBaseUrl += "/";
        }
    }

    /**
     * Handles the loading of the mod configuration.
     *
     * @param event The mod configuration event.
     */
    @SubscribeEvent
    static void onLoad(final ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            bake();
        }
    }

    /**
     * Handles the reloading of the mod configuration.
     *
     * @param event The mod configuration event.
     */
    @SubscribeEvent
    static void onReload(final ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            bake();
        }
    }
}
