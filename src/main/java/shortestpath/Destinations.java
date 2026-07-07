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

	/** The named places (cities/towns) — the only entries offered by the text search. */
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
	 * Every tile of an amenity category — the target set for its "nearest X" search. Fairy rings
	 * and spirit trees come from the live transport data (their world objects carry no cache name).
	 */
	public static Set<Integer> tilesForCategory(String category, PrimitiveIntHashMap<Transport[]> transports)
	{
		Set<Integer> tiles = new HashSet<>();
		boolean transportBacked = "fairy_ring".equals(category) || "spirit_tree".equals(category);
		for (Entry entry : transportBacked ? all(transports) : resourceEntries())
		{
			if (category.equals(entry.category))
			{
				tiles.add(entry.packedPosition);
			}
		}
		return tiles;
	}

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
	 * The static entries plus one entry per fairy ring and spirit tree, derived from the live
	 * transport data (deduplicated by origin). These objects have no cache name, so the plugin
	 * names them by their transport display info ("Fairy ring AIQ", "Spirit tree: Gnome Stronghold").
	 */
	public static List<Entry> all(PrimitiveIntHashMap<Transport[]> transports)
	{
		List<Entry> entries = new ArrayList<>(resourceEntries());
		if (transports == null)
		{
			return entries;
		}
		PrimitiveIntList seen = new PrimitiveIntList();
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
				if (type != TransportType.FAIRY_RING && type != TransportType.SPIRIT_TREE)
				{
					continue;
				}
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
		}
		return entries;
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
