package gps;

import java.util.Set;
import net.runelite.api.gameval.ObjectID;

/**
 * Maps the furniture object ids seen in a scanned player-owned house to the config declarations
 * they imply. Pure logic over object ids (the plugin supplies them from the live scene), so the
 * mapping is unit-testable.
 * <p>
 * Only furniture with an EXACT 1:1 config mapping is auto-detected — the jewellery box (its tier),
 * fairy ring, spirit tree and obelisk. The "Teleport portals & nexus" and "Mounted items" toggles
 * each bundle several furniture pieces, some with no stable object id (the portal nexus and the
 * functional glory/Xeric's/digsite mounts), so auto-enabling them would claim furniture GPS can't
 * verify; those stay manual.
 */
public final class PohScanner
{
	private PohScanner()
	{
	}

	/** What a house scan found: the present 1:1 features, and the jewellery-box tier. */
	public static final class Detected
	{
		final boolean fairyRing;
		final boolean spiritTree;
		final boolean obelisk;
		final JewelleryBoxTier jewelleryBox;

		Detected(boolean fairyRing, boolean spiritTree, boolean obelisk, JewelleryBoxTier jewelleryBox)
		{
			this.fairyRing = fairyRing;
			this.spiritTree = spiritTree;
			this.obelisk = obelisk;
			this.jewelleryBox = jewelleryBox;
		}

		boolean any()
		{
			return fairyRing || spiritTree || obelisk || jewelleryBox != JewelleryBoxTier.NONE;
		}
	}

	public static Detected detect(Set<Integer> objectIds)
	{
		boolean fairyRing = objectIds.contains(ObjectID.POH_FAIRY_RING)
			|| objectIds.contains(ObjectID.POH_FAIRY_HOUSE)
			|| objectIds.contains(ObjectID.POH_FAIRY_HOUSE_OPEN);
		boolean spiritTree = objectIds.contains(ObjectID.POH_SPIRIT_TREE);
		boolean obelisk = objectIds.contains(ObjectID.POH_WILDERNESS_OBELISK);
		// Tiers are cumulative (each includes the ones below); pick the highest built.
		JewelleryBoxTier box = JewelleryBoxTier.NONE;
		if (objectIds.contains(ObjectID.POH_JEWELLERY_BOX_3))
		{
			box = JewelleryBoxTier.ORNATE;
		}
		else if (objectIds.contains(ObjectID.POH_JEWELLERY_BOX_2))
		{
			box = JewelleryBoxTier.FANCY;
		}
		else if (objectIds.contains(ObjectID.POH_JEWELLERY_BOX_1))
		{
			box = JewelleryBoxTier.BASIC;
		}
		return new Detected(fairyRing, spiritTree, obelisk, box);
	}
}
