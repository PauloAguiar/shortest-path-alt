package gps;

import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import net.runelite.api.gameval.ObjectID;

public class PohScannerTest
{
	@Test
	public void emptyHouseDetectsNothing()
	{
		PohScanner.Detected d = PohScanner.detect(Set.of(1, 2, 3));
		assertFalse(d.any());
		assertEquals(JewelleryBoxTier.NONE, d.jewelleryBox);
	}

	@Test
	public void recognisesEachCleanFeature()
	{
		PohScanner.Detected d = PohScanner.detect(Set.of(
			ObjectID.POH_FAIRY_RING, ObjectID.POH_SPIRIT_TREE, ObjectID.POH_WILDERNESS_OBELISK,
			ObjectID.POH_JEWELLERY_BOX_2));
		assertTrue(d.fairyRing);
		assertTrue(d.spiritTree);
		assertTrue(d.obelisk);
		assertEquals(JewelleryBoxTier.FANCY, d.jewelleryBox);
		assertTrue(d.any());
	}

	@Test
	public void jewelleryBoxTakesTheHighestTierPresent()
	{
		// A house upgraded to ornate may still show the lower box ids; the highest wins.
		assertEquals(JewelleryBoxTier.ORNATE, PohScanner.detect(Set.of(
			ObjectID.POH_JEWELLERY_BOX_1, ObjectID.POH_JEWELLERY_BOX_3)).jewelleryBox);
		assertEquals(JewelleryBoxTier.BASIC, PohScanner.detect(Set.of(
			ObjectID.POH_JEWELLERY_BOX_1)).jewelleryBox);
	}

	@Test
	public void openFairyHouseAlsoCounts()
	{
		assertTrue(PohScanner.detect(Set.of(ObjectID.POH_FAIRY_HOUSE_OPEN)).fairyRing);
	}
}
