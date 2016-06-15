package laneDetection;

import java.awt.Color;
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

	class Region implements Comparable<Region> {
		ArrayList<Pixel> pixels = new ArrayList<Pixel>();
		int id;
		double mean;
		private int _rowCount = 0;

		// Only works if someone sorted pixels beforehand
		public Pixel getTopCenterPixel() {
			_rowCount = (_rowCount != 0) ? _rowCount : calculateRowCount();
			int targetRow = _rowCount / 4;

			int firstPixelOfRowIndex = 0, lastPixelOfRowIndex = 0;
			int index = 0, currentRow = 1;
			boolean firstPixelFound = false;
			int y = pixels.get(0).y;
			for (Pixel p : pixels) {
				if (p.y != y) {
					currentRow++;
					y = p.y;
				}
				if (currentRow == targetRow && !firstPixelFound) {
					firstPixelOfRowIndex = index;
					firstPixelFound = true;
				}
				if (currentRow > targetRow) {
					lastPixelOfRowIndex = index - 1;
					break;
				}
				index++;
			}
			return pixels.get(firstPixelOfRowIndex + ((lastPixelOfRowIndex - firstPixelOfRowIndex) / 2));
		}

		public Pixel getBottomCenterPixel() {
			_rowCount = (_rowCount != 0) ? _rowCount : calculateRowCount();
			int targetRow = _rowCount - _rowCount / 4;

			int currentRow = _rowCount;
			int firstPixelOfRowIndex = 0, lastPixelOfRowIndex = 0;
			int index = pixels.size() - 1;
			int y = pixels.get(pixels.size() - 1).y;
			boolean lastPixelFound = false;

			for (int i = pixels.size() - 1; i >= 0; i--) {
				if (pixels.get(i).y != y) {
					currentRow--;
					y = pixels.get(i).y;
				}
				if (currentRow == targetRow && !lastPixelFound) {
					lastPixelOfRowIndex = index;
					lastPixelFound = true;
				}
				if (currentRow < targetRow) {
					firstPixelOfRowIndex = index + 1;
					break;
				}
				index--;
			}
			return pixels.get(firstPixelOfRowIndex + ((lastPixelOfRowIndex - firstPixelOfRowIndex) / 2));
		}

		private int calculateRowCount() {
			int y = pixels.get(0).y;
			int rowCount = 0;
			for (Pixel p : pixels) {
				if (p.y != y) {
					rowCount++;
					y = p.y;
				}
			}
			return rowCount;
		}

		// only works with sorted pixel lists
		public int compareTo(Region o) {
			if (this.pixels.get(0).y < o.pixels.get(0).y) {
				return -1;
			} else if (this.pixels.get(0).y == o.pixels.get(0).y) {
				return 0;
			} else {
				return 1;
			}
		}
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

		ImagePlus roiImage = new ImagePlus("Grayscale with ROI", byteImageProcessor.crop());
		roiImage.show();

		IJ.run("Canny Edge Detector", "Low threshold=[1.0] High threshold=[3.0] Normalize contrast=[true]");
		ImageProcessor roiImageProcessor = (ByteProcessor) roiImage.getProcessor();
		roiImageProcessor.dilate();
		roiImageProcessor.dilate();

		// new ImagePlus("Dilated Edges", byteImageProcessor).show();

		roiOffsetX += 12;
		roiOffsetY += 12;
		roi = new Roi(12, 12, roiImageProcessor.getWidth() - 24, roiImageProcessor.getHeight() - 24);
		roiImageProcessor.setRoi(roi);
		ByteProcessor cropped = (ByteProcessor) roiImageProcessor.crop();
		new ImagePlus("Dilated Edges Cropped", cropped);// .show();

		Region street = this.fillFromSeed(cropped, cropped.getWidth() / 2, cropped.getHeight() - 5, 0);
		if (street.pixels.size() == 0) {
			// Filling of street failed.
			// Starting Backup Plan
			street = this.fillFromSeed(cropped, cropped.getWidth() / 2, cropped.getHeight() - 10, 0);
		}
		ImagePlus streetPlus = NewImage.createByteImage("Filled Region", cropped.getWidth(), cropped.getHeight(), 1,
				NewImage.FILL_WHITE);
		ByteProcessor streetProcessor = (ByteProcessor) streetPlus.getProcessor();
		for (Pixel p : street.pixels) {
			streetProcessor.set(p.x, p.y, 0);
		}
		// streetPlus.show();

		Region leftLane = new Region();
		Region rightLane = new Region();

		extractOuterLanes(street, streetProcessor, leftLane, rightLane);
		drawLanes(ip, roiOffsetX, roiOffsetY, leftLane, rightLane);

		// get other regions
		// regions come sorted from upper to lower positions of their respective
		// top pixel
		ArrayList<Region> regions = collectOtherRegions(streetProcessor);
		regions = filterRegions(regions, streetProcessor, byteImageProcessor, roiOffsetX, roiOffsetY);

		if (regions.size() == 0)
			return;

		ArrayList<ArrayList<Region>> dashedLanes = extractDashedLanes(regions);
		ip.setColor(Color.YELLOW);

		// Draw dashed Lanes by connecting the dashes of each lane
		for (ArrayList<Region> dashedLane : dashedLanes) {
			for (int i = 0; i < dashedLane.size(); i++) {
				// draw dash itself
				for (Pixel p : dashedLane.get(i).pixels) {
					ip.set(p.x + roiOffsetX, p.y + roiOffsetY, ((255 & 0xff) << 16) + ((255 & 0xff) << 8) + (0 & 0xff));
				}
				// draw line from dash do next dash
				if (i < dashedLane.size() - 1) {
					for (int j = -5; j <= 5; j++) {
						ip.drawLine(dashedLane.get(i).getBottomCenterPixel().x + roiOffsetX + j,
								dashedLane.get(i).getBottomCenterPixel().y + roiOffsetY,
								dashedLane.get(i + 1).getTopCenterPixel().x + roiOffsetX + j,
								dashedLane.get(i + 1).getTopCenterPixel().y + roiOffsetY);
					}
				}

				// draw center Pixels
				// ip.set(dashedLane.get(i).getTopCenterPixel().x + roiOffsetX,
				// dashedLane.get(i).getTopCenterPixel().y + roiOffsetY,
				// ((0 & 0xff) << 16) + ((0 & 0xff) << 8) + (255 & 0xff));
				// ip.set(dashedLane.get(i).getBottomCenterPixel().x +
				// roiOffsetX,
				// dashedLane.get(i).getBottomCenterPixel().y + roiOffsetY,
				// ((0 & 0xff) << 16) + ((0 & 0xff) << 8) + (255 & 0xff));
			}
		}

	}

	private ArrayList<ArrayList<Region>> extractDashedLanes(ArrayList<Region> regions) {
		ArrayList<ArrayList<Region>> dashedLanes = new ArrayList<ArrayList<Region>>();

		dashedLanes.add(new ArrayList<Region>());
		dashedLanes.get(dashedLanes.size() - 1).add(regions.get(0));
		Region dash = regions.get(0);
		Region nextDash = null;

		// find next region
		while (regions.size() > 1) {
			double minDistance = Double.MAX_VALUE;
			regions.remove(dash);
			for (int i = 0; i < regions.size(); i++) {
				double currentDistance = this.distance(dash.pixels.get(dash.pixels.size() - 1),
						regions.get(i).pixels.get(0));
				if (currentDistance < minDistance) {
					minDistance = currentDistance;
					nextDash = regions.get(i);
				}
			}
			if (dash.pixels.get(dash.pixels.size() - 1).y - nextDash.pixels.get(0).y > 0) {
				// next line is positioned over current line
				// start of next lane
				dashedLanes.add(new ArrayList<Region>());
				// start over from top
				nextDash = regions.get(0);
			}
			dashedLanes.get(dashedLanes.size() - 1).add(nextDash);
			dash = nextDash;
		}
		return dashedLanes;
	}

	private double distance(Pixel p1, Pixel p2) {
		return Math.sqrt(Math.pow((((double) p1.x - p2.x)), 2) + Math.pow((((double) p1.y - p2.y)), 2));
	}

	private ArrayList<Region> filterRegions(ArrayList<Region> regions, ByteProcessor streetProcessor,
			ByteProcessor originalByteImage, int roiOffsetX, int roiOffsetY) {

		ArrayList<Region> filteredRegions = new ArrayList<Region>();
		double imageSize = streetProcessor.getPixelCount();
		// First filter by size

		for (Region r : regions) {
			int sum = 0;
			for (Pixel p : r.pixels) {
				sum += originalByteImage.get(p.x + roiOffsetX, p.y + roiOffsetY);
			}
			double mean = (double) sum / (double) r.pixels.size();
			r.mean = mean;

			if (r.pixels.size() > imageSize * regionSizeLowerThershold
					&& r.pixels.size() < imageSize * regionSizeUpperThershold) {
				filteredRegions.add(r);
			}
		}
		regions = filteredRegions;
		filteredRegions = new ArrayList<Region>();

		// now filter by intensity

		// calculate intensity threshold
		int iterationLimit = Math.min(2, regions.size());
		double pixelIntensitySum = 0;
		double count = 0;
		for (int i = 0; i < iterationLimit; i++) {
			for (Pixel p : regions.get(i).pixels) {
				pixelIntensitySum += originalByteImage.get(p.x + roiOffsetX, p.y + roiOffsetY);
				count++;
			}
		}

		double meanLaneMarkingIntensity = pixelIntensitySum / count;

		// filter out too dark regions
		for (Region r : regions) {
			int sum = 0;
			for (Pixel p : r.pixels) {
				sum += originalByteImage.get(p.x + roiOffsetX, p.y + roiOffsetY);
			}
			double mean = (double) sum / (double) r.pixels.size();
			r.mean = mean;

			if (mean >= meanLaneMarkingIntensity - 10) {
				filteredRegions.add(r);
			}
		}

		return filteredRegions;
	}

	private ArrayList<Region> collectOtherRegions(ByteProcessor streetProcessor) {
		ArrayList<Region> regions = new ArrayList<Region>();
		int regionId = 0;
		for (int y = 0; y < streetProcessor.getHeight(); y++) {
			for (int x = 0; x < streetProcessor.getWidth(); x++) {
				if (streetProcessor.get(x, y) == 255) {
					Region region = this.fillFromSeed(streetProcessor, x, y, 255);
					region.id = regionId++;
					for (Pixel p : region.pixels) {
						streetProcessor.set(p.x, p.y, 0);
					}
					Collections.sort(region.pixels);
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
				ip.set(p.x + roiOffsetX + i, p.y + roiOffsetY, ((255 & 0xff) << 16) + ((0 & 0xff) << 8) + (0 & 0xff));

		}

		for (Pixel p : rightLane.pixels) {
			for (int i = 0; i <= 10; i++)
				ip.set(p.x + roiOffsetX + i, p.y + roiOffsetY, ((0 & 0xff) << 16) + ((255 & 0xff) << 8) + (0 & 0xff));
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
