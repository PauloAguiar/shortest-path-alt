package shortestpath;

/**
 * Which teleport/transport methods the alternative-routes feature considers when searching.
 */
public enum AlternativeRoutesMode
{
	/**
	 * Follows the user's Shortest Path teleport-item / bank-path config unchanged (the original
	 * "Available" behaviour) — no forcing applied by the alternative-routes feature.
	 */
	AVAILABLE,
	/**
	 * As {@link #AVAILABLE}, plus teleport items stored in the bank — routing through a bank to pick
	 * them up (mirrors Shortest Path's {@code INVENTORY_AND_BANK} + {@code includeBankPath}).
	 */
	AVAILABLE_WITH_BANK,
	/**
	 * Every teleport in the game, regardless of whether the player owns it (planning mode).
	 */
	ALL_TELEPORTS
}
