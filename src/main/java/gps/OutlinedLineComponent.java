package gps;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;

/**
 * A left/right text row for the GPS panel — {@code LineComponent}'s single-line layout with one
 * addition it doesn't expose: an OUTLINE text effect (black border on all four sides) instead of
 * the standard one-pixel drop shadow. The outline is what keeps the text readable when the
 * transparent-background option removes the panel behind it; with the option off the row renders
 * with the stock shadow, pixel-identical to {@code LineComponent}. No wrapping support: the panel
 * pre-ellipsizes every left text to fit, which is exactly why wrapped rows never occur.
 */
@Setter
@Builder
class OutlinedLineComponent implements LayoutableRenderableEntity
{
	private String left;
	private String right;

	@Builder.Default
	private Color leftColor = Color.WHITE;

	@Builder.Default
	private Color rightColor = Color.WHITE;

	private Font leftFont;

	private Font rightFont;

	// Black border on all four sides instead of the single drop shadow.
	private boolean outlined;

	@Builder.Default
	private Point preferredLocation = new Point();

	@Builder.Default
	private Dimension preferredSize = new Dimension(ComponentConstants.STANDARD_WIDTH, 0);

	@Builder.Default
	@Getter
	private final Rectangle bounds = new Rectangle();

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final String leftText = left == null ? "" : left;
		final String rightText = right == null ? "" : right;
		final Font fontForLeft = leftFont != null ? leftFont : graphics.getFont();
		final Font fontForRight = rightFont != null ? rightFont : graphics.getFont();
		final FontMetrics lfm = graphics.getFontMetrics(fontForLeft);
		final FontMetrics rfm = graphics.getFontMetrics(fontForRight);
		final int fmHeight = Math.max(lfm.getHeight(), rfm.getHeight());
		final int x = preferredLocation.x;
		final int y = preferredLocation.y + fmHeight;

		if (!leftText.isEmpty())
		{
			drawText(graphics, leftText, x, y, fontForLeft, leftColor);
		}
		if (!rightText.isEmpty())
		{
			drawText(graphics, rightText, x + preferredSize.width - rfm.stringWidth(rightText), y,
				fontForRight, rightColor);
		}

		final Dimension dimension = new Dimension(preferredSize.width, fmHeight);
		bounds.setLocation(preferredLocation);
		bounds.setSize(dimension);
		return dimension;
	}

	private void drawText(Graphics2D graphics, String text, int x, int y, Font font, Color colour)
	{
		graphics.setFont(font);
		graphics.setColor(Color.BLACK);
		if (outlined)
		{
			graphics.drawString(text, x, y + 1);
			graphics.drawString(text, x, y - 1);
			graphics.drawString(text, x + 1, y);
			graphics.drawString(text, x - 1, y);
		}
		else
		{
			graphics.drawString(text, x + 1, y + 1);
		}
		graphics.setColor(colour);
		graphics.drawString(text, x, y);
	}
}
