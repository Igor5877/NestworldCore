package alternate.current;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Vendored from Alternate Current 1.9.0 (MC 1.20 branch) by Space Walker,
 * MIT licensed — see the LICENSE file in this package. The Fabric entrypoint
 * and debug profiler were dropped; NestworldCore wires the handler into
 * ServerLevel and RedStoneWireBlock via Forge patches instead of mixins.
 */
public class AlternateCurrentMod {

	public static final String MOD_ID = "alternate-current";
	public static final String MOD_NAME = "Alternate Current";
	public static final String MOD_VERSION = "1.9.0";
	public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

	public static boolean on = true;
}
