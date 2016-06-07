package laneDetection;

import java.util.ArrayList;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.NewImage;
import ij.gui.Roi;
import ij.plugin.filter.PlugInFilter;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

public class Detect_Lanes implements PlugInFilter {

	class Pixel {
		int x;
		int y;

		public Pixel(int x, int y) {
			this.x = x;
			this.y = y;
		}
	}

	class Region {
		ArrayList<Pixel> pixels = new ArrayList<Pixel>();
		int number;
	}

	public int setup(String arg, ImagePlus imp) {
		return DOES_ALL;
	}

	public void run(ImageProcessor ip) {
		ByteProcessor byteImageProcessor = (ByteProcessor) ip.convertToByte(true);

		Roi roi = new Roi(0, ip.getHeight() / 2, ip.getWidth() - 1, ip.getHeight() / 2 - 1);
		byteImageProcessor.setRoi(roi);

		ImagePlus grayscaleImage = new ImagePlus("Grayscale", byteImageProcessor.crop());
		grayscaleImage.show();

		IJ.run("Canny Edge Detector", "Low threshold=[1.0] High threshold=[3.0] Normalize contrast=[true]");
		byteImageProcessor = (ByteProcessor) grayscaleImage.getProcessor();
		byteImageProcessor.dilate();
		new ImagePlus("Dilated Edges", byteImageProcessor).show();

		roi = new Roi(12, 12, byteImageProcessor.getWidth() - 24, byteImageProcessor.getHeight() - 24);
		byteImageProcessor.setRoi(roi);
		ByteProcessor cropped = (ByteProcessor) byteImageProcessor.crop();
		new ImagePlus("Dilated Edges Cropped", cropped).show();

		Region region = this.fillFromSeed(cropped, cropped.getWidth() / 2, cropped.getHeight() - 5);
		ImagePlus plus = NewImage.createByteImage("Filled Region", cropped.getWidth(), cropped.getHeight(), 1,
				NewImage.FILL_WHITE);
		ByteProcessor filledRegionByteProcessor = (ByteProcessor) plus.getProcessor();
		for (Pixel p : region.pixels) {
			filledRegionByteProcessor.set(p.x, p.y, 0);
		}
		plus.show();
	}

	/****************************************************************
	 * Region Filling Stuff *****************************************
	 ****************************************************************/

	ByteProcessor contourImage;
	ArrayList<Pixel> tempPixelList;

	private Region fillFromSeed(ByteProcessor processor, int x, int y) {
		contourImage = (ByteProcessor) processor.duplicate();
		tempPixelList = new ArrayList<Pixel>();

		if (processor.get(x, y) == 0) {
			queueExpandPixel(x, y);
		}

		Region region = new Region();
		for (Pixel p : tempPixelList) {
			region.pixels.add(new Pixel(p.x, p.y));
		}
		return region;
	}
	
	private void queueExpandPixel(int x, int y) {
		ArrayList<Pixel> queue = new ArrayList<Pixel>();
		queue.add(new Pixel(x, y));
		while(queue.size() > 0) {
			Pixel p = queue.get(0);
			if(contourImage.get(p.x, p.y) == 255) {
				queue.remove(0);
				continue;
			}
			tempPixelList.add(new Pixel(p.x, p.y));
			contourImage.set(p.x, p.y, 255);
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

//	private void recExpandPixels(int x, int y) {
//		if (contourImage.get(x, y) == 255)
//			return;
//		tempPixelList.add(new Pixel(x, y));
//		contourImage.set(x, y, 255);
//		if (y - 1 >= 0) {
//			recExpandPixels(x, y - 1);
//		}
//		if (y + 1 < contourImage.getHeight()) {
//			recExpandPixels(x, y + 1);
//		}
//		if (x - 1 >= 0) {
//			recExpandPixels(x - 1, y);
//		}
//		if (x + 1 < contourImage.getWidth()) {
//			recExpandPixels(x + 1, y);
//		}
//	}

}
