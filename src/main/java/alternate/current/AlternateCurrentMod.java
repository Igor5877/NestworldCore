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

	/** 2026-08-31: diagnostic toggle (default unchanged, still true) -- lets us A/B whether AC's
	 *  WireHandler is the source of a suspected diode-block (repeater/comparator) update loss,
	 *  isolated from NestWorldCore's free-running scheduled-tick handoff, without a code revert
	 *  between test runs. See net.nestworld.region.NestworldRedstone. */
	public static boolean on = !Boolean.getBoolean("nestworld.disableAlternateCurrent");
}
