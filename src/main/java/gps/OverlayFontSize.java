package gps;

/**
 * Text-size presets for the directions overlay, limited to combinations that RENDER CRISPLY: the
 * RuneScape fonts are pixel-art TTFs designed for size 16, so fractional sizes smear their square
 * pixels across pixel boundaries (the reason a free-size setting looked distorted). Small shifts
 * the three-tier hierarchy down onto the native small face; Large pixel-doubles everything to 32 —
 * the one enlargement that keeps the pixels sharp.
 */
public enum OverlayFontSize
{
	SMALL("Small"),
	NORMAL("Normal"),
	LARGE("Large");

	private final String label;

	OverlayFontSize(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
