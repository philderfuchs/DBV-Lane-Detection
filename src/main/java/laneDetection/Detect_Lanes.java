package laneDetection;

import java.util.ArrayList;
import java.util.Collections;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.NewImage;
import ij.gui.Roi;
import ij.plugin.filter.PlugInFilter;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

public class Detect_Lanes implements PlugInFilter {

	static final double regionSizeLowerThershold = 0.0;
	static final double regionSizeUpperThershold = 0.3;

	class Pixel implements Comparable<Pixel> {
		int x;
		int y;

		public Pixel(int x, int y) {
			this.x = x;
			this.y = y;
		}

		public int compareTo(Pixel o) {
			if (this.y < o.y) {
				return -1;
			} else if (this.y == o.y) {
				return this.x < o.x ? -1 : 1;
			} else {
				return 1;
			}
		}
	}

	class Region {
		ArrayList<Pixel> pixels = new ArrayList<Pixel>();
		int id;
	}

	public int setup(String arg, ImagePlus imp) {
		return DOES_ALL;
	}

	public void run(ImageProcessor ip) {
		ByteProcessor byteImageProcessor = (ByteProcessor) ip.convertToByte(true);

		int roiOffsetX = 0;
		int roiOffsetY = ip.getHeight() / 2;

		Roi roi = new Roi(0, roiOffsetY, ip.getWidth() - roiOffsetX - 1, roiOffsetY - 1);
		byteImageProcessor.setRoi(roi);

		ImagePlus grayscaleImage = new ImagePlus("Grayscale", byteImageProcessor.crop());
		grayscaleImage.show();

		IJ.run("Canny Edge Detector", "Low threshold=[1.0] High threshold=[3.0] Normalize contrast=[true]");
		byteImageProcessor = (ByteProcessor) grayscaleImage.getProcessor();
		byteImageProcessor.dilate();
		byteImageProcessor.dilate();

		// new ImagePlus("Dilated Edges", byteImageProcessor).show();

		roiOffsetX += 12;
		roiOffsetY += 12;
		roi = new Roi(12, 12, byteImageProcessor.getWidth() - 24, byteImageProcessor.getHeight() - 24);
		byteImageProcessor.setRoi(roi);
		ByteProcessor cropped = (ByteProcessor) byteImageProcessor.crop();
		new ImagePlus("Dilated Edges Cropped", cropped);// .show();

		Region street = this.fillFromSeed(cropped, cropped.getWidth() / 2, cropped.getHeight() - 5, 0);
		ImagePlus streetPlus = NewImage.createByteImage("Filled Region", cropped.getWidth(), cropped.getHeight(), 1,
				NewImage.FILL_WHITE);
		ByteProcessor streetProcessor = (ByteProcessor) streetPlus.getProcessor();
		for (Pixel p : street.pixels) {
			streetProcessor.set(p.x, p.y, 0);
		}
		streetPlus.show();

		Region leftLane = new Region();
		Region rightLane = new Region();

		extractOuterLanes(street, streetProcessor, leftLane, rightLane);
		drawLanes(ip, roiOffsetX, roiOffsetY, leftLane, rightLane);

		// get left regions
		ArrayList<Region> regions = collectAndFilterOtherRegions(streetProcessor);

		for (Region r : regions) {
			for (Pixel p : r.pixels) {
				ip.set(p.x + roiOffsetX, p.y + roiOffsetY, ((0 & 0xff) << 16) + ((0 & 0xff) << 8) + (255 & 0xff));
			}
		}

	}

	private ArrayList<Region> collectAndFilterOtherRegions(ByteProcessor streetProcessor) {
		ArrayList<Region> regions = new ArrayList<Region>();
		int regionId = 0;
		for (int i = 0; i < streetProcessor.getWidth(); i++) {
			for (int j = 0; j < streetProcessor.getHeight(); j++) {
				if (streetProcessor.get(i, j) == 255) {
					Region region = this.fillFromSeed(streetProcessor, i, j, 255);
					region.id = regionId++;
					for (Pixel p : region.pixels) {
						streetProcessor.set(p.x, p.y, 0);
						// ip.set(p.x + roiOffsetX, p.y + roiOffsetY,
						// ((0 & 0xff) << 16) + ((0 & 0xff) << 8) + (255 &
						// 0xff));
					}

					double regionSize = region.pixels.size();
					double imageSize = streetProcessor.getPixelCount();
					System.out.println("ID: " + region.id + "RegionSize: " + regionSize + " | ImageSize: " + imageSize);
					if (regionSize > imageSize * regionSizeLowerThershold
							&& regionSize < imageSize * regionSizeUpperThershold)
						regions.add(region);
				}
			}
		}
		return regions;
	}

	private void extractOuterLanes(Region street, ByteProcessor streetProcessor, Region leftLane, Region rightLane) {
		// Find left and right lanes
		Collections.sort(street.pixels);
		int currentY = 0;
		Pixel lastPixel = null;
		for (Pixel p : street.pixels) {
			if (p.y != currentY) {
				currentY = p.y;
				if (p.x != 0)
					leftLane.pixels.add(p);
				if (lastPixel != null && lastPixel.x != streetProcessor.getWidth() - 1) {
					rightLane.pixels.add(lastPixel);
				}
			}

			lastPixel = p;
		}
	}

	private void drawLanes(ImageProcessor ip, int roiOffsetX, int roiOffsetY, Region leftLane, Region rightLane) {
		for (Pixel p : leftLane.pixels) {
			for (int i = -10; i <= 0; i++)
				ip.set(p.x + roiOffsetX - i, p.y + roiOffsetY, ((255 & 0xff) << 16) + ((0 & 0xff) << 8) + (0 & 0xff));

		}

		for (Pixel p : rightLane.pixels) {
			for (int i = 0; i <= 10; i++)
				ip.set(p.x + roiOffsetX - i, p.y + roiOffsetY, ((0 & 0xff) << 16) + ((255 & 0xff) << 8) + (0 & 0xff));
		}
	}

	/****************************************************************
	 * Region Filling Stuff *****************************************
	 ****************************************************************/

	ByteProcessor contourImage;
	ArrayList<Pixel> tempPixelList;

	private Region fillFromSeed(ByteProcessor processor, int x, int y, int commonColor) {
		contourImage = (ByteProcessor) processor.duplicate();
		tempPixelList = new ArrayList<Pixel>();

		if (processor.get(x, y) == commonColor) {
			queueExpandPixel(x, y, commonColor);
		}

		Region region = new Region();
		for (Pixel p : tempPixelList) {
			region.pixels.add(new Pixel(p.x, p.y));
		}
		return region;
	}

	private void queueExpandPixel(int x, int y, int commonColor) {
		ArrayList<Pixel> queue = new ArrayList<Pixel>();
		queue.add(new Pixel(x, y));
		while (queue.size() > 0) {
			Pixel p = queue.get(0);
			if (contourImage.get(p.x, p.y) != commonColor) {
				queue.remove(0);
				continue;
			}
			tempPixelList.add(new Pixel(p.x, p.y));
			contourImage.set(p.x, p.y, 255 - commonColor);
			if (p.y - 1 >= 0) {
				queue.add(new Pixel(p.x, p.y - 1));
			}
			if (p.y + 1 < contourImage.getHeight()) {
				queue.add(new Pixel(p.x, p.y + 1));
			}
			if (p.x - 1 >= 0) {
				queue.add(new Pixel(p.x - 1, p.y));
			}
			if (p.x + 1 < contourImage.getWidth()) {
				queue.add(new Pixel(p.x + 1, p.y));
			}
			queue.remove(0);
		}
	}

}
