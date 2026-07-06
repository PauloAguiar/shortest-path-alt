package shortestpath;

import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.coords.LocalPoint;

/**
 * Live-scene object queries shared by the overlays, mainly the "is this door still closed"
 * check: a door is closed exactly while its closed-variant object id stands on the tile, and
 * opening it swaps the object out, so presence is the whole test.
 */
public class SceneObjects
{
	private SceneObjects()
	{
	}

	/**
	 * Whether an object with this id currently stands on the tile (wall objects cover doors;
	 * gates and double doors can be game objects). Only tiles inside the loaded scene can
	 * match — which is fine for hints, they matter when the player is close enough to see the
	 * object.
	 */
	public static boolean objectPresent(Client client, int packedLocation, int objectId)
	{
		PrimitiveIntList points = WorldPointUtil.toLocalInstance(client, packedLocation);
		for (int i = 0; i < points.size(); i++)
		{
			LocalPoint lp = WorldPointUtil.toLocalPoint(client, points.get(i));
			if (lp == null)
			{
				continue;
			}
			int plane = WorldPointUtil.unpackWorldPlane(points.get(i));
			Tile[][][] tiles = client.getTopLevelWorldView().getScene().getTiles();
			if (plane < 0 || plane >= tiles.length)
			{
				continue;
			}
			int sceneX = lp.getSceneX();
			int sceneY = lp.getSceneY();
			if (sceneX < 0 || sceneY < 0 || sceneX >= tiles[plane].length || sceneY >= tiles[plane][sceneX].length)
			{
				continue;
			}
			Tile tile = tiles[plane][sceneX][sceneY];
			if (tile == null)
			{
				continue;
			}
			WallObject wall = tile.getWallObject();
			if (wall != null && wall.getId() == objectId)
			{
				return true;
			}
			GameObject[] gameObjects = tile.getGameObjects();
			if (gameObjects != null)
			{
				for (GameObject gameObject : gameObjects)
				{
					if (gameObject != null && gameObject.getId() == objectId)
					{
						return true;
					}
				}
			}
		}
		return false;
	}
}
