package laneDetection;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.filter.PlugInFilter;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

public class Detect_Lanes implements PlugInFilter {
	
	public int setup(String arg, ImagePlus imp) {
		return DOES_ALL;
	}

	public void run(ImageProcessor ip) {
		ByteProcessor byteImageProcessor = (ByteProcessor) ip.convertToByte(true);
		
		Roi roi = new Roi(0, ip.getHeight() / 2, ip.getWidth() - 1, ip.getHeight() / 2 - 1);
		byteImageProcessor.setRoi(roi);

		ImagePlus grayscaleImage = new ImagePlus("Grayscale", byteImageProcessor.crop());
		grayscaleImage.show();
		
		//create the detector
//		Canny_Edge_Detector detector = new Canny_Edge_Detector();

		//adjust its parameters as desired
//		detector.setLowThreshold(1.0f);
//		detector.setHighThreshold(3.0f);

		//apply it to an image
//		detector.process(grayscaleImage).show();
		
		IJ.run("Canny Edge Detector", "Low threshold=[1.0] High threshold=[3.0] Normalize contrast=[true]");
		byteImageProcessor = (ByteProcessor) grayscaleImage.getProcessor();
		byteImageProcessor.dilate();
		new ImagePlus("Dilated Edges", byteImageProcessor).show();
	}

}
