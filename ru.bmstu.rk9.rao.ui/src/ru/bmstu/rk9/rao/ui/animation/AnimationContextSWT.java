package ru.bmstu.rk9.rao.ui.animation;

import java.util.HashMap;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Rectangle;

import ru.bmstu.rk9.rao.lib.animation.AnimationContext;
import ru.bmstu.rk9.rao.lib.animation.AnimationFrame;
import ru.bmstu.rk9.rao.lib.animation.BackgroundData;
import ru.bmstu.rk9.rao.lib.animation.RaoColor;

public class AnimationContextSWT implements AnimationContext {
	private Display display;

	AnimationContextSWT(Display display) {
		this.display = display;
	}

	private GC paintContext;

	private HashMap<AnimationFrame, Image> storedFrames = new HashMap<AnimationFrame, Image>();

	void drawBackground(BackgroundData backgroundData) {
		paintContext.setAlpha(255);

		Color backgroundColor = createColor(backgroundData.color);

		paintContext.setBackground(backgroundColor);
		paintContext.fillRectangle(0, 0, backgroundData.width, backgroundData.height);

		backgroundColor.dispose();
	}

	void drawFrame(GC gc, AnimationFrame frame) {
		Image image = storedFrames.get(frame);

		if (image != null)
			gc.drawImage(image, 0, 0);
	}

	void prepareFrame(AnimationFrame frame) {
		Image lastStored = storedFrames.get(frame);

		if (lastStored != null)
			lastStored.dispose();

		storedFrames.put(frame, drawFrameBuffer(frame));
	}

	private Image drawFrameBuffer(AnimationFrame frame) {
		BackgroundData backgroundData = frame.getBackgroundData();

		Rectangle backgroundRectangle = new Rectangle(0, 0, backgroundData.width, backgroundData.height);

		Image image = new Image(display, backgroundRectangle);

		paintContext = new GC(image);
		paintContext.setAntialias(SWT.ON);

		drawBackground(backgroundData);

		frame.draw(this);

		paintContext.dispose();

		return image;
	}

	// TODO handle alignment
	@Override
	public void drawText(String text, int x, int y, RaoColor textRaoColor, int width, Alignment alignment) {
		paintContext.setAlpha(textRaoColor.alpha);

		Color foregroundColor = createColor(textRaoColor);

		paintContext.setForeground(foregroundColor);
		paintContext.drawText(text, x, y, true);

		foregroundColor.dispose();
	}

	@Override
	public void drawText(String text, int x, int y, RaoColor textColor) {
		drawText(text, x, y, textColor, 0, Alignment.LEFT);
	}

	@Override
	public void drawRectangle(int x, int y, int width, int height, RaoColor backgroundRaoColor,
			RaoColor borderRaoColor) {
		paintContext.setAlpha(backgroundRaoColor.alpha);

		Color backgroundColor = createColor(backgroundRaoColor);

		paintContext.setBackground(backgroundColor);
		paintContext.fillRectangle(x, y, width, height);

		paintContext.setAlpha(borderRaoColor.alpha);

		Color borderColor = createColor(borderRaoColor);

		paintContext.setForeground(borderColor);
		paintContext.drawRectangle(x, y, width, height);

		backgroundColor.dispose();
		borderColor.dispose();
	}

	@Override
	public void drawLine(int x1, int y1, int x2, int y2, RaoColor lineRaoColor) {
		paintContext.setAlpha(lineRaoColor.alpha);

		Color lineColor = createColor(lineRaoColor);

		paintContext.setForeground(lineColor);
		paintContext.drawLine(x1, y1, x2, y2);

		lineColor.dispose();
	}

	@Override
	public void drawCircle(int x, int y, int radius, RaoColor backgroundRaoColor, RaoColor borderRaoColor) {
		paintContext.setAlpha(backgroundRaoColor.alpha);

		Color backgroundColor = createColor(backgroundRaoColor);

		paintContext.setBackground(backgroundColor);
		paintContext.fillOval(x - radius / 2, y - radius / 2, radius, radius);

		paintContext.setAlpha(borderRaoColor.alpha);

		Color borderColor = createColor(borderRaoColor);

		paintContext.setForeground(borderColor);
		paintContext.drawOval(x - radius / 2, y - radius / 2, radius, radius);

		backgroundColor.dispose();
		borderColor.dispose();
	}

	@Override
	public void drawEllipse(int x, int y, int width, int height, RaoColor backgroundRaoColor, RaoColor borderRaoColor) {
		paintContext.setAlpha(backgroundRaoColor.alpha);

		Color backgroundColor = createColor(backgroundRaoColor);

		paintContext.setBackground(backgroundColor);
		paintContext.fillOval(x, y, width, height);

		paintContext.setAlpha(borderRaoColor.alpha);

		Color borderColor = createColor(borderRaoColor);

		paintContext.setForeground(borderColor);
		paintContext.drawOval(x, y, width, height);

		backgroundColor.dispose();
		borderColor.dispose();
	}

	@Override
	public void drawTriangle(int x1, int y1, int x2, int y2, int x3, int y3, RaoColor backgroundRaoColor,
			RaoColor borderRaoColor) {
		paintContext.setAlpha(backgroundRaoColor.alpha);

		Color backgroundColor = createColor(backgroundRaoColor);

		paintContext.setBackground(backgroundColor);
		paintContext.fillPolygon(new int[] { x1, y1, x2, y2, x3, y3 });

		paintContext.setAlpha(borderRaoColor.alpha);

		Color borderColor = createColor(borderRaoColor);

		paintContext.setForeground(borderColor);
		paintContext.drawPolygon(new int[] { x1, y1, x2, y2, x3, y3 });

		backgroundColor.dispose();
		borderColor.dispose();
	}

	Color createColor(RaoColor color) {
		return new Color(display, color.r, color.g, color.b);
	}
}
