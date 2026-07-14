package gps.transport;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import gps.TeleportMethod;
import gps.WorldPointUtil;

/**
 * Mountain-guide rows must carry their DESTINATION as display info — the data used to repeat the
 * menu text ("Travel Mountain Guide") on every row, so route cards and direction steps could not
 * say where the guide goes, and identical labels collapsed the catalog entries' meaning.
 */
public class MountainGuideLabelTest
{
	@Test
	public void everyGuideRouteNamesItsDestination()
	{
		Map<Integer, String> expected = new HashMap<>();
		expected.put(WorldPointUtil.packWorldPoint(1275, 3559, 0), "Mount Quidamortem");
		expected.put(WorldPointUtil.packWorldPoint(1272, 3475, 0), "South of Quidamortem");
		expected.put(WorldPointUtil.packWorldPoint(1401, 3536, 0), "Shayzien Outpost");
		expected.put(WorldPointUtil.packWorldPoint(1361, 3309, 0), "Auburnvale");
		expected.put(WorldPointUtil.packWorldPoint(1486, 3232, 0), "Quetzacalli Gorge");

		int guides = 0;
		for (Set<Transport> transports : TransportLoader.loadAllFromResources().values())
		{
			for (Transport transport : transports)
			{
				if (!TransportType.MOUNTAIN_GUIDE.equals(transport.getType()))
				{
					continue;
				}
				guides++;
				String info = transport.getDisplayInfo();
				assertFalse("display info must not be the menu text: " + info,
					info == null || info.contains("Mountain Guide"));
				assertEquals("display info must name the destination",
					expected.get(transport.getDestination()), info);
				assertEquals("the route label must name the vehicle",
					"Mountain guide to " + info, TeleportMethod.fromTransport(transport).routeLabel());
			}
		}
		assertTrue("the mountain-guide network must load (got " + guides + " routes)", guides >= 8);
	}
}
