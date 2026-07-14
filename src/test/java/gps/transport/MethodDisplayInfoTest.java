package gps.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import gps.TeleportMethod;
import gps.WorldPointUtil;

/**
 * Every method-type transport must carry display info: the panel's method rows and the direction
 * steps label methods by it, and the fallback is a bare coordinate pair ("Portals (2440, 3089)") —
 * meaningless to a player. Permutation networks inherit the destination row's info, so this
 * effectively requires every direct row and every destination-role row to be named.
 */
public class MethodDisplayInfoTest
{
	@Test
	public void everyMethodTransportIsNamed()
	{
		List<String> unnamed = new ArrayList<>();
		for (Set<Transport> transports : TransportLoader.loadAllFromResources().values())
		{
			for (Transport transport : transports)
			{
				if (!TeleportMethod.isMethodType(transport.getType()))
				{
					continue;
				}
				String info = transport.getDisplayInfo();
				if (info == null || info.isEmpty())
				{
					int d = transport.getDestination();
					unnamed.add(transport.getType() + " -> (" + WorldPointUtil.unpackWorldX(d)
						+ ", " + WorldPointUtil.unpackWorldY(d) + ", " + WorldPointUtil.unpackWorldPlane(d) + ")");
				}
			}
		}
		assertTrue("method transports without display info (would label as coordinates): " + unnamed,
			unnamed.isEmpty());
	}
}
