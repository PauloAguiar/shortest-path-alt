package shortestpath;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportType;

/**
 * The GPS search index: named places and curated amenities (bank, altar, water source,
 * furnace, ...) a player can navigate to by name or as "nearest X". The bulk is a bundled
 * resource dumped from the cache (destinations.tsv); fairy rings and spirit trees are added
 * from the live transport data because their world objects carry no name in the cache.
 */
@Slf4j
public final class Destinations
{
	private static final String RESOURCE_PATH = "/destinations.tsv";

	/** One searchable destination: a category, a display name, and a packed world tile. */
	public static final class Entry
	{
		public final String category;
		public final String name;
		public final int packedPosition;

		Entry(String category, String name, int packedPosition)
		{
			this.category = category;
			this.name = name;
			this.packedPosition = packedPosition;
		}
	}

	/** A "nearest X" amenity category offered as a quick option: its id and its display label. */
	public static final class NearestOption
	{
		public final String id;
		public final String label;

		NearestOption(String id, String label)
		{
			this.id = id;
			this.label = label;
		}
	}

	/**
	 * The amenity categories offered as "nearest X" options, in display order. Picking one routes
	 * to ALL tiles of that category at once — the alternative-routes engine then ranks the shortest
	 * paths (using available teleports), which may lead to different sites.
	 */
	public static final List<NearestOption> NEAREST_OPTIONS = List.of(
		new NearestOption("bank", "Bank"),
		new NearestOption("altar", "Altar"),
		new NearestOption("water", "Water source"),
		new NearestOption("furnace", "Furnace"),
		new NearestOption("anvil", "Anvil"),
		new NearestOption("range", "Cooking range"),
		new NearestOption("spinning_wheel", "Spinning wheel"),
		new NearestOption("pottery", "Potter's wheel"),
		new NearestOption("fairy_ring", "Fairy ring"),
		new NearestOption("spirit_tree", "Spirit tree"));

	/** The named places (cities/towns/landmarks) from the bundled resource. */
	public static List<Entry> places()
	{
		List<Entry> places = new ArrayList<>();
		for (Entry entry : resourceEntries())
		{
			if ("place".equals(entry.category))
			{
				places.add(entry);
			}
		}
		return places;
	}

	/**
	 * The entries offered by the name search: named places and dungeons (from the bundled resource)
	 * plus minigames (from the live transport data). Amenities like banks and altars are reached via
	 * "nearest X" instead, so they're left out here to keep results focused.
	 */
	public static List<Entry> searchable(PrimitiveIntHashMap<Transport[]> transports)
	{
		List<Entry> out = new ArrayList<>();
		for (Entry entry : all(transports))
		{
			if ("place".equals(entry.category) || "landmark".equals(entry.category)
				|| "dungeon".equals(entry.category) || "minigame".equals(entry.category))
			{
				out.add(entry);
			}
		}
		return out;
	}

	/**
	 * Every tile of an amenity category plus each tile's walkable perimeter — the target set for its
	 * "nearest X" search. Fairy rings and spirit trees come from the live transport data (their world
	 * objects carry no cache name).
	 */
	public static Set<Integer> tilesForCategory(String category, PrimitiveIntHashMap<Transport[]> transports)
	{
		Set<Integer> tiles = new HashSet<>();
		boolean transportBacked = "fairy_ring".equals(category) || "spirit_tree".equals(category);
		for (Entry entry : transportBacked ? all(transports) : resourceEntries())
		{
			if (category.equals(entry.category))
			{
				addWithPerimeter(tiles, entry.packedPosition);
			}
		}
		return tiles;
	}

	/**
	 * Adds a destination tile and its 8 neighbours to the target set. Amenity destinations are the
	 * OBJECT's own tile (a bank booth, an anvil, an altar), which is usually not walkable — a search
	 * targeting only that tile can never settle it, so it explores the entire reachable map and falls
	 * back to a closest-tile path (measured: ~1.3M nodes per search, and the walk-cost cap never
	 * engages because nothing ever counts as reached). With the perimeter in the target set the
	 * search terminates the moment it settles a tile the player could interact from, and the
	 * reached/cost-cap machinery works. Unwalkable perimeter tiles are harmless: never settled.
	 */
	public static void addWithPerimeter(Set<Integer> tiles, int packed)
	{
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				tiles.add(WorldPointUtil.dxdy(packed, dx, dy));
			}
		}
	}

	/**
	 * The target set for an arbitrary single tile (a map pin, a Quest Helper NPC tile): the tile
	 * itself when it's walkable — exact semantics preserved — otherwise the tile plus the nearest
	 * ring of walkable tiles around it (radius up to {@code MAX_WALKABLE_RING}). A pin dropped on a
	 * fence, a piece of furniture or an NPC's own tile can never be settled by the search, which
	 * otherwise explores the entire map and falls back to a closest-tile path (the same pathology
	 * the amenity perimeter fixes). If no walkable tile exists within range (a pin mid-pond), the
	 * original tile is returned alone and the search keeps the old closest-tile behaviour.
	 */
	public static Set<Integer> walkableTargets(shortestpath.pathfinder.CollisionMap map, int packed)
	{
		final int x = WorldPointUtil.unpackWorldX(packed);
		final int y = WorldPointUtil.unpackWorldY(packed);
		final int plane = WorldPointUtil.unpackWorldPlane(packed);
		if (map == null || !map.isBlocked(x, y, plane))
		{
			return Set.of(packed);
		}
		for (int radius = 1; radius <= MAX_WALKABLE_RING; radius++)
		{
			Set<Integer> ring = new HashSet<>();
			for (int dx = -radius; dx <= radius; dx++)
			{
				for (int dy = -radius; dy <= radius; dy++)
				{
					if (Math.max(Math.abs(dx), Math.abs(dy)) != radius)
					{
						continue;
					}
					if (!map.isBlocked(x + dx, y + dy, plane))
					{
						ring.add(WorldPointUtil.packWorldPoint(x + dx, y + dy, plane));
					}
				}
			}
			if (!ring.isEmpty())
			{
				ring.add(packed);
				return ring;
			}
		}
		return Set.of(packed);
	}

	private static final int MAX_WALKABLE_RING = 5;

	private static volatile List<Entry> resourceEntries;

	private Destinations()
	{
	}

	/** The static place + amenity entries from the bundled resource (loaded once). */
	public static List<Entry> resourceEntries()
	{
		List<Entry> snapshot = resourceEntries;
		if (snapshot == null)
		{
			synchronized (Destinations.class)
			{
				snapshot = resourceEntries;
				if (snapshot == null)
				{
					snapshot = load();
					resourceEntries = snapshot;
				}
			}
		}
		return snapshot;
	}

	/**
	 * The static entries plus destinations derived from the live transport data: one per fairy ring
	 * and spirit tree (deduplicated by origin — these objects have no cache name, so they're named by
	 * their transport display info like "Fairy ring AIQ"), and one per minigame (deduplicated by name,
	 * placed at the minigame's teleport destination and named from its display info, e.g. "Castle Wars").
	 */
	public static List<Entry> all(PrimitiveIntHashMap<Transport[]> transports)
	{
		List<Entry> entries = new ArrayList<>(resourceEntries());
		if (transports == null)
		{
			return entries;
		}
		PrimitiveIntList seen = new PrimitiveIntList();
		Set<String> seenMinigames = new HashSet<>();
		for (int key : transports.keys())
		{
			Transport[] set = transports.get(key);
			if (set == null)
			{
				continue;
			}
			for (Transport transport : set)
			{
				TransportType type = transport.getType();
				if (type == TransportType.FAIRY_RING || type == TransportType.SPIRIT_TREE)
				{
					int origin = transport.getOrigin();
					if (origin == Transport.UNDEFINED_ORIGIN || seen.contains(origin))
					{
						continue;
					}
					seen.add(origin);
					String label = type == TransportType.FAIRY_RING ? "Fairy ring" : "Spirit tree";
					String info = transport.getDisplayInfo();
					String name = info != null && !info.isEmpty() ? label + " " + info : label;
					entries.add(new Entry(type == TransportType.FAIRY_RING ? "fairy_ring" : "spirit_tree",
						name, origin));
				}
				else if (type == TransportType.TELEPORTATION_MINIGAME)
				{
					int destination = transport.getDestination();
					String name = minigameName(transport.getDisplayInfo());
					if (destination == WorldPointUtil.UNDEFINED
						|| !seenMinigames.add(name.toLowerCase(java.util.Locale.ROOT)))
					{
						continue;
					}
					entries.add(new Entry("minigame", name, destination));
				}
			}
		}
		return entries;
	}

	/**
	 * A clean minigame label from a minigame-teleport's display info: drops the "Minigame Teleport"
	 * boilerplate and the numbered-variant prefix, e.g. "Castle Wars Minigame Teleport" -&gt;
	 * "Castle Wars", and "Rat Pits Minigame Teleport: 1. Ardougne" -&gt; "Rat Pits: Ardougne".
	 */
	private static String minigameName(String info)
	{
		if (info == null || info.isEmpty())
		{
			return "Minigame";
		}
		String name = info.replace("Minigame Teleport", " ").replaceAll("\\s+", " ").trim();
		name = name.replace(" :", ":").replaceAll(":\\s*\\d+\\.\\s*", ": ");
		return name.trim();
	}

	private static List<Entry> load()
	{
		try (InputStream in = ShortestPathPlugin.class.getResourceAsStream(RESOURCE_PATH))
		{
			if (in == null)
			{
				log.warn("Destinations resource not found at {}; search disabled", RESOURCE_PATH);
				return new ArrayList<>();
			}
			return parse(new String(Util.readAllBytes(in), StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.error("Failed to load destinations from {}", RESOURCE_PATH, e);
			return new ArrayList<>();
		}
	}

	private static List<Entry> parse(String tsv)
	{
		List<Entry> entries = new ArrayList<>();
		boolean header = true;
		for (String line : tsv.split("\\R"))
		{
			if (header)
			{
				header = false;
				continue;
			}
			if (line.isEmpty())
			{
				continue;
			}
			// category, name, x, y, plane
			String[] fields = line.split("\t");
			if (fields.length < 5)
			{
				continue;
			}
			try
			{
				int packed = WorldPointUtil.packWorldPoint(
					Integer.parseInt(fields[2]), Integer.parseInt(fields[3]), Integer.parseInt(fields[4]));
				entries.add(new Entry(fields[0], fields[1], packed));
			}
			catch (NumberFormatException e)
			{
				log.warn("Skipping malformed destination row: '{}'", line);
			}
		}
		return entries;
	}
}
