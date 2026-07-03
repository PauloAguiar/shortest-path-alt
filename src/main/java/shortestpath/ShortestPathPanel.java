package shortestpath;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The "view": lists up to {@link AlternativeRoutesService#MAX_ROUTES} alternative routes to the
 * target, then — below them — the full catalog of teleport/transport methods for the current mode,
 * grouped into collapsible categories with per-method and per-category include/exclude toggles.
 * <p>
 * The route cards and the catalog share one exclusion set: the ✕ on a route's method and the
 * check/cross in the catalog flip the same state. Clicking a route card shows it on the world map.
 * Built on the tile-packs style: small icon controls with hover states and tooltips.
 */
public class ShortestPathPanel extends PluginPanel
{
	private static final int CONTROL_SIZE = 18;
	private static final int METHOD_TEXT_WIDTH = 132;

	// Stable, distinct-ish palette; categories hash into it so the same category always gets the
	// same dot colour.
	private static final Color[] CATEGORY_PALETTE =
	{
		new Color(0x5B, 0x9B, 0xD5), // blue
		new Color(0x4C, 0xAF, 0x50), // green
		new Color(0xE9, 0x7D, 0x3B), // orange
		new Color(0xB4, 0x6F, 0xD4), // purple
		new Color(0x4D, 0xB6, 0xAC), // teal
		new Color(0xE5, 0x73, 0x99), // pink
		new Color(0xC0, 0xA8, 0x3B), // gold
		new Color(0x7E, 0x8C, 0x9A), // slate
		new Color(0x8B, 0xC3, 0x4A), // lime
		new Color(0xD1, 0x5B, 0x5B), // red
	};

	private final ShortestPathPlugin plugin;
	private final JLabel statusLabel = new JLabel();
	private final JPanel listPanel = new JPanel();
	private JButton availableButton;
	private JButton bankButton;
	private JButton allButton;

	// Cached last render input so expand/collapse can re-render without a round-trip to the plugin.
	private List<RouteOption> cachedRoutes = List.of();
	private List<TeleportMethod> cachedCatalog = List.of();
	private Set<TeleportMethod> cachedExclusions = Set.of();
	private boolean cachedCalculating = false;
	private boolean cachedHasTarget = false;
	private final Set<String> expandedCategories = new HashSet<>();

	public ShortestPathPanel(ShortestPathPlugin plugin)
	{
		super(false);
		this.plugin = plugin;
		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(buildHeader(), BorderLayout.NORTH);

		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// Top-anchor the content so a short list keeps each row at its natural height.
		JPanel listWrapper = new JPanel(new BorderLayout());
		listWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		listWrapper.add(listPanel, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(listWrapper,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);

		render();
	}

	private JPanel buildHeader()
	{
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(new EmptyBorder(0, 0, 8, 0));

		JPanel titleRow = new JPanel(new BorderLayout());
		titleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("Alternative routes");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		titleRow.add(title, BorderLayout.WEST);

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
		actions.setBackground(ColorScheme.DARK_GRAY_COLOR);
		actions.add(control(new IconActionLabel(RouteIcons.CLEAR, RouteIcons.CLEAR_HOVER,
			"Re-include all excluded methods", plugin::clearExclusions)));
		titleRow.add(actions, BorderLayout.EAST);

		header.add(titleRow, BorderLayout.NORTH);

		JPanel bottom = new JPanel(new BorderLayout());
		bottom.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel modeRow = new JPanel(new GridLayout(1, 3, 4, 0));
		modeRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		modeRow.setBorder(new EmptyBorder(8, 0, 0, 0));
		availableButton = new JButton("Available");
		availableButton.setToolTipText("Same as before — follows your Shortest Path teleport-item settings");
		availableButton.setFocusPainted(false);
		availableButton.addActionListener(e -> plugin.setRoutesMode(AlternativeRoutesMode.AVAILABLE));
		bankButton = new JButton("Bank");
		bankButton.setToolTipText("Only teleports you own — inventory, equipment and bank — routing through a bank to grab bank items");
		bankButton.setFocusPainted(false);
		bankButton.addActionListener(e -> plugin.setRoutesMode(AlternativeRoutesMode.AVAILABLE_WITH_BANK));
		allButton = new JButton("All");
		allButton.setToolTipText("Every teleport in the game, even ones you don't own");
		allButton.setFocusPainted(false);
		allButton.addActionListener(e -> plugin.setRoutesMode(AlternativeRoutesMode.ALL_TELEPORTS));
		modeRow.add(availableButton);
		modeRow.add(bankButton);
		modeRow.add(allButton);
		bottom.add(modeRow, BorderLayout.NORTH);

		JPanel lower = new JPanel(new BorderLayout());
		lower.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JButton findButton = new JButton("Find routes to target");
		findButton.setToolTipText("Calculate alternative routes to the destination Shortest Path is currently set to");
		findButton.setFocusPainted(false);
		findButton.addActionListener(e -> plugin.recomputeAlternatives());
		JPanel findWrap = new JPanel(new BorderLayout());
		findWrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		findWrap.setBorder(new EmptyBorder(8, 0, 0, 0));
		findWrap.add(findButton, BorderLayout.CENTER);
		lower.add(findWrap, BorderLayout.NORTH);

		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setBorder(new EmptyBorder(6, 0, 0, 0));
		lower.add(statusLabel, BorderLayout.SOUTH);

		bottom.add(lower, BorderLayout.SOUTH);

		header.add(bottom, BorderLayout.SOUTH);

		updateModeButtons();
		return header;
	}

	/**
	 * Stores the latest data and re-renders. Must be called on the Swing EDT.
	 */
	public void displayRoutes(List<RouteOption> routes, List<TeleportMethod> catalog,
		Set<TeleportMethod> exclusions, boolean calculating, boolean hasTarget)
	{
		cachedRoutes = routes != null ? routes : List.of();
		cachedCatalog = catalog != null ? catalog : List.of();
		cachedExclusions = exclusions != null ? exclusions : Set.of();
		cachedCalculating = calculating;
		cachedHasTarget = hasTarget;
		render();
	}

	private void render()
	{
		updateModeButtons();
		listPanel.removeAll();

		if (cachedCalculating)
		{
			statusLabel.setText(cachedRoutes.isEmpty()
				? "Calculating routes…"
				: ("Calculating… (" + cachedRoutes.size() + " so far)"));
		}
		else if (!cachedRoutes.isEmpty())
		{
			statusLabel.setText(cachedRoutes.size() + (cachedRoutes.size() == 1 ? " route found" : " routes found"));
		}
		else if (cachedHasTarget)
		{
			// A search ran for the current target but produced nothing — distinct from "no target set".
			statusLabel.setText("<html>No routes found to the target."
				+ (plugin.getRoutesMode() == AlternativeRoutesMode.ALL_TELEPORTS ? "" : "<br>Try the \"Bank\" or \"All\" mode.")
				+ "</html>");
		}
		else
		{
			// Shortest Path has no active target. (Quest Helper draws its own line for some steps and
			// doesn't hand Shortest Path a destination — set one on the map to find routes.)
			statusLabel.setText("<html>No Shortest Path destination set."
				+ "<br>Quest Helper draws its own line for some steps."
				+ "<br>Set a target on the map, then press \"Find routes\".</html>");
		}

		// Routes are shown as they stream in (even while still calculating). The previous list was
		// cleared when this generation started, so only the new routes appear.
		RouteOption selected = plugin.getSelectedRoute();
		for (int i = 0; i < cachedRoutes.size(); i++)
		{
			listPanel.add(buildRouteCard(i, cachedRoutes.get(i), cachedRoutes.get(i) == selected));
			listPanel.add(verticalGap(4));
		}
		// Only offer "show more" once this generation has finished.
		if (!cachedCalculating && !cachedRoutes.isEmpty() && plugin.canLoadMoreRoutes())
		{
			listPanel.add(buildShowMoreButton());
			listPanel.add(verticalGap(4));
		}

		if (!cachedCatalog.isEmpty())
		{
			listPanel.add(buildCatalogSection());
		}

		listPanel.revalidate();
		listPanel.repaint();
	}

	private JPanel buildShowMoreButton()
	{
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		JButton more = new JButton("Show 5 more routes");
		more.setFocusPainted(false);
		more.setToolTipText("Search for 5 more alternative routes");
		more.addActionListener(e -> plugin.loadMoreRoutes());
		wrap.add(more, BorderLayout.CENTER);
		return wrap;
	}

	private JPanel buildRouteCard(int index, RouteOption route, boolean selected)
	{
		JPanel card = new JPanel(new BorderLayout());
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createLineBorder(
			selected ? ColorScheme.BRAND_ORANGE : ColorScheme.MEDIUM_GRAY_COLOR));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

		JPanel topRow = new JPanel(new BorderLayout());
		topRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		topRow.setBorder(new EmptyBorder(3, 6, 3, 4));

		JLabel name = new JLabel("Route " + (index + 1)
			+ (route.isWalkOnly() ? " · walk" : "")
			+ (route.isReached() ? "" : " · closest"));
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(selected ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		if (!route.isReached())
		{
			name.setToolTipText("The exact target isn't reachable — this ends at the closest reachable tile");
		}
		topRow.add(name, BorderLayout.WEST);

		JPanel right = new JPanel(new FlowLayout(FlowLayout.TRAILING, 5, 0));
		right.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JLabel cost = new JLabel("≈ " + route.getTotalCost());
		cost.setFont(FontManager.getRunescapeSmallFont());
		cost.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		cost.setToolTipText("Blended cost: walk distance + teleport ticks/penalties (lower is shorter)");
		right.add(cost);
		// Status indicator (orange when shown); the whole card is the click target, see makeSelectable.
		right.add(control(new JLabel(selected ? RouteIcons.SHOW_ACTIVE : RouteIcons.SHOW)));
		topRow.add(right, BorderLayout.EAST);
		card.add(topRow, BorderLayout.NORTH);

		JPanel methods = new JPanel();
		methods.setLayout(new BoxLayout(methods, BoxLayout.Y_AXIS));
		methods.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		methods.setBorder(new EmptyBorder(2, 6, 4, 4));
		if (route.isWalkOnly())
		{
			methods.add(wrappedLabel("<i>No teleports — walking the whole way</i>"));
		}
		else
		{
			for (TeleportMethod method : route.getMethods())
			{
				methods.add(buildMethodRow(method));
			}
		}
		card.add(methods, BorderLayout.CENTER);

		card.setToolTipText(selected ? "Showing on map — click to hide" : "Click to show this route on the map");
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		makeSelectable(card, index);
		return card;
	}

	/**
	 * A route-card method row: category dot + wrapped label + an exclude (✕) icon.
	 */
	private JPanel buildMethodRow(TeleportMethod method)
	{
		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setOpaque(false);

		JLabel dot = new JLabel(categoryDot(method.category()));
		dot.setVerticalAlignment(SwingConstants.TOP);
		dot.setBorder(new EmptyBorder(2, 0, 0, 0));
		dot.setToolTipText(method.category());
		row.add(dot, BorderLayout.WEST);

		JLabel text = wrappedLabel(escapeHtml(method.label()));
		text.setToolTipText(methodTooltip(method));
		row.add(text, BorderLayout.CENTER);

		IconActionLabel exclude = new IconActionLabel(RouteIcons.EXCLUDE, RouteIcons.EXCLUDE_HOVER,
			"Exclude \"" + method.label() + "\" from the next search", () -> plugin.excludeMethod(method));
		JPanel actionWrap = new JPanel(new BorderLayout());
		actionWrap.setOpaque(false);
		actionWrap.add(control(exclude), BorderLayout.NORTH);
		row.add(actionWrap, BorderLayout.EAST);

		return row;
	}

	private JPanel buildCatalogSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.setBorder(new EmptyBorder(10, 0, 0, 0));
		section.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel title = new JLabel("Teleport methods");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		title.setBorder(new EmptyBorder(0, 0, 4, 0));
		section.add(title);

		Map<String, List<TeleportMethod>> grouped = new TreeMap<>();
		for (TeleportMethod method : cachedCatalog)
		{
			grouped.computeIfAbsent(method.category(), k -> new ArrayList<>()).add(method);
		}
		for (List<TeleportMethod> items : grouped.values())
		{
			items.sort(Comparator.comparing(m -> m.label().toLowerCase()));
		}

		for (Map.Entry<String, List<TeleportMethod>> entry : grouped.entrySet())
		{
			String category = entry.getKey();
			List<TeleportMethod> items = entry.getValue();
			section.add(buildCategoryHeader(category, items));
			if (expandedCategories.contains(category))
			{
				for (TeleportMethod item : items)
				{
					section.add(buildCatalogItemRow(item));
				}
			}
		}

		return section;
	}

	private JPanel buildCategoryHeader(String category, List<TeleportMethod> items)
	{
		int excludedCount = 0;
		for (TeleportMethod method : items)
		{
			if (cachedExclusions.contains(method))
			{
				excludedCount++;
			}
		}
		boolean allIncluded = excludedCount == 0;
		boolean allExcluded = excludedCount == items.size();
		boolean expanded = expandedCategories.contains(category);

		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(3, 4, 3, 4)));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

		ImageIcon icon;
		ImageIcon hover;
		String tip;
		Runnable action;
		if (allIncluded)
		{
			icon = RouteIcons.CHECK;
			hover = RouteIcons.CHECK_HOVER;
			tip = "All included — click to exclude every " + category.toLowerCase();
			action = () -> plugin.excludeMethods(items);
		}
		else if (allExcluded)
		{
			icon = RouteIcons.CROSS;
			hover = RouteIcons.CROSS_HOVER;
			tip = "All excluded — click to include every " + category.toLowerCase();
			action = () -> plugin.includeMethods(items);
		}
		else
		{
			icon = RouteIcons.DASH;
			hover = RouteIcons.DASH_HOVER;
			tip = (items.size() - excludedCount) + " of " + items.size() + " included — click to include all";
			action = () -> plugin.includeMethods(items);
		}
		row.add(control(new IconActionLabel(icon, hover, tip, action)), BorderLayout.WEST);

		String count = allIncluded
			? " (" + items.size() + ")"
			: " (" + (items.size() - excludedCount) + "/" + items.size() + ")";
		JLabel name = new JLabel(category + count);
		name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		row.add(name, BorderLayout.CENTER);

		row.add(control(new JLabel(expanded ? RouteIcons.CHEVRON_DOWN : RouteIcons.CHEVRON_RIGHT)), BorderLayout.EAST);

		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setToolTipText(expanded ? "Collapse" : "Expand to toggle individual methods");
		addClickRecursively(row, new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggleCategory(category);
			}
		});
		return row;
	}

	private JPanel buildCatalogItemRow(TeleportMethod item)
	{
		boolean excluded = cachedExclusions.contains(item);

		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(2, 18, 2, 4));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

		IconActionLabel toggle = excluded
			? new IconActionLabel(RouteIcons.CROSS, RouteIcons.CROSS_HOVER,
				"Excluded — click to include", () -> plugin.includeMethod(item))
			: new IconActionLabel(RouteIcons.CHECK, RouteIcons.CHECK_HOVER,
				"Included — click to exclude", () -> plugin.excludeMethod(item));
		JPanel toggleWrap = new JPanel(new BorderLayout());
		toggleWrap.setOpaque(false);
		toggleWrap.add(control(toggle), BorderLayout.NORTH);
		row.add(toggleWrap, BorderLayout.WEST);

		JLabel text = wrappedLabel(escapeHtml(item.label()));
		text.setToolTipText(methodTooltip(item));
		if (excluded)
		{
			text.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		}
		row.add(text, BorderLayout.CENTER);

		return row;
	}

	private void toggleCategory(String category)
	{
		if (!expandedCategories.add(category))
		{
			expandedCategories.remove(category);
		}
		render();
	}

	private String methodTooltip(TeleportMethod method)
	{
		int destination = method.getDestination();
		int x = WorldPointUtil.unpackWorldX(destination);
		int y = WorldPointUtil.unpackWorldY(destination);
		int plane = WorldPointUtil.unpackWorldPlane(destination);
		return "<html><b>" + escapeHtml(method.category()) + "</b><br>"
			+ escapeHtml(method.label()) + "<br>"
			+ "Arrives at " + x + ", " + y + (plane > 0 ? " (plane " + plane + ")" : "")
			+ "</html>";
	}

	private void makeSelectable(JPanel card, int index)
	{
		addClickRecursively(card, new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				plugin.selectRoute(index);
			}
		});
	}

	/**
	 * Attaches a click listener to a component and its descendants, skipping {@link IconActionLabel}s
	 * so the icon controls keep their own action. Swing only delivers a click to the deepest component
	 * under the cursor, hence the recursion.
	 */
	private void addClickRecursively(Component component, MouseListener listener)
	{
		if (component instanceof IconActionLabel)
		{
			return;
		}
		component.addMouseListener(listener);
		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				addClickRecursively(child, listener);
			}
		}
	}

	private void updateModeButtons()
	{
		AlternativeRoutesMode mode = plugin.getRoutesMode();
		styleModeButton(availableButton, mode == AlternativeRoutesMode.AVAILABLE);
		styleModeButton(bankButton, mode == AlternativeRoutesMode.AVAILABLE_WITH_BANK);
		styleModeButton(allButton, mode == AlternativeRoutesMode.ALL_TELEPORTS);
	}

	private static void styleModeButton(JButton button, boolean active)
	{
		button.setForeground(active ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		button.setBackground(active ? ColorScheme.DARKER_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(active ? ColorScheme.BRAND_ORANGE : ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(3, 0, 3, 0)));
	}

	private JLabel wrappedLabel(String innerHtml)
	{
		JLabel label = new JLabel("<html><body style='width:" + METHOD_TEXT_WIDTH + "px'>" + innerHtml + "</body></html>");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setVerticalAlignment(SwingConstants.TOP);
		return label;
	}

	private static JLabel control(JLabel label)
	{
		label.setPreferredSize(new Dimension(CONTROL_SIZE, CONTROL_SIZE));
		label.setHorizontalAlignment(SwingConstants.CENTER);
		return label;
	}

	private static Icon categoryDot(String category)
	{
		final int s = 9;
		BufferedImage image = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(categoryColour(category));
		g.fillRoundRect(0, 1, s - 1, s - 2, 4, 4);
		g.dispose();
		return new ImageIcon(image);
	}

	private static Color categoryColour(String category)
	{
		return CATEGORY_PALETTE[Math.floorMod(category.hashCode(), CATEGORY_PALETTE.length)];
	}

	private static String escapeHtml(String text)
	{
		if (text == null)
		{
			return "";
		}
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static Component verticalGap(int height)
	{
		JPanel gap = new JPanel();
		gap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		gap.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		gap.setPreferredSize(new Dimension(1, height));
		return gap;
	}
}
