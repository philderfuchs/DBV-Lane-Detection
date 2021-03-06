package laneDetection;

import java.awt.Polygon;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.gui.NewImage;
import ij.gui.Roi;
import ij.io.FileSaver;
import ij.io.Opener;
import ij.plugin.PlugIn;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

public class Detect_Lanes implements PlugIn {

	private static double regionSizeLowerThreshold = 0.0;
	private static double regionSizeUpperThreshold = 0.1;
	static final String fileNameExtension = "_Lane";

	private static String inputPath;
	private static String outputPath;
	private static String xmlPath;

	private static String cannyGaussian = "2";
	private static String cannyLow = "2.5";
	private static String cannyHigh = "7.5";

	private static boolean cancelExpMode = false;
	private static boolean verbose = false;

	private static double maxIntensityDifference = 10;

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

	enum LaneType {
		EGO_LEFT, EGO_RIGHT, OTHER;
	}

	enum DrawType {
		OUTER, INNER;
	}

	class Lane {
		ArrayList<Region> markings = new ArrayList();
		LaneType laneType;
		DrawType drawtype;

		public Lane(DrawType drawType) {
			this.drawtype = drawType;
		}

		public Lane(Region r, DrawType drawType) {
			markings.add(r);
			this.drawtype = drawType;
		}
	}

	class Region implements Comparable<Region> {
		ArrayList<Pixel> pixels = new ArrayList<Pixel>();
		int id;
		double mean;
		private int _rowCount = 0;
		public Pixel firstPixelOfTopRow;
		public Pixel lastPixelOfTopRow;
		public Pixel firstPixelOfBottomRow;
		public Pixel lastPixelOfBottomRow;

		// Only works if someone sorted pixels beforehand
		// Sets lastPixelOfTopRow and lastPixelOfTopRow as side effect
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
			firstPixelOfTopRow = pixels.get(firstPixelOfRowIndex);
			lastPixelOfTopRow = pixels.get(lastPixelOfRowIndex);
			return pixels.get(firstPixelOfRowIndex + ((lastPixelOfRowIndex - firstPixelOfRowIndex) / 2));
		}

		// Only works if someone sorted pixels beforehand
		// Sets firstPixelOfBottomRow and lastPixelOfBottomRow as side effect
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
			firstPixelOfBottomRow = pixels.get(firstPixelOfRowIndex);
			lastPixelOfBottomRow = pixels.get(lastPixelOfRowIndex);
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

	public boolean extractArguments(String args) {
		if (args == null)
			return false;

		String[] splArgs = args.split("[ ][-]");
		// remove starting dash
		for (int i = 0; i < splArgs.length; i++) {
			if (splArgs[i].charAt(0) == '-') {
				splArgs[i] = splArgs[i].substring(1);
			}
		}
		for (String s : splArgs) {
			String[] param = s.replaceFirst("\\s", "HuRz").split("HuRz");

			if (param[0].equals("i")) {
				// inputfile
				inputPath = param[1].replaceAll("\\s", "\\ ").trim();
			}
			if (param[0].equals("o")) {
				outputPath = param[1].replaceAll("\\s", "\\ ").trim();
			}
			if (param[0].equals("l")) {
				regionSizeLowerThreshold = Double.parseDouble(param[1].trim());
			}
			if (param[0].equals("u")) {
				regionSizeUpperThreshold = Double.parseDouble(param[1].trim());
			}
			if (param[0].equals("g")) {
				cannyGaussian = param[1].trim();
			}
			if (param[0].equals("c")) {
				cannyLow = param[1].trim();
			}
			if (param[0].equals("C")) {
				cannyHigh = param[1].trim();
			}
			if (param[0].equals("e")) {
				cancelExpMode = true;
			}
			if (param[0].equals("d")) {
				maxIntensityDifference = Double.parseDouble(param[1].trim());
			}
			if (param[0].equals("v")) {
				verbose = true;
			}
			if (param[0].equals("V")) {
				if (param.length == 1)
					System.out.println("Version number unchanged.");
				else
					System.out.println("Version number set to: " + param[1]);
			}
		}

		return true;
	}

	public void run(String args) {
		boolean foundArguments = this.extractArguments(Macro.getOptions());
		if (!foundArguments)
			return;

		if (inputPath == null) {
			System.err.println("No input file.");
			return;
		}

		new Opener().open(inputPath);

		if (outputPath == null) {
			int fileExtensionIndex = inputPath.lastIndexOf(".");
			String pathString = inputPath.substring(0, fileExtensionIndex);
			String fileString = inputPath.substring(fileExtensionIndex);
			outputPath = pathString.concat(fileNameExtension).concat(fileString);
			xmlPath = pathString.concat(fileNameExtension).concat(".xml");
		} else {
			int fileExtensionIndex = outputPath.lastIndexOf(".");
			String pathString = outputPath.substring(0, fileExtensionIndex);
			String fileString = outputPath.substring(fileExtensionIndex);
			xmlPath = pathString.concat(".xml");
		}

		ImagePlus plus = IJ.getImage();
		ImageProcessor ip = plus.getProcessor();

		ImageProcessor expProcessor = ip.duplicate();
		expProcessor.exp();

		ByteProcessor byteImageProcessor = (ByteProcessor) expProcessor.convertToByte(true);

		System.out.println("Starting.");

		boolean success = false;

		if (!cancelExpMode) {
			success = processImage(ip, byteImageProcessor, true);
		}
		if (!success) {

			if (verbose)
				System.out.println("\nTrying without exp().");
			success = processImage(ip, (ByteProcessor) ip.convertToByte(true), false);

			if (!success) {
				System.err.println("Failed. :(");
				return;
			}
		}
		plus.updateAndDraw();

		new FileSaver(plus).saveAsPng(outputPath);

		System.out.println("\nDone.");
	}

	boolean processImage(ImageProcessor ip, ByteProcessor byteImageProcessor, boolean expMode) {
		int roiOffsetX = 0;
		int roiOffsetY = ip.getHeight() / 2;

		Roi roi = new Roi(0, roiOffsetY, ip.getWidth() - roiOffsetX - 1, ip.getHeight() - roiOffsetY - 1);
		byteImageProcessor.setRoi(roi);

		ImagePlus roiImage = new ImagePlus("Grayscale with ROI", byteImageProcessor.crop());
		roiImage.show();

		if (verbose)
			System.out.print("Waiting on Canny Edge Detector ...");
		IJ.run("Canny Edge Detector", "gaussian=" + cannyGaussian + " low=" + cannyLow + " high=" + cannyHigh);
		if (verbose)
			System.out.println(" has finished.");
		ImageProcessor roiImageProcessor = (ByteProcessor) roiImage.getProcessor();
		roiImageProcessor.dilate();
		roiImageProcessor.dilate();
		roiImage.close();

		// new ImagePlus("Dilated Edges", roiImageProcessor).show();

		int edgeInset = 12;
		roiOffsetX += edgeInset;
		roiOffsetY += edgeInset;
		roi = new Roi(edgeInset, edgeInset, roiImageProcessor.getWidth() - edgeInset * 2,
				roiImageProcessor.getHeight() - edgeInset * 2);
		roiImageProcessor.setRoi(roi);
		ByteProcessor cropped = (ByteProcessor) roiImageProcessor.crop();
		Region street = new Region();

		int cutter = 20;
		int loopCount = 0;
		do {
			if (verbose)
				System.out.print("\rTrying hard " + ++loopCount + ((loopCount == 1) ? " time " : " times ")
						+ (expMode ? "in exp mode." : "in normal mode."));

			if (expMode) {
				roi = new Roi(0, cutter, cropped.getWidth() - 1, cropped.getHeight() - (cutter + 1));
				cropped.setRoi(roi);
				cropped = (ByteProcessor) cropped.crop();
				roiOffsetY += cutter;
			}

			if (cropped.getHeight() > byteImageProcessor.getHeight() / 4) {
				street = this.fillFromSeed(cropped, cropped.getWidth() / 2, cropped.getHeight() - 5, 0, 255);

				if (street.pixels.size() == 0) {
					// Filling of street failed.
					// Starting Backup Plan
					street = this.fillFromSeed(cropped, cropped.getWidth() / 2, cropped.getHeight() - 10, 0, 255);

					// If still failing, stop completely
					if (street.pixels.size() == 0)
						return false;
				}
			} else {
				return false;
			}
		} while (expMode && street.pixels.size() > cropped.getPixelCount() * 0.5);

		ImagePlus streetPlus = NewImage.createByteImage("Detected Street", cropped.getWidth(), cropped.getHeight(), 1,
				NewImage.FILL_WHITE);
		ByteProcessor streetProcessor = (ByteProcessor) streetPlus.getProcessor();
		for (Pixel p : street.pixels) {
			streetProcessor.set(p.x, p.y, 128);
		}

		// // Show asphalt
		// streetPlus.show();

		Region leftRegion = new Region();
		Region rightRegion = new Region();

		extractOuterLanes(street, streetProcessor, leftRegion, rightRegion);
		// drawLanes(ip, roiOffsetX, roiOffsetY, leftLane, rightLane);

		// get other regions
		// regions come sorted from upper to lower positions of their respective
		// top pixel
		ArrayList<Region> regions = collectOtherRegions(streetProcessor);
		regions = filterRegions(regions, streetProcessor, byteImageProcessor, roiOffsetX, roiOffsetY);
		ArrayList<Lane> dashedLanes = extractDashedLanes(regions);

		if (leftRegion.pixels.size() > 0) {
			dashedLanes.add(new Lane(leftRegion, DrawType.OUTER));
		}
		if (rightRegion.pixels.size() > 0) {
			dashedLanes.add(new Lane(rightRegion, DrawType.OUTER));
		}

		categorizeLanes(streetProcessor, dashedLanes);

		drawLanes(ip, roiOffsetX, roiOffsetY, streetProcessor, dashedLanes);

		// export xml
		ImagePlus xmlGuide = NewImage.createRGBImage("XML-Guide", ip.getWidth(), ip.getHeight(), 1,
				NewImage.FILL_WHITE);
		ImageProcessor xmlGuideProcessor = xmlGuide.getProcessor();
		drawLanes(xmlGuideProcessor, roiOffsetX, roiOffsetY, streetProcessor, dashedLanes);
		this.exportXML(xmlGuideProcessor);
		// xmlGuide.show();

		return true;
	}

	private void exportXML(ImageProcessor xmlGuideProcessor) {
		int background = xmlGuideProcessor.get(0, 0);
		DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder docBuilder;

		try {
			docBuilder = docFactory.newDocumentBuilder();
			// root elements
			Document doc = docBuilder.newDocument();
			Element video = doc.createElement("video");
			doc.appendChild(video);
			Element videoInfo = doc.createElement("info");
			video.appendChild(videoInfo);
			Element file = doc.createElement("info");
			file.setAttribute("type", "file");
			file.appendChild(doc.createTextNode(inputPath));
			videoInfo.appendChild(file);
			Element name = doc.createElement("name");
			name.appendChild(doc.createTextNode("EgoLane"));
			videoInfo.appendChild(name);
			Element description = doc.createElement("description");
			description.appendChild(doc.createTextNode("EgoLane-Aufgabe"));
			videoInfo.appendChild(description);
			Element frames = doc.createElement("frames");
			video.appendChild(frames);
			Element frame = doc.createElement("frame");
			frames.appendChild(frame);
			Element objects = doc.createElement("objects");
			frame.appendChild(objects);

			for (int y = 0; y < xmlGuideProcessor.getHeight(); y++) {
				for (int x = 0; x < xmlGuideProcessor.getWidth(); x++) {
					if (xmlGuideProcessor.get(x, y) != background) {

						Region region = this.fillFromSeed(xmlGuideProcessor, x, y, xmlGuideProcessor.get(x, y),
								background);
						Element object = doc.createElement("object");
						object.setAttribute("id", UUID.randomUUID().toString());
						objects.appendChild(object);
						Element info = doc.createElement("info");
						object.appendChild(info);
						Element category = doc.createElement("category");
						category.setAttribute("confidence", "1.0");
						category.appendChild(doc.createTextNode("road_mark"));
						Element booleanAttribute = doc.createElement("booleanAttribute");
						booleanAttribute.setAttribute("key", "leftMark");
						info.appendChild(category);
						// categorize lane
						if (xmlGuideProcessor.get(x, y) == ((255 & 0xff) << 16) + ((255 & 0xff) << 8) + (0 & 0xff)) {
							booleanAttribute.appendChild(doc.createTextNode("true"));
							info.appendChild(booleanAttribute);
						} else if (xmlGuideProcessor.get(x, y) == ((0 & 0xff) << 16) + ((255 & 0xff) << 8)
								+ (0 & 0xff)) {
							booleanAttribute.appendChild(doc.createTextNode("false"));
							info.appendChild(booleanAttribute);
						}
						Element shape = doc.createElement("shape");
						shape.setAttribute("type", "points");
						object.appendChild(shape);

						for (Pixel p : region.pixels) {
							xmlGuideProcessor.set(p.x, p.y, background);
							Element point = doc.createElement("point");
							Element xValue = doc.createElement("x");
							xValue.appendChild(doc.createTextNode(String.valueOf(p.x)));
							Element yValue = doc.createElement("y");
							yValue.appendChild(doc.createTextNode(String.valueOf(p.y)));
							point.appendChild(xValue);
							point.appendChild(yValue);
							shape.appendChild(point);
						}
					}
				}
			}

			TransformerFactory transformerFactory = TransformerFactory.newInstance();
			Transformer transformer = transformerFactory.newTransformer();

			DOMSource source = new DOMSource(doc);
			StreamResult result = new StreamResult(new File(xmlPath));
			transformer.transform(source, result);
		} catch (ParserConfigurationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (TransformerConfigurationException e) {
			e.printStackTrace();
		} catch (TransformerException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void drawLanes(ImageProcessor ip, int roiOffsetX, int roiOffsetY, ByteProcessor streetProcessor,
			ArrayList<Lane> dashedLanes) {
		// Draw dashed Lanes by connecting the dashes of each lane
		for (Lane lane : dashedLanes) {
			if (lane.laneType == LaneType.EGO_LEFT) {
				ip.setColor(((255 & 0xff) << 16) + ((255 & 0xff) << 8) + (0 & 0xff));
			} else if (lane.laneType == LaneType.EGO_RIGHT) {
				ip.setColor(((0 & 0xff) << 16) + ((255 & 0xff) << 8) + (0 & 0xff));
			} else if (lane.laneType == LaneType.OTHER) {
				ip.setColor(((255 & 0xff) << 16) + ((160 & 0xff) << 8) + (0 & 0xff));
			}
			if (lane.drawtype == DrawType.INNER) {

				for (int i = 0; i < lane.markings.size(); i++) {

					// draw marking itself
					for (Pixel p : lane.markings.get(i).pixels) {
						ip.drawPixel(p.x + roiOffsetX, p.y + roiOffsetY);
					}

					// draw connecting polygon from dash do next dash
					if (i < lane.markings.size() - 1) {
						ip.fillPolygon(
								new Polygon(new int[] { lane.markings.get(i).firstPixelOfBottomRow.x + roiOffsetX,
										lane.markings.get(i + 1).firstPixelOfTopRow.x + roiOffsetX,
										lane.markings.get(i + 1).lastPixelOfTopRow.x + roiOffsetX,
										lane.markings.get(i).lastPixelOfBottomRow.x + roiOffsetX,

								}, new int[] { lane.markings.get(i).firstPixelOfBottomRow.y + roiOffsetY, lane.markings.get(i + 1).firstPixelOfTopRow.y + roiOffsetY, lane.markings.get(i + 1).lastPixelOfTopRow.y + roiOffsetY, lane.markings.get(i).lastPixelOfBottomRow.y + roiOffsetY, }, 4));
					} else {
						// draw end of lane
						double dY = lane.markings.get(i).getBottomCenterPixel().y
								- lane.markings.get(i).getTopCenterPixel().y;
						double dX = lane.markings.get(i).getBottomCenterPixel().x
								- lane.markings.get(i).getTopCenterPixel().x;
						double m = dY / dX;
						double n = lane.markings.get(i).getBottomCenterPixel().y
								- m * lane.markings.get(i).getBottomCenterPixel().x;

						int targetX = (int) ((streetProcessor.getHeight() - 1 - n) / m);
						int thickness = (lane.markings.get(i).lastPixelOfBottomRow.x
								- lane.markings.get(i).firstPixelOfBottomRow.x) / 2;

						for (int j = -1 * thickness; j <= thickness; j++) {
							ip.drawLine(lane.markings.get(i).getBottomCenterPixel().x + roiOffsetX + j,
									lane.markings.get(i).getBottomCenterPixel().y + roiOffsetY,
									targetX + roiOffsetX + j, streetProcessor.getHeight() - 1 + roiOffsetY);
						}
					}
				}
			} else if (lane.drawtype == DrawType.OUTER) {
				Region r = lane.markings.get(0);
				int thickness = 12;
				int leftend = lane.laneType == LaneType.EGO_LEFT ? -1 * thickness : 0;
				int rightend = lane.laneType == LaneType.EGO_LEFT ? 0 : thickness;
				for (int i = 3; i < r.pixels.size() - 1; i++) {
					for (int j = leftend; j <= rightend; j++) {
						ip.drawLine(r.pixels.get(i).x + j + roiOffsetX, r.pixels.get(i).y + roiOffsetY,
								r.pixels.get(i + 1).x + j + roiOffsetX, r.pixels.get(i + 1).y + roiOffsetY);
					}
				}

			}
		}
	}

	private void categorizeLanes(ByteProcessor streetProcessor, ArrayList<Lane> dashedLanes) {
		// find left egolane
		Lane leftEgoLane = null;
		Lane rightEgoLane = null;
		int minDistanceLeft = Integer.MAX_VALUE;
		int minDistanceRight = Integer.MAX_VALUE;
		int currentDistance = 0;
		int centerX = streetProcessor.getWidth() / 2;
		for (Lane l : dashedLanes) {
			Region lowestRegion = l.markings.get(l.markings.size() - 1);
			currentDistance = centerX - lowestRegion.pixels.get(lowestRegion.pixels.size() - 1).x;

			if (currentDistance > 0 && currentDistance < minDistanceLeft) {
				minDistanceLeft = currentDistance;
				leftEgoLane = l;
			}
			if (currentDistance < 0 && Math.abs(currentDistance) < minDistanceRight) {
				minDistanceRight = currentDistance;
				rightEgoLane = l;
			}
		}
		leftEgoLane.laneType = LaneType.EGO_LEFT;
		rightEgoLane.laneType = LaneType.EGO_RIGHT;
		for (Lane l : dashedLanes) {
			if (l.laneType == null)
				l.laneType = LaneType.OTHER;
		}
	}

	private ArrayList<Lane> extractDashedLanes(ArrayList<Region> regions) {
		ArrayList<Lane> dashedLanes = new ArrayList<Lane>();
		if (regions.size() == 0)
			return dashedLanes;

		dashedLanes.add(new Lane(DrawType.INNER));
		dashedLanes.get(dashedLanes.size() - 1).markings.add(regions.get(0));
		Region dash = regions.get(0);
		Region nextDash = dash;
		boolean lookLeft = true;
		boolean lookBothWays = true;
		// find next region
		while (regions.size() > 1) {
			double minDistance = Double.MAX_VALUE;
			regions.remove(dash);
			lookBothWays = dash.getTopCenterPixel().x == dash.getBottomCenterPixel().x;
			lookLeft = dash.getTopCenterPixel().x > dash.getBottomCenterPixel().x;
			for (int i = 0; i < regions.size(); i++) {
				if (!lookBothWays && ((dash.getTopCenterPixel().x < regions.get(i).getTopCenterPixel().x && lookLeft)
						|| (dash.getTopCenterPixel().x > regions.get(i).getTopCenterPixel().x && !lookLeft)))
					continue;

				double currentDistance = this.distance(dash.getBottomCenterPixel(), regions.get(i).getTopCenterPixel());
				if (currentDistance < minDistance) {
					minDistance = currentDistance;
					nextDash = regions.get(i);
				}
			}
			if (dash.getBottomCenterPixel().y > nextDash.getTopCenterPixel().y) {
				// next line is positioned over current line
				// start of next lane
				dashedLanes.add(new Lane(DrawType.INNER));
				// start over from top
				nextDash = regions.get(0);
			}
			dashedLanes.get(dashedLanes.size() - 1).markings.add(nextDash);
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

			if (r.pixels.size() > imageSize * regionSizeLowerThreshold
					&& r.pixels.size() < imageSize * regionSizeUpperThreshold) {
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
			if (mean >= meanLaneMarkingIntensity - maxIntensityDifference) {
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
					Region region = this.fillFromSeed(streetProcessor, x, y, 255, 0);
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
				if (p.x != 0) {
					leftLane.pixels.add(new Pixel(p.x, p.y));
				}

				if (lastPixel != null && lastPixel.x != streetProcessor.getWidth() - 1) {
					rightLane.pixels.add(new Pixel(lastPixel.x, lastPixel.y));
				}
			}

			lastPixel = p;
		}
		Collections.sort(leftLane.pixels);
		Collections.sort(rightLane.pixels);
	}

	/****************************************************************
	 * Region Filling Stuff *****************************************
	 ****************************************************************/

	ImageProcessor contourImage;
	ArrayList<Pixel> tempPixelList;

	private Region fillFromSeed(ImageProcessor processor, int x, int y, int beforeColor, int afterColor) {
		contourImage = processor.duplicate();
		tempPixelList = new ArrayList<Pixel>();

		if (processor.get(x, y) == beforeColor) {
			queueExpandPixel(x, y, beforeColor, afterColor);
		}

		Region region = new Region();
		for (Pixel p : tempPixelList) {
			region.pixels.add(new Pixel(p.x, p.y));
		}
		return region;
	}

	private void queueExpandPixel(int x, int y, int commonColor, int afterColor) {
		ArrayList<Pixel> queue = new ArrayList<Pixel>();
		queue.add(new Pixel(x, y));
		while (queue.size() > 0) {
			Pixel p = queue.get(0);
			if (contourImage.get(p.x, p.y) != commonColor) {
				queue.remove(0);
				continue;
			}
			tempPixelList.add(new Pixel(p.x, p.y));
			contourImage.set(p.x, p.y, afterColor);
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
