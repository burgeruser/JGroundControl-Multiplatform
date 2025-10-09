package com.serialcomm.controller;

import com.MAVLink.MAVLinkPacket;
import com.serialcomm.service.DeviceSelectionService;
import com.serialcomm.service.MavlinkDispatcher;
import com.serialcomm.service.VehicleState;
import com.serialcomm.util.LanguageManager;
import com.serialcomm.util.UiUpdateQueue;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Visual Real-time Status tab.
 * Left: attitude visualization (artificial horizon style).
 * Bottom: 18 key telemetry items in big font.
 * No extra smoothing; display values as-is from VehicleState.
 */
public class VisualStatusTabController extends BaseController {
    private static final Logger logger = LoggerFactory.getLogger(VisualStatusTabController.class);

    @FXML private Canvas attitudeCanvas;
    @FXML private GridPane grid;
    @FXML private Label platformLabel;
    @FXML private javafx.scene.layout.BorderPane rootPane;
    @FXML private javafx.scene.layout.VBox leftTopBox;
    @FXML private javafx.scene.layout.StackPane attitudePane;
    @FXML private javafx.scene.control.SplitPane outerSplit;
    @FXML private javafx.scene.control.SplitPane leftSplit;
    @FXML private Label hintLabel;
    @FXML private javafx.scene.layout.StackPane mapPlaceholder;
    @FXML private javafx.scene.control.ScrollPane bottomScroll;

    private final Label[] nameLabels = new Label[18];
    private final Label[] valueLabels = new Label[18];
    private java.util.List<Field> fieldOrder;
    private java.util.Map<Field, Integer> fieldToIndex;

    private enum Field {
        ROLL, PITCH, YAW,
        REL_ALT, VZ, CLIMB,
        ALT_BARO, LAT, LON,
        THROTTLE, VN, VE,
        GROUNDSPEED, COURSE, HEADING,
        PRESSURE, TEMPERATURE, UPTIME
    }

    @FXML
    public void initialize() {
        super.initialize();
        currentReceiveMode = ReceiveMode.HEX; // not used; driven by dispatcher events
        setupGrid();
        try {
            MavlinkDispatcher.getInstance().addListener(this::onPacket);
        } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("visual.subscribe", t); }
        bindResponsiveCanvas();
        redrawAttitude(0,0,0,0,0);
        updatePlatformLabel(null);
        bindDynamicFonts();
        // Listen to language change and refresh labels
        try {
            com.serialcomm.util.LanguageManager.getInstance().addLanguageChangeListener((oldL, newL) -> { updateUI(); try { if (mapView != null) mapView.refreshI18n(); } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("visual.map.refreshI18n", t); } });
        } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("visual.i18n.listen", t); }
        logger.info("VisualStatusTabController initialized");

        // Initialize maps service asynchronously and attach MapView on demand
		try {
			com.serialcomm.service.MapDataService.getInstance().scanAsync();
			com.serialcomm.service.MapDataService.getInstance().addOnScanFinished(() -> {
				try {
					Platform.runLater(() -> {
						if (mapView != null) {
							java.util.List<com.serialcomm.map.TileSource> offline = com.serialcomm.service.MapDataService.getInstance().getOfflineSources();
							for (com.serialcomm.map.TileSource ts : offline) mapView.addTileSource(ts);
							// default to first offline if available
							if (!offline.isEmpty()) {
								mapView.setActiveSourceById(offline.get(0).getId());
							}
						}
						// mark scan finished to enable auto-pick prompt
						mapScanFinished = true;
					});
            		} catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("visual.map.scan.attach", t); }
			});
		} catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("visual.map.scan", t); }
        initMapView();
    }

    private void bindResponsiveCanvas() {
        try {
            if (attitudeCanvas == null) return;
            if (attitudePane != null) {
                attitudePane.widthProperty().addListener((o, ov, nv) -> resizeCanvas());
                attitudePane.heightProperty().addListener((o, ov, nv) -> resizeCanvas());
            }
            if (outerSplit != null) outerSplit.getDividers().forEach(d -> d.positionProperty().addListener((o,ov,nv)->resizeCanvas()));
            if (leftSplit != null) leftSplit.getDividers().forEach(d -> d.positionProperty().addListener((o,ov,nv)->resizeCanvas()));
            resizeCanvas();
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("visual.bind.canvas", e); }
    }

    private void resizeCanvas() {
        try {
            if (attitudeCanvas == null) return;
            double padding = 8;
            double topW = Math.max(0, (attitudePane != null ? attitudePane.getWidth() : attitudeCanvas.getWidth()) - padding);
            double topH = Math.max(0, (attitudePane != null ? attitudePane.getHeight() : attitudeCanvas.getHeight()) - padding);
            if (topW <= 0 || topH <= 0) return;
            // Maintain aspect ratio (approx 4:3)
            double ratio = 0.75; // h = w * 3/4
            double w = Math.max(1, topW);
            double h = w * ratio;
            if (h > topH) {
                h = Math.max(1, topH);
                w = Math.min(topW, h / ratio);
            }
            attitudeCanvas.setWidth(w);
            attitudeCanvas.setHeight(h);
            // After resizing, trigger a redraw with last known values (we don't store them; draw neutral)
            redrawAttitude(0,0,0,0,0);
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("visual.resize.canvas", e); }
    }

    private void setupGrid() {
        // Define field order and index mapping (6 rows x 3 cols)
        fieldOrder = java.util.Arrays.asList(
            Field.ROLL, Field.PITCH, Field.YAW,
            Field.REL_ALT, Field.VZ, Field.CLIMB,
            Field.ALT_BARO, Field.LAT, Field.LON,
            Field.THROTTLE, Field.VN, Field.VE,
            Field.GROUNDSPEED, Field.COURSE, Field.HEADING,
            Field.PRESSURE, Field.TEMPERATURE, Field.UPTIME
        );
        fieldToIndex = new java.util.HashMap<>();
        for (int i = 0; i < fieldOrder.size(); i++) fieldToIndex.put(fieldOrder.get(i), i);

        // Clear previous children (in case of re-init) and build vertical item cards (3 cols x 6 rows)
        grid.getChildren().clear();
        for (int i = 0; i < 18; i++) {
            Label n = new Label(getFieldName(fieldOrder.get(i)) + "：");
            n.setWrapText(true);
            n.setMaxWidth(Double.MAX_VALUE);
            n.setAlignment(javafx.geometry.Pos.CENTER);
            n.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

            Label v = new Label("-");
            v.setWrapText(true);
            v.setMaxWidth(Double.MAX_VALUE);
            v.setAlignment(javafx.geometry.Pos.CENTER);
            v.setStyle("-fx-font-size: 22px;");

            nameLabels[i] = n; valueLabels[i] = v;

            javafx.scene.layout.VBox box = new javafx.scene.layout.VBox();
            box.setSpacing(2.0);
            box.setAlignment(javafx.geometry.Pos.CENTER);
            box.getChildren().addAll(n, v);
            box.setStyle("-fx-border-color: rgba(120,120,120,0.40); -fx-border-radius: 6; -fx-background-radius: 6; -fx-border-insets: 2; -fx-padding: 6 8 6 8;");

            int row = i / 3; int col = (i % 3);
            grid.add(box, col, row);
            javafx.scene.layout.GridPane.setFillWidth(box, true);
            javafx.scene.layout.GridPane.setFillHeight(box, true);
            javafx.scene.layout.GridPane.setHgrow(box, javafx.scene.layout.Priority.ALWAYS);
            javafx.scene.layout.GridPane.setVgrow(box, javafx.scene.layout.Priority.SOMETIMES);
        }
    }

    private void onPacket(MAVLinkPacket pkt) {
        // Respect global device filter (ALL = no filter)
        boolean filtered = false;
        try {
            int selSys = DeviceSelectionService.getInstance().selectedSys();
            int selComp = DeviceSelectionService.getInstance().selectedComp();
            filtered = !(selSys < 0 && selComp < 0);
            if (filtered) {
                if (selSys >= 0 && pkt.sysid != selSys) return;
                if (selComp >= 0 && pkt.compid != selComp) return;
            }
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("visual.device.filter", e); }

        VehicleState.Snapshot s = VehicleState.getInstance().getSnapshotOf(pkt.sysid, pkt.compid);
        if (s == null) return;

        // Update platform label
        updatePlatformLabel(s);
        // Show selection hint when multiple devices exist and ALL is selected
        updateSelectDeviceHint();

        // Prepare values (units & precision per requirements)
        double rollDeg = Math.toDegrees(s.rollRad);
        double pitchDeg = Math.toDegrees(s.pitchRad);
        double yawDeg = Math.toDegrees(s.yawRad);
        // Normalize yaw to [-180,180] display per ATTITUDE; separate Course uses hdg

        double tempC = s.temperatureCdegC / 100.0;
        double uptimeS = s.timeBootMs / 1000.0;
        double relAltM = s.relativeAltMm / 1000.0;
        double altBaroM = s.vfrAltM;
        double climbMs = s.vfrClimbMs;
        int throttlePct = s.vfrThrottlePct;
        int headingDeg = s.vfrHeadingDeg;
        double groundspeed = s.vfrGroundspeedMs;
        double lat = s.latE7 / 1e7;
        double lon = s.lonE7 / 1e7;
        double vnMs = s.vxCms / 100.0;
        double veMs = s.vyCms / 100.0;
        double vdMs = s.vzCms / 100.0;
        double courseDeg = s.hdgCdeg > 0 ? (s.hdgCdeg / 100.0) : Double.NaN;
        double pressHpa = s.pressAbsHpa;

        UiUpdateQueue.get().submit("visual.update", () -> {
            setField(Field.ROLL, String.format("%.2f °", rollDeg));
            setField(Field.PITCH, String.format("%.2f °", pitchDeg));
            setField(Field.YAW, String.format("%.2f °", yawDeg));
            setField(Field.REL_ALT, String.format("%.2f m", relAltM));
            setField(Field.VZ, String.format("%.2f m/s", vdMs));
            setField(Field.CLIMB, String.format("%.2f m/s", climbMs));
            setField(Field.ALT_BARO, String.format("%.2f m", altBaroM));
            setField(Field.LAT, String.format("%.7f", lat));
            setField(Field.LON, String.format("%.7f", lon));
            setField(Field.THROTTLE, throttlePct >= 0 ? (throttlePct + " %") : "-");
            setField(Field.VN, String.format("%.2f m/s", vnMs));
            setField(Field.VE, String.format("%.2f m/s", veMs));
            setField(Field.GROUNDSPEED, String.format("%.2f m/s", groundspeed));
            setField(Field.COURSE, !Double.isNaN(courseDeg) ? String.format("%.2f °", courseDeg) : "-");
            setField(Field.HEADING, headingDeg >= 0 ? (headingDeg + " °") : "-");
            setField(Field.PRESSURE, pressHpa > 0 ? String.format("%.2f hPa", pressHpa) : "-");
            setField(Field.TEMPERATURE, String.format("%.2f °C", tempC));
            setField(Field.UPTIME, formatUptime(uptimeS));
            redrawAttitude(rollDeg, pitchDeg, yawDeg, headingDeg, throttlePct);
            // Update map center and yaw
            try { updateMap(lat, lon, isValidLatLon(s), yawDeg); } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("visual.map.update", t); }
        });
    }

    private boolean isValidLatLon(VehicleState.Snapshot s) {
        try {
            if (s == null) return false;
            if (s.latE7 == 0 && s.lonE7 == 0) return false; // 0,0 invalid default
            double lat = s.latE7 / 1e7; double lon = s.lonE7 / 1e7;
            return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
        } catch (Throwable t) { return false; }
    }

    private void updatePlatformLabel(VehicleState.Snapshot s) {
        UiUpdateQueue.get().submit("visual.platform", () -> {
            String vendor = "-";
            try {
                if (s != null) {
                    int ap = s.autopilot;
                    int APM = com.MAVLink.enums.MAV_AUTOPILOT.MAV_AUTOPILOT_ARDUPILOTMEGA;
                    int PX4 = com.MAVLink.enums.MAV_AUTOPILOT.MAV_AUTOPILOT_PX4;
                    vendor = (ap == PX4) ? "PX4" : (ap == APM ? "ArduPilot" : "-");
                }
            } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("visual.platform.vendor", t); }
            String prefix = LanguageManager.getInstance().getString("ui.visual.platform");
            platformLabel.setText(prefix + ": " + vendor);
        });
    }

    private void redrawAttitude(double rollDeg, double pitchDeg, double yawDeg, double headingDeg, int throttlePct) {
        if (attitudeCanvas == null) return;
        GraphicsContext g = attitudeCanvas.getGraphicsContext2D();
        double w = attitudeCanvas.getWidth();
        double h = attitudeCanvas.getHeight();
        // clear
        g.clearRect(0,0,w,h);
        // artificial horizon: sky/ground with pitch shift, rotated by roll
        g.save();
        g.translate(w/2, h/2);
        g.rotate(-rollDeg);
        double pixelsPerDeg = 2.0; // pitch visual scale
        // invert pitch direction to match MissionPlanner convention
        double yShift = -pitchDeg * pixelsPerDeg;
        // sky
        g.setFill(javafx.scene.paint.Color.web("#4A90E2"));
        g.fillRect(-w, -h - yShift, 2*w, h);
        // ground
        g.setFill(javafx.scene.paint.Color.web("#C09048"));
        g.fillRect(-w, 0 - yShift, 2*w, h);
        // horizon line
        // horizon line
        g.setStroke(javafx.scene.paint.Color.color(1,1,1,1.0));
        g.setLineWidth(2);
        g.strokeLine(-w, -yShift, w, -yShift);
        // pitch ladder (semi-transparent white) — length scales with instrument size
        g.setStroke(javafx.scene.paint.Color.color(1,1,1,0.7));
        g.setLineWidth(2);
        g.setFill(javafx.scene.paint.Color.color(1,1,1,0.8));
        final double ladderScale = Math.min(w, h);
        for (int p = -30; p <= 30; p += 5) {
            double yLine = -yShift - p * pixelsPerDeg;
            if (yLine < -h/2 - 20 || yLine > h/2 + 20) continue;
            boolean major = (p % 10) == 0;
            double len = major ? Math.max(50.0, ladderScale * 0.30) : Math.max(32.0, ladderScale * 0.18);
            g.strokeLine(-len/2.0, yLine, len/2.0, yLine);
            if (major && p != 0) {
                String txt = String.format("%d°", Math.abs(p));
                g.fillText(txt, (len/2.0) - 10.0, yLine + 5);
            }
        }
        g.restore();
        // fixed fuselage marker
        g.setStroke(javafx.scene.paint.Color.WHITE);
        g.setLineWidth(3);
        g.strokeLine(w/2 - 40, h/2, w/2 + 40, h/2);
        g.strokeOval(w/2 - 4, h/2 - 4, 8, 8);

        // roll scale (now bottom arc only); draw arc and ticks on lower half without clipping
        double cx = w/2, cy = h/2;
        double rRoll = Math.min(w, h) * 0.42;
        g.setStroke(javafx.scene.paint.Color.web("#FFD700")); // opaque yellow for roll arc
        g.setLineWidth(2);
        // bottom half segment: center-bottom ±70° around bottom (polar: 110°..250°)
        drawArcPolar(g, cx, cy, rRoll, 110.0, 140.0);
        int[] majorAngles = new int[]{-60,-45,-30,-20,-10,0,10,20,30,45,60};
        for (int a : majorAngles) {
            // shift ticks by 180° so they lie on the bottom half (use short tick length)
            double theta = Math.toRadians(a + 180);
            double tickLen = (Math.abs(a)%10==0?12:8);
            double rxOut = cx + rRoll * Math.sin(theta);
            double ryOut = cy - rRoll * Math.cos(theta);
            double rxIn = cx + (rRoll - tickLen) * Math.sin(theta);
            double ryIn = cy - (rRoll - tickLen) * Math.cos(theta);
            g.strokeLine(rxIn, ryIn, rxOut, ryOut);
        }
        // roll pointer: short radial segment around the roll arc (bottom-half aligned)
        double thetaRoll = Math.toRadians(rollDeg + 180.0);
        double rOut = rRoll + 12;
        double rIn = rRoll - 8;
        double rollPtXOut = cx + rOut * Math.sin(thetaRoll);
        double rollPtYOut = cy - rOut * Math.cos(thetaRoll);
        double rollPtXIn = cx + rIn * Math.sin(thetaRoll);
        double rollPtYIn = cy - rIn * Math.cos(thetaRoll);
        g.setLineWidth(3);
        g.strokeLine(rollPtXIn, rollPtYIn, rollPtXOut, rollPtYOut);

        // heading compass ring
        double rCompass = rRoll + 22;
        g.save();
        g.translate(cx, cy);
        g.setStroke(javafx.scene.paint.Color.web("#20B2AA")); // opaque teal (blue-green) for compass
        g.setLineWidth(1.5);
        // keep text upright: do not rotate GC for ticks labels; draw ticks by math, and draw labels without rotation
        // draw compass circle
        g.strokeOval(-rCompass, -rCompass, rCompass*2, rCompass*2);
        for (int deg = 0; deg < 360; deg += 10) {
            double t = Math.toRadians(deg - headingDeg);
            boolean major = (deg % 30) == 0;
            double len = major ? 12 : 6;
            double cxOut = (rCompass) * Math.sin(t);
            double cyOut = -(rCompass) * Math.cos(t);
            double cxIn = (rCompass - len) * Math.sin(t);
            double cyIn = -(rCompass - len) * Math.cos(t);
            g.strokeLine(cxIn, cyIn, cxOut, cyOut);
            if (major) {
                String label;
                switch (deg) {
                    case 0: label = "N"; break;
                    case 90: label = "E"; break;
                    case 180: label = "S"; break;
                    case 270: label = "W"; break;
                    default: label = Integer.toString(deg);
                }
                javafx.scene.paint.Paint old = g.getFill();
                if ("N".equals(label) || "E".equals(label) || "S".equals(label) || "W".equals(label)) {
                    g.setFill(javafx.scene.paint.Color.BLACK);
                    g.setFont(javafx.scene.text.Font.font(null, javafx.scene.text.FontWeight.BOLD, 13));
                } else {
                    g.setFill(javafx.scene.paint.Color.BLACK);
                    g.setFont(javafx.scene.text.Font.font(11));
                }
                // place labels outside the compass ring to avoid interfering with roll markings
                double rLabel = rCompass + Math.max(14.0, rRoll * 0.09);
                double tx = rLabel * Math.sin(t);
                double ty = -rLabel * Math.cos(t);
                g.fillText(label, tx - 8, ty + 4);
                g.setFill(old);
            }
        }
        g.restore();
        // fixed heading index
        double ix = cx, iy = cy - rCompass - 6;
        g.setFill(javafx.scene.paint.Color.WHITE);
        g.fillPolygon(new double[]{ix-6, ix+6, ix}, new double[]{iy, iy, iy-10}, 3);

        // throttle semicircle arc (now top half, like tachometer)
        try {
            int throttle = (int) Math.max(0, Math.min(100, throttlePct));
            double rTh = rRoll - 30;
            // background arc (semi-transparent light blue)
            g.setStroke(javafx.scene.paint.Color.web("#ADD8E6", 0.55));
            g.setLineWidth(8);
            // top half segment: center-top ±70° around top (polar: -70°..+70°)
            drawArcPolar(g, cx, cy, rTh, -70.0, 140.0);
            // value arc with smooth gradient: 0% = green, 50% = yellow, 100% = red
            double t = throttle / 100.0;
            double sweep = 140 * t;
            double r, gcol;
            if (t <= 0.5) {
                double k = t / 0.5; // 0..1
                r = k;         // 0 -> 1
                gcol = 1.0;    // stay at 1
            } else {
                double k = (t - 0.5) / 0.5; // 0..1
                r = 1.0;       // stay at 1
                gcol = 1.0 - k; // 1 -> 0
            }
            g.setStroke(javafx.scene.paint.Color.color(Math.max(0.0, Math.min(1.0, r)), Math.max(0.0, Math.min(1.0, gcol)), 0.0));
            // draw the value arc only when throttle > 0 to avoid showing green at 0%
            if (sweep > 0.0) {
                drawArcPolar(g, cx, cy, rTh, -70.0, sweep);
            }
                    } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("visual.render.throttle", t); }
    }

    // Draw an open arc using polar angles (degrees) with 0° pointing up, positive clockwise
    private void drawArcPolar(GraphicsContext g, double cx, double cy, double radius, double startDeg, double sweepDeg) {
        // return early for non-positive sweep to avoid stray marks
        if (sweepDeg <= 0.0) return;
        // Approximate the arc with short line segments for stability and to keep control of angle conventions
        int steps = Math.max(12, (int) Math.ceil(Math.abs(sweepDeg))); // 1° per segment, min 12
        double step = sweepDeg / steps;
        double a0 = startDeg;
        for (int i = 0; i < steps; i++) {
            double a1 = a0 + step;
            double t0 = Math.toRadians(a0);
            double t1 = Math.toRadians(a1);
            double x0 = cx + radius * Math.sin(t0);
            double y0 = cy - radius * Math.cos(t0);
            double x1 = cx + radius * Math.sin(t1);
            double y1 = cy - radius * Math.cos(t1);
            g.strokeLine(x0, y0, x1, y1);
            a0 = a1;
        }
    }

    private String formatUptime(double sec) {
        if (sec < 0) return "-";
        long s = (long) sec;
        long h = s / 3600; s %= 3600;
        long m = s / 60; s %= 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    @Override
    protected void onDataReceived(String data) {
        // Data path driven by central dispatcher; no work here.
    }

    public void updateUI() {
        LanguageManager lm = LanguageManager.getInstance();
        if (lm == null) return;
        UiUpdateQueue.get().submit("visual.updateUI", () -> {
            try {
                String text = platformLabel.getText();
                String vendor = text;
                int idx = text.indexOf(':');
                if (idx >= 0 && idx + 1 < text.length()) vendor = text.substring(idx + 1).trim();
                int idxCn = text.indexOf('：');
                if (idxCn >= 0 && idxCn + 1 < text.length()) vendor = text.substring(idxCn + 1).trim();
                platformLabel.setText(lm.getString("ui.visual.platform") + ": " + vendor);
            } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("visual.updateUI.platformLabel", e); }
            for (int i = 0; i < 18 && i < nameLabels.length; i++) {
                Field f = fieldOrder.get(i);
                if (nameLabels[i] != null) nameLabels[i].setText(getFieldName(f) + "：");
            }
            updateSelectDeviceHint();
            // ensure font sizes re-evaluated after language refresh
            scheduleFontUpdate();
        });
    }

    private String getFieldName(Field f) {
        LanguageManager lm = LanguageManager.getInstance();
        switch (f) {
            case ROLL: return lm.getString("ui.visual.roll");
            case PITCH: return lm.getString("ui.visual.pitch");
            case YAW: return lm.getString("ui.visual.yaw");
            case REL_ALT: return lm.getString("ui.visual.rel_alt");
            case VZ: return lm.getString("ui.visual.vd");
            case CLIMB: return lm.getString("ui.visual.climb");
            case ALT_BARO: return lm.getString("ui.visual.alt_baro");
            case LAT: return lm.getString("ui.visual.lat");
            case LON: return lm.getString("ui.visual.lon");
            case THROTTLE: return lm.getString("ui.visual.throttle");
            case VN: return lm.getString("ui.visual.vn");
            case VE: return lm.getString("ui.visual.ve");
            case GROUNDSPEED: return lm.getString("ui.visual.groundspeed");
            case COURSE: return lm.getString("ui.visual.course");
            case HEADING: return lm.getString("ui.visual.heading");
            case PRESSURE: return lm.getString("ui.visual.pressure");
            case TEMPERATURE: return lm.getString("ui.visual.temperature");
            case UPTIME: return lm.getString("ui.visual.uptime");
            default: return "";
        }
    }

    private void setField(Field f, String value) {
        Integer idx = fieldToIndex.get(f);
        if (idx == null) return;
        if (idx >= 0 && idx < valueLabels.length && valueLabels[idx] != null) {
            valueLabels[idx].setText(value);
        }
    }

    // ---------------- Dynamic font sizing for bottom 18 items ----------------
    private void bindDynamicFonts() {
        try {
            if (bottomScroll != null) {
                bottomScroll.viewportBoundsProperty().addListener((o, ov, nv) -> scheduleFontUpdate());
            }
            if (leftSplit != null) {
                leftSplit.getDividers().forEach(d -> d.positionProperty().addListener((o, ov, nv) -> scheduleFontUpdate()));
            }
            if (outerSplit != null) {
                outerSplit.getDividers().forEach(d -> d.positionProperty().addListener((o, ov, nv) -> scheduleFontUpdate()));
            }
            if (grid != null) {
                grid.widthProperty().addListener((o, ov, nv) -> scheduleFontUpdate());
                grid.heightProperty().addListener((o, ov, nv) -> scheduleFontUpdate());
            }
            scheduleFontUpdate();
        } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("visual.font.bind", t); }
    }

    private void scheduleFontUpdate() {
        UiUpdateQueue.get().submit("visual.grid.font", this::applyDynamicFonts);
    }

    private void applyDynamicFonts() {
        try {
            if (grid == null) return;
            double viewW = (bottomScroll != null && bottomScroll.getViewportBounds() != null) ? bottomScroll.getViewportBounds().getWidth() : grid.getWidth();
            double viewH = (bottomScroll != null && bottomScroll.getViewportBounds() != null) ? bottomScroll.getViewportBounds().getHeight() : grid.getHeight();
            if (viewW <= 0 || viewH <= 0) return;

            javafx.geometry.Insets pad = grid.getPadding();
            double padL = pad != null ? pad.getLeft() : 0.0;
            double padR = pad != null ? pad.getRight() : 0.0;
            double padT = pad != null ? pad.getTop() : 0.0;
            double padB = pad != null ? pad.getBottom() : 0.0;
            double hgap = grid.getHgap();
            double vgap = grid.getVgap();

            int cols = 3; // 3 cards per row
            int rows = 6;
            double usableW = Math.max(1.0, viewW - padL - padR - hgap * (cols - 1));
            double usableH = Math.max(1.0, viewH - padT - padB - vgap * (rows - 1));
            double colW = Math.max(1.0, usableW / cols);
            double rowH = Math.max(1.0, usableH / rows);

            // derive font sizes from width & height constraints; clamp to reasonable ranges
            double nameByW = colW / 10.5;   // label centered on top
            double valueByW = colW / 6.5;   // value larger
            double nameByH = rowH / 2.4;    // allocate roughly 40% height to label
            double valueByH = rowH / 1.6;   // allocate roughly 60% height to value
            double nameSize = clamp(Math.min(nameByW, nameByH), 10.0, 24.0);
            double valueSize = clamp(Math.min(valueByW, valueByH), 14.0, 36.0);

            String nameStyle = String.format("-fx-font-size: %.1fpx; -fx-font-weight: bold;", nameSize);
            String valueStyle = String.format("-fx-font-size: %.1fpx;", valueSize);
            for (int i = 0; i < nameLabels.length; i++) {
                if (nameLabels[i] != null) nameLabels[i].setStyle(nameStyle);
                if (valueLabels[i] != null) valueLabels[i].setStyle(valueStyle);
            }
        } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("visual.font.apply", t); }
    }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private void updateSelectDeviceHint() {
        try {
            if (hintLabel == null) return;
            int selSys = com.serialcomm.service.DeviceSelectionService.getInstance().selectedSys();
            int selComp = com.serialcomm.service.DeviceSelectionService.getInstance().selectedComp();
            java.util.List<String> devices = com.serialcomm.service.DeviceSelectionService.getInstance().enumerateDevices();
            boolean multiple = devices != null && devices.size() > 2; // ALL + at least two devices
            boolean allSelected = selSys < 0 && selComp < 0;
            if (multiple && allSelected) {
                String msg = LanguageManager.getInstance().getString("ui.visual.hint.select.device");
                hintLabel.setText(msg);
                hintLabel.setVisible(true);
            } else {
                hintLabel.setVisible(false);
                hintLabel.setText("");
            }
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("visual.hint.update", e); }
    }

    /** Called when global device selection changes to refresh hint immediately. */
    public void onGlobalDeviceChanged() {
        UiUpdateQueue.get().submit("visual.hint", this::updateSelectDeviceHint);
    }

    // ---------------- Map embedding ----------------
    private volatile com.serialcomm.map.MapView mapView;

    private void initMapView() {
        try {
            if (mapPlaceholder == null) return;
                if (mapView == null) {
                    mapView = new com.serialcomm.map.MapView();
                    // allow SplitPane to shrink the map side to very small width
                    mapView.setMinSize(0.0, 0.0);
                    mapPlaceholder.setMinSize(0.0, 0.0);
                // Add offline first, then online
                java.util.List<com.serialcomm.map.TileSource> offline = com.serialcomm.service.MapDataService.getInstance().getOfflineSources();
                java.util.List<com.serialcomm.map.TileSource> online = com.serialcomm.service.MapDataService.getInstance().getOnlineSources();
                for (com.serialcomm.map.TileSource ts : offline) mapView.addTileSource(ts);
                for (com.serialcomm.map.TileSource ts : online) mapView.addTileSource(ts);
                // Restore saved active source if present
                try {
                    String savedId = com.serialcomm.util.Settings.getSavedMapSourceId();
                    if (savedId != null && !savedId.isEmpty()) mapView.setActiveSourceById(savedId);
                } catch (Throwable ignore) {}
                mapPlaceholder.getChildren().setAll(mapView);
            }
        } catch (Exception e) {
            logger.error("initMapView failed: {}", e.getMessage(), e);
            com.serialcomm.util.ErrorMonitor.record("visual.map.init", e);
        }
    }

    @Override
    public void cleanup() {
        try {
            super.cleanup();
            if (mapView != null) { mapView.cleanup(); }
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("visual.cleanup", e); }
    }

    private void updateMap(double lat, double lon, boolean valid, double yawDeg) {
        try {
            if (mapView == null) return;
            double yaw = !Double.isNaN(yawDeg) ? yawDeg : 0.0;
            mapView.setCenterAndYaw(valid ? lat : 0.0, valid ? lon : 0.0, yaw, valid);
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("visual.map.update", e); }
    }

	// ---------------- Auto-select offline map (one-shot) ----------------
	private volatile boolean mapAutoPickPrompted = false;
	private volatile boolean mapScanFinished = false;

    private void maybeAutoSelectOfflineMap(double lat, double lon) {
		try {
			if (mapAutoPickPrompted) return;
			// Only proceed after maps scan finished (initialized in initialize())
			if (!mapScanFinished) return;
			java.util.List<com.serialcomm.map.TileSource> matches = com.serialcomm.service.MapDataService.getInstance().matchOfflineByPoint(lat, lon);
			if (matches == null) matches = java.util.Collections.emptyList();
			mapAutoPickPrompted = true; // guard prompt duplication
			if (matches.isEmpty()) {
				Platform.runLater(() -> {
					try {
						javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
						alert.setTitle(com.serialcomm.util.LanguageManager.getInstance().getString("ui.map.offline.none.title"));
						alert.setHeaderText(com.serialcomm.util.LanguageManager.getInstance().getString("ui.map.offline.none.header"));
						alert.setContentText("");
						alert.showAndWait();
					} catch (Throwable ignore) {}
				});
				return;
			}
			if (matches.size() == 1) {
				com.serialcomm.map.TileSource ts = matches.get(0);
                Platform.runLater(() -> { try { if (mapView != null) mapView.setActiveSourceById(ts.getId()); } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("visual.map.setActive", t); } });
				return;
			}
			// Multiple matches: show i18n ChoiceDialog
			final java.util.List<com.serialcomm.map.TileSource> options = new java.util.ArrayList<>(matches);
			Platform.runLater(() -> {
				try {
					javafx.collections.ObservableList<String> names = javafx.collections.FXCollections.observableArrayList();
					for (com.serialcomm.map.TileSource t : options) names.add(t.getName() != null ? t.getName() : t.getId());
					javafx.scene.control.ChoiceDialog<String> dlg = new javafx.scene.control.ChoiceDialog<>(names.get(0), names);
					dlg.setTitle(com.serialcomm.util.LanguageManager.getInstance().getString("ui.map.offline.choice.title"));
					dlg.setHeaderText(com.serialcomm.util.LanguageManager.getInstance().getString("ui.map.offline.choice.header"));
					dlg.setContentText("");
					java.util.Optional<String> res = dlg.showAndWait();
                    if (res.isPresent()) {
						String picked = res.get();
						for (com.serialcomm.map.TileSource t : options) {
							String nm = (t.getName() != null && !t.getName().trim().isEmpty()) ? t.getName() : t.getId();
                            if (nm.equals(picked)) { try { if (mapView != null) mapView.setActiveSourceById(t.getId()); } catch (Throwable ex) { com.serialcomm.util.ErrorMonitor.record("visual.map.setActive", ex); } break; }
						}
					}
                } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("visual.dialog.choice", t); }
			});
        } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("visual.auto.pick", t); }
	}
}

