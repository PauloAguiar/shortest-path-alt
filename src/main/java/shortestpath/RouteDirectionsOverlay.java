package shortestpath;

import com.google.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Movable "directions" panel for the route currently shown on the map: the numbered steps to follow
 * — walking legs, teleports/transports to use, bank withdrawals and climbs — with live progress from
 * the player's position. Completed steps grey out (and collapse into a summary line), the step being
 * executed is orange (amber once it's nearly finished), the next step is white. Drag it wherever you
 * like (RuneLite overlay editing); hidden when no route is shown or via the config toggle.
 */
public class RouteDirectionsOverlay extends OverlayPanel
{
	private static final int MAX_LINES = 14;
	// The panel grows to fit its widest line between these bounds; only beyond MAX_WIDTH do lines
	// get ellipsized (clipping "Use Rat Pits M…" is worse than a wider panel).
	private static final int MIN_WIDTH = 140;
	private static final int MAX_WIDTH = 280;
	// Component insets plus the gap between the left text and the right-aligned time.
	private static final int PANEL_PADDING = 18;
	/**
	 * How far (in tiles, same plane) the player may stand from a path tile and still count as being
	 * on it — small walking deviations shouldn't stall the progress tracking.
	 */
	private static final int ON_PATH_DISTANCE = 3;

	private static final Color DONE = new Color(0x80, 0x80, 0x80);
	// Map-app navigation blue for the active step; the lighter shade signals "about to end".
	private static final Color CURRENT = new Color(0x4C, 0x8B, 0xF5);
	private static final Color ENDING = new Color(0x8A, 0xB4, 0xF8);
	private static final Color NEXT = Color.WHITE;
	private static final Color UPCOMING = new Color(0xB4, 0xB4, 0xB4);

	// Magnifying-glass effect via the fonts' NATIVE sizes only: the RuneScape fonts are bitmap-style
	// and deform when scaled with deriveFont. Bold (16) > regular (16, lighter) > small.
	private static final Font FONT_CURRENT = FontManager.getRunescapeBoldFont();
	private static final Font FONT_NEXT = FontManager.getRunescapeFont();
	private static final Font FONT_OTHER = FontManager.getRunescapeSmallFont();

	private final Client client;
	private final ShortestPathPlugin plugin;

	// Progress along the displayed route: the path index the player is currently at. Regression is
	// allowed on purpose — walking backwards should un-complete steps and grow the ETA again. When
	// the path crosses itself, the spatial tie is broken towards the current progress, so standing on
	// a crossing doesn't teleport the highlight to the other leg. Reset when the route changes.
	private RouteOption progressRoute;
	private int reachedIndex;
	// Tiles from the player to the nearest path tile when standing off the route; added to the ETA
	// as the walk back to the rejoin point.
	private int offPathDistance;

	@Inject
	public RouteDirectionsOverlay(Client client, ShortestPathPlugin plugin)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.showDirections)
		{
			return null;
		}
		RouteOption route = plugin.getDisplayedRoute();
		if (route == null)
		{
			return null;
		}
		List<RouteDirections.Step> steps = plugin.getRouteDirections(route);
		if (steps.isEmpty())
		{
			return null;
		}
		updateProgress(route);

		// The first step whose span the player hasn't finished yet is the one being executed.
		int current = steps.size() - 1;
		for (int i = 0; i < steps.size(); i++)
		{
			if (steps.get(i).getEndIndex() > reachedIndex)
			{
				current = i;
				break;
			}
		}

		// Remaining wall-time: full estimate before departure, live countdown as progress advances
		// (the current step contributes only its unfinished fraction). Standing off the route adds
		// the run back to the rejoin point, so straying visibly costs time.
		double remainingTicks = offPathDistance > 0 ? (offPathDistance + 1) / 2.0 : 0;
		for (int s = current; s < steps.size(); s++)
		{
			RouteDirections.Step step = steps.get(s);
			if (s == current)
			{
				int span = Math.max(1, step.getEndIndex() - step.getStartIndex());
				int left = Math.max(0, step.getEndIndex() - Math.max(reachedIndex, step.getStartIndex()));
				remainingTicks += step.getTicks() * (left / (double) span);
			}
			else
			{
				remainingTicks += step.getTicks();
			}
		}

		String etaText = "ETA: " + formatTime((int) Math.ceil(remainingTicks * RouteDirections.SECONDS_PER_TICK));

		// First pass: collect the lines, then size the panel to the widest one (within bounds) so
		// content is neither wrapped nor needlessly clipped; only lines beyond MAX_WIDTH truncate.
		List<Line> lines = new ArrayList<>();
		String source = plugin.getTargetSource();
		if (source != null)
		{
			lines.add(new Line("Destination set by " + source, FONT_OTHER, UPCOMING, null, null));
		}

		// Window: collapse all but the most recent completed step into one summary line, then show
		// the current step and what follows, capped.
		int windowStart = Math.max(0, current - 1);
		if (windowStart > 0)
		{
			lines.add(new Line("✓ " + windowStart + (windowStart == 1 ? " step done" : " steps done"),
				FONT_OTHER, DONE, null, null));
		}
		int shown = 0;
		int i = windowStart;
		for (; i < steps.size() && shown < MAX_LINES; i++, shown++)
		{
			RouteDirections.Step step = steps.get(i);
			String text = (i + 1) + ". " + step.getText();
			Color colour;
			Font font;
			if (i < current)
			{
				// The RuneScape fonts carry no U+2713 glyph, but the JVM's font pipeline substitutes
				// one from a system font (verified in-game on Windows). If reports of missing-glyph
				// boxes come in from other platforms, drop the prefix — the grey colour already
				// marks completion on its own.
				text = "✓ " + text;
				colour = DONE;
				font = FONT_OTHER;
			}
			else if (i == current)
			{
				colour = nearEnd(step) ? ENDING : CURRENT;
				font = FONT_CURRENT;
			}
			else if (i == current + 1)
			{
				colour = NEXT;
				font = FONT_NEXT;
			}
			else
			{
				colour = UPCOMING;
				font = FONT_OTHER;
			}
			lines.add(new Line(text, font, colour,
				formatTime((int) Math.ceil(step.getTicks() * RouteDirections.SECONDS_PER_TICK)), colour));
		}
		if (i < steps.size())
		{
			lines.add(new Line("… " + (steps.size() - i) + " more", FONT_OTHER, DONE, null, null));
		}

		// Fit the panel to the content: widest left text + its time column, clamped to sane bounds.
		int contentWidth = MIN_WIDTH;
		for (Line line : lines)
		{
			int width = graphics.getFontMetrics(line.font).stringWidth(line.left)
				+ rightWidth(graphics, line) + PANEL_PADDING;
			contentWidth = Math.max(contentWidth, width);
		}
		int panelWidth = Math.min(contentWidth, MAX_WIDTH);
		panelComponent.setPreferredSize(new Dimension(panelWidth, 0));

		// Spacer reserving the header row; the decorated title (pin glyph + bold text + accent rule)
		// is custom-drawn over it after the panel renders — TitleComponent supports no font/styling.
		panelComponent.getChildren().add(
			LineComponent.builder()
				.left(" ")
				.leftFont(FONT_CURRENT)
				.build());
		for (Line line : lines)
		{
			LineComponent.LineComponentBuilder builder = LineComponent.builder()
				.left(ellipsize(graphics, line.font, line.left, panelWidth - rightWidth(graphics, line) - PANEL_PADDING))
				.leftColor(line.colour)
				.leftFont(line.font);
			if (line.right != null)
			{
				builder.right(line.right)
					.rightColor(line.rightColour)
					.rightFont(line.font == FONT_CURRENT ? FONT_NEXT : FONT_OTHER);
			}
			panelComponent.getChildren().add(builder.build());
		}

		Dimension dimension = super.render(graphics);
		if (dimension != null)
		{
			drawTitle(graphics, dimension);
			drawEtaBadge(graphics, dimension, etaText);
		}
		return dimension;
	}

	/**
	 * Decorated header drawn over the reserved top row: a small location-pin glyph, bold "GPS", and
	 * a navigation-blue rule under the header separating it from the steps.
	 */
	private void drawTitle(Graphics2D graphics, Dimension panelSize)
	{
		// Location pin: round head with a tail, hollow centre.
		final int px = 8;
		final int py = 4;
		graphics.setColor(CURRENT);
		graphics.fillOval(px, py, 9, 9);
		Polygon tail = new Polygon(
			new int[]{px + 1, px + 8, px + 4},
			new int[]{py + 7, py + 7, py + 13},
			3);
		graphics.fillPolygon(tail);
		graphics.setColor(new Color(0x10, 0x10, 0x10));
		graphics.fillOval(px + 3, py + 3, 3, 3);

		graphics.setFont(FONT_CURRENT);
		graphics.setColor(Color.BLACK);
		graphics.drawString("GPS", px + 14, py + 12);
		graphics.setColor(Color.WHITE);
		graphics.drawString("GPS", px + 13, py + 11);

		// Accent rule under the header row.
		graphics.setColor(new Color(CURRENT.getRed(), CURRENT.getGreen(), CURRENT.getBlue(), 170));
		graphics.drawLine(4, py + 15, panelSize.width - 4, py + 15);
	}

	/**
	 * The ETA as a floating pill overlapping the panel's top-right corner — a badge rather than a
	 * list row, map-app style.
	 */
	private void drawEtaBadge(Graphics2D graphics, Dimension panelSize, String etaText)
	{
		java.awt.FontMetrics metrics = graphics.getFontMetrics(FONT_OTHER);
		int width = metrics.stringWidth(etaText) + 12;
		int height = metrics.getHeight() + 4;
		// Slight outset past the panel edge so the badge reads as sitting on top of it.
		int x = panelSize.width - width + 6;
		int y = 1;

		graphics.setColor(CURRENT);
		graphics.fillRoundRect(x, y, width, height, 8, 8);
		graphics.setColor(new Color(0x2A, 0x5C, 0xC4));
		graphics.drawRoundRect(x, y, width, height, 8, 8);
		graphics.setFont(FONT_OTHER);
		graphics.setColor(Color.BLACK);
		graphics.drawString(etaText, x + 7, y + height - 5);
		graphics.setColor(Color.WHITE);
		graphics.drawString(etaText, x + 6, y + height - 6);
	}

	/**
	 * One pending panel line: collected first so the panel can be sized to the widest line before
	 * any component is built.
	 */
	private static final class Line
	{
		private final String left;
		private final Font font;
		private final Color colour;
		private final String right;
		private final Color rightColour;

		private Line(String left, Font font, Color colour, String right, Color rightColour)
		{
			this.left = left;
			this.font = font;
			this.colour = colour;
			this.right = right;
			this.rightColour = rightColour;
		}
	}

	private static int rightWidth(Graphics2D graphics, Line line)
	{
		return line.right == null ? 0 : graphics.getFontMetrics(FONT_OTHER).stringWidth(line.right) + 6;
	}

	/**
	 * Compact wall-time: "34s" under a minute, "2m 05s" above.
	 */
	private static String formatTime(int seconds)
	{
		if (seconds < 60)
		{
			return seconds + "s";
		}
		return (seconds / 60) + "m " + String.format("%02ds", seconds % 60);
	}

	/**
	 * Truncates a line to {@code maxWidth} instead of letting LineComponent wrap it — wrapped steps
	 * blur into their neighbours and break the list rhythm. With the panel sized to its content this
	 * only kicks in past {@link #MAX_WIDTH}.
	 */
	private static String ellipsize(Graphics2D graphics, Font font, String text, int maxWidth)
	{
		java.awt.FontMetrics metrics = graphics.getFontMetrics(font);
		if (metrics.stringWidth(text) <= maxWidth)
		{
			return text;
		}
		int end = text.length();
		while (end > 1 && metrics.stringWidth(text.substring(0, end) + "…") > maxWidth)
		{
			end--;
		}
		return text.substring(0, end) + "…";
	}

	/**
	 * Whether the current step is nearly finished: within the last fifth (or last 3 tiles) of its
	 * span. Only meaningful for longer spans — short steps (a teleport edge) flip states quickly
	 * anyway.
	 */
	private boolean nearEnd(RouteDirections.Step step)
	{
		int span = step.getEndIndex() - step.getStartIndex();
		if (span < 8)
		{
			return false;
		}
		int remaining = step.getEndIndex() - reachedIndex;
		return remaining <= Math.max(3, span / 5);
	}

	/**
	 * Moves the progress marker to the path tile nearest the player (same plane). Spatial ties are
	 * broken towards the current progress, so standing where the path crosses itself keeps the
	 * highlight on the leg being travelled; genuine backtracking moves it (and the ETA) backwards.
	 * When the player is more than {@link #ON_PATH_DISTANCE} tiles off the route, the marker snaps
	 * to the nearest rejoin point and the detour distance is charged onto the ETA.
	 */
	private void updateProgress(RouteOption route)
	{
		if (route != progressRoute)
		{
			progressRoute = route;
			reachedIndex = 0;
			offPathDistance = 0;
		}
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		int playerPacked = WorldPointUtil.fromLocalInstance(client, player.getLocalLocation());
		int playerPlane = WorldPointUtil.unpackWorldPlane(playerPacked);

		List<shortestpath.pathfinder.PathStep> path = route.getPath();
		int best = -1;
		int bestDistance = Integer.MAX_VALUE;
		for (int i = 0; i < path.size(); i++)
		{
			int packed = path.get(i).getPackedPosition();
			if (WorldPointUtil.unpackWorldPlane(packed) != playerPlane)
			{
				continue;
			}
			int distance = WorldPointUtil.distanceBetween(packed, playerPacked);
			if (distance < bestDistance
				|| (distance == bestDistance && best >= 0
					&& Math.abs(i - reachedIndex) < Math.abs(best - reachedIndex)))
			{
				bestDistance = distance;
				best = i;
			}
		}
		if (best < 0)
		{
			// Different plane and nothing matches (e.g. mid-dungeon detour): freeze progress.
			return;
		}
		reachedIndex = best;
		offPathDistance = bestDistance > ON_PATH_DISTANCE ? bestDistance : 0;
	}
}
