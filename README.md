## Extraktion der rechten und linken Egospurmarkierung mittels Line-Growing

### Nutzerdokumentation

#### Vorraussetzungen
Das Canny Edge Detection Plugin muss im ImageJ-Pluginverzeichnis installiert werden (https://imagej.nih.gov/ij/plugins/canny/index.html).
Das Lane Detection Plugin muss unter Pfad/zu/ImageJ/plugins/laneDetection installiert werden.

### Nutzung
Das Plugin kann über die Kommandozeile mit folgendem Befehl aus dem Verzeichnis der `ij.jar` heraus aufgerufen werden:

	java -jar ij.jar -ijpath Pfad/zu/ImageJ -eval 'run("Detect Lanes",
	 "-i /Absoluter/Pfad/zum/Bild.png")'

Zusätzlich stehen einige Parameter zur Verfügung:
- `-o`: Absoluter Pfad der Ausgabedatei
- `-l`: Mindestgröße (0 .. 1) einer Region, die als Teil der Spurmarkierung erkannt werden soll. (*Standard: 0*)
- `-u`: Maximalgröße (0 .. 1) einer Region, die als Teil der Spurmarkierung erkannt werden soll. (*Standard: 0.1*)
- `-g`: Stärke des Weichzeichnens des Canny Edge Detectors (*Standard: 2.0*)
- `-c`: Untergrenze des Canny Edge Detectors. (*Standard: 2.5*)
- `-C`: Obergrenze des Canny Edge Detectors. (*Standard: 7.5*)
- `-e`: Erweiterter Modus wird übersprungen, "fleckige" Straßen werden schlechter erkannt
- `-d`: Maximaler Intensitätsunterschied (0 .. 255), die bei der Erkennung von Markierungsteilen toleriert wird. (*Standard: 10*)
- `-v`: Gesprächiger Modus (aktiviert zusätzliche Konsolenausgaben)

### Entwicklerdokumentation

#### Aufgabe
Die Aufgabe ist die Erkennung sämtlicher relevanter Fahrspurmarkierungen und die gesonderte Hervorhebung der Egospurmarkierungen unter Einsatz des Line-Growing bzw. Region-Growing Verfahrens. Grundlegendes Lösungskonzept ist dabei die Erkennung des Ashphalts mittels Region Growing (Floodfill), um das Gebiet zur Suche von Spurmarkierungen einzugrenzen.

#### Pipeline

##### 8bit + ROI
Zunächst wird eine 8-Bit-Kopie der unteren Hälfte des Originalbildes erzeugt.

##### Canny Edge + Dilate
Mittels Canny Edge Detector werden die Konturen des Straße extrahiert. Dabei handelt es sich um ein externes Plugin, welches zuvor in ImageJ installiert werden muss (https://imagej.nih.gov/ij/plugins/canny/index.html). Da das Plugin die Kanten der Straße herausarbeitet - vorrausgetzt, klare Straßenbegrenzungslinine sind vorhanden - muss das nun folgende Region Growing keine Toleranzkriterien erfüllen. Das Resultat der Canny Edge Detection wird zweimal binär dilatiert, um Lücken zwischen den Kanten zu schließen. Außerdem wird das Bild an allen Seiten beschnitten (cropping), da der Canny Edge Detector einen gewissen Bereich zum Bildrand offen lässt.

##### Floodfil Asphalt
Ausgehend von der Mitte des unteren Bildrands wird ein Region Growing durchgeführt, welches den Asphalt ausfüllt. Aus dem gefüllten Bereich wird ein neues Bild für die folgenden Bearbeitungsschritte generiert (Asphalt Image). Außerdem werden der linke- sowie der rechte Rand der Ashphalt-Region pauschal als äußere Fahrspuren betrachtet.

##### Übrige Regionen extrahieren
Es folgt das Connected-Components Verfahren. Dabei werden die übrigen weißen Bereiche des Asphalt-Bildes gefüllt und jeweils als separate Regionen betrachtet.

##### Regionen filtern
Die erkannten Regionen werden gefiltert. Ziel ist, alle Regionen zu eleminieren, die keine Spurmarkierung darstellen. Die Filterkriterien sind einerseits die Regionsgröße sowie die durchschnittliche Farbe der Regionen. Zu große Regionen (wie zum Beispiel der Himmel) bzw. zu kleine Regionen (Rauschen) werden entfernt (siehe hierzu die Kommandozeilen-Parameter in der Nutzerdokumentation). Aus den verbliebenen Regionen werden die mittleren Farbwerte der enthaltenen Pixel errechnet. Regionen, die die mittlere Pixelintensität der obersten beiden Regionen mit einer parametrisierbaren Toleranz unterschreiten, werden elimiert.

##### Regionen verbinden
Die übrigen Regionen stellen alle erkannten Fahrbahnmarkierungen innerhalb des Asphalts dar. Die Markierungen müssen nun noch so verbunden werden, dass sie als durchgängige Linien darstellbar sind. Dazu startet das Plugin bei der obersten Region und findet deren oberen und unteren Mittelpunkt. Ist der untere Mittelpunkt relativ nach links versetzt, wird links nach weiteren Regionen gesucht, andererseits rechts. Auf der entsprechenden Seite wird nun die Region identifiziert, deren oberer Mittelpunkt den kürzesten Abstand zum unteren Mittelpunkt der aktuellen Region aufweist. Befindet sich die gefundene Region unterhalb der aktuellen, wird sie als unterer Nachfolger bestimmt. Die aktuelle Region wird nun aus der Liste zu betrachtender Regionen entfernt und der Prozess mit der Nachfolgerregion wiederholt. Befindet sich die Nachfolgerregion jedoch über der aktuellen Region, wird eine neue Fahrlinie eröffnet und der Algorithmus startet abermals mit der obserten noch zur Verfügung stehenden Region.

Ergebnis dieses Prozesses sind die mittleren Fahrspuren. Alle gefundenen äußeren und mittleren Fahrspuren werden nun als eine gemeinsame Fahrspurmenge betrachtet. Diejenige Fahrspur, die sich von der Mitte des Bildes aus betrachtet als nächstes links befindet, wird als linke Egospur-Markierung gesetzt. Analog wird mit der rechten Egospur-Markierung verfahren.

#### Der Extra-Modus (-e)
Vor der eigentlichen Pipeline versucht der Algorithmus die Bildverarbeitung mit einem speziellen Vorverarbeitungsschritt: Das Anwenden der "exp()"-Funktion, um farbliche Unebenheiten der Straße zu relativieren. Der Trade-Off dieser Technik ist der Verlust der oberen "Abgrenzung" des Asphaltbereiches. Darum schneidet der Algorithmus vor dem Anwenden der exp()-Funktion 20px vom oberen Rand der Bildes ab. Dieser Prozess wiederholt sich so lange, bis nur noch der Asphalt beim Floodfill ausgeführt wird, jedoch höchstens bis ein viertel der originalen Bildhöhe erreicht wurde. Schlägt der Modus fehl, wird der Prozess mit wird, die Pipeline ganz ohne Anwendung der exp()-Funktion angewendet

![](Pipeline.png "Ablaufübersicht")
