package com.serialcomm.controller;

import com.serialcomm.controller.BaseController.StatusCallback;
import com.serialcomm.serial.SerialRouter;
import com.serialcomm.util.SerialExceptionHandler;
import javafx.application.Platform;
import com.serialcomm.util.UiUpdateQueue;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MAVLink frame extraction tab controller.
 * Implements identification, extraction, and visualization of MAVLink frames.
 */
public class MavlinkTabController extends BaseController implements StatusCallback {
    /** Logger */
    private static final Logger logger = LoggerFactory.getLogger(MavlinkTabController.class);
    
    // Legacy hand-rolled parse constants retained; gradually superseded by Parser
    private static final byte MAVLINK_V1_START = (byte) 0xFE;
    private static final byte MAVLINK_V2_START = (byte) 0xFD;
    private static final int MAVLINK_V1_HEADER_LEN = 6;
    private static final int MAVLINK_V2_HEADER_LEN = 10;
    private static final int MAVLINK_MAX_PAYLOAD_LEN = 255;
    
    // UI components
    @FXML
    private ComboBox<String> protocolVersionComboBox;
    @FXML
    private Label protocolVersionLabel;
    @FXML
    private TextArea rawDataTextArea;
    @FXML
    private Label rawDataLabel;
    @FXML
    private TextFlow extractedFramesFlow;
    @FXML
    private ScrollPane extractedFramesScrollPane;
    @FXML
    private Label extractedFramesLabel;
    @FXML
    private Label frameStatsLabel;
    @FXML
    private Label totalFramesLabel;
    @FXML
    private Label successFramesLabel;
    @FXML
    private Label failedFramesLabel;
    @FXML
    private Label crcErrorsLabel;
    @FXML
    private Label frameRateLabel;
    @FXML
    private Button clearFramesButton;
    @FXML
    private CheckBox lightModeCheckBox;
    
    /** Protocol version selector */
    public enum ProtocolVersion {
        V1_ONLY("MAVLink v1.0"),
        V2_ONLY("MAVLink v2.0"),
        AUTO("Auto detect");
        
        private final String displayName;
        
        ProtocolVersion(String displayName) {
            this.displayName = displayName;
        }
        
        @Override
        public String toString() {
            return displayName;
        }
    }
    
    /** Currently selected protocol version */
    private ProtocolVersion currentProtocolVersion = ProtocolVersion.AUTO;
    
    /** Byte buffer for incremental extraction (legacy path) */
    private final List<Byte> dataBuffer = new ArrayList<>();
    
    /** Frame statistics */
    private final AtomicLong totalFrames = new AtomicLong(0);
    private final AtomicLong successFrames = new AtomicLong(0);
    private final AtomicLong failedFrames = new AtomicLong(0);
    private final AtomicLong crcErrors = new AtomicLong(0);
    private final AtomicInteger frameRate = new AtomicInteger(0);
    
    /** Frame rate computation helpers */
    private long lastFrameTime = System.currentTimeMillis();
    private long frameCount = 0;

    // Use generated library Parser for actual CRC checking and frame parsing
    private final com.MAVLink.Parser parser = new com.MAVLink.Parser();
    // Light mode listener reference
    private java.util.function.Consumer<com.MAVLink.MAVLinkPacket> lightModeListener;
    
    /** Initialize UI controls, listeners, and defaults. */
    @FXML
    public void initialize() {
        try {
            super.initialize();
            
            // Create exception handler bound to the raw data area
            exceptionHandler = new SerialExceptionHandler(rawDataTextArea);
            
            // Initialize protocol version selector with localized options
            protocolVersionComboBox.setItems(FXCollections.observableArrayList(
                languageManager.getString("ui.mavlink.version.auto"),
                languageManager.getString("ui.mavlink.version.v1"),
                languageManager.getString("ui.mavlink.version.v2")
            ));
            protocolVersionComboBox.setValue(languageManager.getString("ui.mavlink.version.auto"));
            
            // React to protocol version changes
            protocolVersionComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null) {
                    handleProtocolVersionChange(newVal);
                }
            });
            
            // Clear button wiring
            if (clearFramesButton != null) {
                clearFramesButton.setOnAction(e -> clearFrames());
            }
            // Light mode: enabled by default. Show central parsed summaries, skip per-byte parsing
            if (lightModeCheckBox != null) {
                lightModeCheckBox.setSelected(true);
                attachLightMode();
                lightModeCheckBox.selectedProperty().addListener((o, a, b) -> {
                    try {
                        if (Boolean.TRUE.equals(b)) attachLightMode(); else detachLightMode();
                    } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.mavtab.light.toggle", e); }
                });
            }
            
            // Display raw RX in HEX mode
            currentReceiveMode = ReceiveMode.HEX;
            
            // Render initial statistics
            updateFrameStats();
            
            logger.info("MavlinkTabController initialized successfully");
        } catch (Exception e) {
            logger.error("MavlinkTabController initialization failed", e);
            if (exceptionHandler != null) {
                exceptionHandler.handleUIException("MavlinkTabController", e);
            }
        }
    }
    
    /** Handle protocol version changes. */
    private void handleProtocolVersionChange(String version) {
        // Map localized display text back to enum
        if (version.equals(languageManager.getString("ui.mavlink.version.v1"))) {
            currentProtocolVersion = ProtocolVersion.V1_ONLY;
        } else if (version.equals(languageManager.getString("ui.mavlink.version.v2"))) {
            currentProtocolVersion = ProtocolVersion.V2_ONLY;
        } else {
            currentProtocolVersion = ProtocolVersion.AUTO;
        }
        logger.info("Protocol version changed to: {}", currentProtocolVersion);
        synchronized (dataBuffer) {
            dataBuffer.clear();
        }
    }
    
    /** Process data reception (batch append and parse). */
    private com.serialcomm.service.UiAppender uiAppender;
    @Override
    protected void onDataReceived(String data) {
        if (uiAppender == null) {
            uiAppender = new com.serialcomm.service.UiAppender(this::appendHexBatch).setFlushIntervalMs(100);
            uiAppender.start();
        }
        uiAppender.append(data);
    }

    private void appendHexBatch(String batch) {
        Platform.runLater(() -> {
            try {
                rawDataTextArea.appendText(batch);
                String[] hexBytes = batch.trim().split("\\s+");
                boolean light = false;
                try { light = (lightModeCheckBox != null && lightModeCheckBox.isSelected()); } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.mavtab.light.read", e); }
                for (String hexByte : hexBytes) {
                    if (hexByte.isEmpty()) continue;
                    try {
                        if (!light) {
                            int value = Integer.parseInt(hexByte, 16) & 0xFF;
                            com.MAVLink.MAVLinkPacket pkt = parser.mavlink_parse_char(value);
                            if (pkt != null) {
                                boolean pktIsV2 = pkt.isMavlink2;
                                boolean allow =
                                    (currentProtocolVersion == ProtocolVersion.AUTO) ||
                                    (currentProtocolVersion == ProtocolVersion.V1_ONLY && !pktIsV2) ||
                                    (currentProtocolVersion == ProtocolVersion.V2_ONLY && pktIsV2);
                                if (!allow) continue;
                                boolean isV2 = pkt.isMavlink2;
                                // Global device filter (ALL means no filter)
                                try {
                                    int selSys = com.serialcomm.service.DeviceSelectionService.getInstance().selectedSys();
                                    int selComp = com.serialcomm.service.DeviceSelectionService.getInstance().selectedComp();
                                    if (!(selSys < 0 && selComp < 0)) {
                                        if (selSys >= 0 && pkt.sysid != selSys) continue;
                                        if (selComp >= 0 && pkt.compid != selComp) continue;
                                    }
                                } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.mavtab.filter", e); }
                                List<Byte> frame = new ArrayList<>();
                                byte[] encoded = pkt.encodePacket();
                                for (byte b : encoded) frame.add(b);
                                boolean crcValid = true;
                                displayExtractedFrame(frame, isV2, crcValid);
                                totalFrames.incrementAndGet();
                                successFrames.incrementAndGet();
                            }
                        } else {
                            // Light mode: do not parse byte-by-byte; frame display comes from central subscription callback
                        }
                    } catch (NumberFormatException ignore) {
                        // ignore
                    }
                }
                updateFrameRate();
                updateFrameStats();
            } catch (Exception e) {
                logger.error("Error processing received data", e);
            }
        });
    }

    private void attachLightMode() {
        if (lightModeListener != null) return;
        lightModeListener = pkt -> {
            try {
                if (pkt == null) return;
                boolean pktIsV2 = pkt.isMavlink2;
                boolean allow =
                    (currentProtocolVersion == ProtocolVersion.AUTO) ||
                    (currentProtocolVersion == ProtocolVersion.V1_ONLY && !pktIsV2) ||
                    (currentProtocolVersion == ProtocolVersion.V2_ONLY && pktIsV2);
                if (!allow) return;
                try {
                    int selSys = com.serialcomm.service.DeviceSelectionService.getInstance().selectedSys();
                    int selComp = com.serialcomm.service.DeviceSelectionService.getInstance().selectedComp();
                    if (!(selSys < 0 && selComp < 0)) {
                        if (selSys >= 0 && pkt.sysid != selSys) return;
                        if (selComp >= 0 && pkt.compid != selComp) return;
                    }
                } catch (Exception ignore) {}
                byte[] encoded = pkt.encodePacket();
                List<Byte> frame = new ArrayList<>(encoded.length);
                for (byte b : encoded) frame.add(b);
                boolean crcValid = true;
                displayExtractedFrame(frame, pkt.isMavlink2, crcValid);
                totalFrames.incrementAndGet();
                successFrames.incrementAndGet();
                updateFrameRate();
                updateFrameStats();
            } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("mavlink.lightMode.display", t); }
        };
        try { com.serialcomm.service.MavlinkDispatcher.getInstance().addListener(lightModeListener); } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("ui.mavtab.subscribe", t); }
    }

    private void detachLightMode() {
        if (lightModeListener == null) return;
        try { com.serialcomm.service.MavlinkDispatcher.getInstance().removeListener(lightModeListener); } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("ui.mavtab.unsubscribe", t); }
        lightModeListener = null;
    }
    
    /** Extract MAVLink frames from the legacy incremental buffer. */
    private void extractMavlinkFrames() {
        while (!dataBuffer.isEmpty()) {
            // Find start sentinel byte
            int startIndex = findStartByte();
            if (startIndex < 0) {
                // No start sentinel found -> clear buffer and wait for more
                dataBuffer.clear();
                break;
            }
            
            // Drop any data before the start sentinel
            if (startIndex > 0) {
                dataBuffer.subList(0, startIndex).clear();
            }
            
            // Ensure we have enough bytes to form a frame
            if (dataBuffer.size() < 2) {
                break; // wait for more data
            }
            
            byte startByte = dataBuffer.get(0);
            boolean isV2 = (startByte == MAVLINK_V2_START);
            int headerLen = isV2 ? MAVLINK_V2_HEADER_LEN : MAVLINK_V1_HEADER_LEN;
            
            // Check for a complete header
            if (dataBuffer.size() < headerLen) {
                break; // wait for more data
            }
            
            // Read payload length
            int payloadLen = dataBuffer.get(1) & 0xFF;
            if (payloadLen > MAVLINK_MAX_PAYLOAD_LEN) {
                // Invalid payload length -> drop the start byte and continue
                dataBuffer.remove(0);
                failedFrames.incrementAndGet();
                continue;
            }
            
            // Compute full frame length
            int frameLen = headerLen + payloadLen + 2; // +2 for CRC
            if (isV2 && (dataBuffer.get(2) & 0x01) != 0) {
                frameLen += 13; // signed frame
            }
            
            // Ensure a full frame is present
            if (dataBuffer.size() < frameLen) {
                break; // wait for more data
            }
            
            // Extract a complete frame
            List<Byte> frame = new ArrayList<>(dataBuffer.subList(0, frameLen));
            dataBuffer.subList(0, frameLen).clear();
            
            // Validate CRC (simplified; real CRC should be implemented)
            boolean crcValid = validateCRC(frame, isV2);
            
            // Display the extracted frame
            displayExtractedFrame(frame, isV2, crcValid);
            
            // Update statistics
            totalFrames.incrementAndGet();
            if (crcValid) {
                successFrames.incrementAndGet();
            } else {
                crcErrors.incrementAndGet();
            }
            
            updateFrameStats();
        }
    }
    
    /** Find the start sentinel byte for a frame. */
    private int findStartByte() {
        for (int i = 0; i < dataBuffer.size(); i++) {
            byte b = dataBuffer.get(i);
            
            switch (currentProtocolVersion) {
                case V1_ONLY:
                    if (b == MAVLINK_V1_START) return i;
                    break;
                case V2_ONLY:
                    if (b == MAVLINK_V2_START) return i;
                    break;
                case AUTO:
                    if (b == MAVLINK_V1_START || b == MAVLINK_V2_START) return i;
                    break;
            }
        }
        return -1;
    }
    
    /** Validate CRC (placeholder implementation). */
    private boolean validateCRC(List<Byte> frame, boolean isV2) {
        // TODO: Implement full MAVLink CRC validation.
        // Currently returns true; replace with proper algorithm.
        return true;
    }
    
    /** Render a single extracted frame into the UI with metadata and raw bytes. */
    private void displayExtractedFrame(List<Byte> frame, boolean isV2, boolean crcValid) {
        Platform.runLater(() -> {
            // Build the textual representation for the frame
            Text frameText = new Text();
            StringBuilder sb = new StringBuilder();
            
            // Add timestamp
            String timestamp = java.time.LocalTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            );
            sb.append("[").append(timestamp).append("] ");
            
            // Add protocol version
            sb.append(isV2 ? "v2.0" : "v1.0").append(" ");
            
            // Add System ID and Component ID
            int sysId = frame.get(isV2 ? 5 : 3) & 0xFF;
            int compId = frame.get(isV2 ? 6 : 4) & 0xFF;
            sb.append("SYS:").append(sysId).append(" COMP:").append(compId).append(" ");
            
            // Add message ID
            int msgId;
            if (isV2) {
                msgId = (frame.get(7) & 0xFF) | 
                       ((frame.get(8) & 0xFF) << 8) | 
                       ((frame.get(9) & 0xFF) << 16);
            } else {
                msgId = frame.get(5) & 0xFF;
            }
            sb.append("MSG:").append(msgId).append(" ");
            
            // Add payload length
            int payloadLen = frame.get(1) & 0xFF;
            sb.append("LEN:").append(payloadLen).append(" ");
            
            // Add CRC status
            sb.append(crcValid ? "[CRC OK]" : "[CRC ERROR]");
            sb.append("\n");
            
            // Append raw bytes (HEX)
            sb.append("  RAW: ");
            for (Byte b : frame) {
                sb.append(String.format("%02X ", b));
            }
            sb.append("\n\n");
            
            frameText.setText(sb.toString());
            frameText.setFill(crcValid ? Color.GREEN : Color.RED);
            
            extractedFramesFlow.getChildren().add(frameText);
            
            // Cap the number of rendered frames to avoid unbounded growth
            if (extractedFramesFlow.getChildren().size() > 100) {
                extractedFramesFlow.getChildren().remove(0);
            }
            
            // Auto-scroll to bottom
            extractedFramesScrollPane.setVvalue(1.0);

            // Trim buffers to avoid memory growth
            pruneIfNeeded();
        });
    }
    
    /** Update frame rate once per second. */
    private void updateFrameRate() {
        long currentTime = System.currentTimeMillis();
        frameCount++;
        
        if (currentTime - lastFrameTime >= 1000) {
            frameRate.set((int) frameCount);
            frameCount = 0;
            lastFrameTime = currentTime;
            updateFrameStats();
        }
    }
    
    /** Update frame statistics labels. */
    private void updateFrameStats() {
        UiUpdateQueue.get().submit("mavtab.stats", () -> {
            if (totalFramesLabel != null) {
                totalFramesLabel.setText(String.format(languageManager.getString("ui.mavlink.total.frames.fmt"), totalFrames.get()));
            }
            if (successFramesLabel != null) {
                successFramesLabel.setText(String.format(languageManager.getString("ui.mavlink.success.frames.fmt"), successFrames.get()));
            }
            if (failedFramesLabel != null) {
                failedFramesLabel.setText(String.format(languageManager.getString("ui.mavlink.failed.frames.fmt"), failedFrames.get()));
            }
            if (crcErrorsLabel != null) {
                crcErrorsLabel.setText(String.format(languageManager.getString("ui.mavlink.crc.errors.fmt"), crcErrors.get()));
            }
            if (frameRateLabel != null) {
                frameRateLabel.setText(String.format(languageManager.getString("ui.mavlink.frame.rate.fmt"), frameRate.get()));
            }
        });
    }

    /** Connection/disconnect wrappers for MainController use. */
    public void connectPort(String portName, int baudRate) {
        try {
            com.serialcomm.service.TransportViewModel.getInstance().connectSerial(portName, baudRate);
            com.serialcomm.service.TransportViewModel.getInstance().setActiveController(this);
            if (statusCallback != null) {
                statusCallback.updateStatus(languageManager.getString("status.port.connected", portName));
            }
        } catch (Exception e) {
            handleException(languageManager.getString("error.connection.failed", e.getMessage()), e);
        }
    }

    public void disconnectPort() {
        try {
            String name = (serialPort != null) ? serialPort.getSystemPortName() : languageManager.getString("statusbar.port.unknown");
            com.serialcomm.service.TransportViewModel.getInstance().disconnect();
            if (statusCallback != null) {
                statusCallback.updateStatus(languageManager.getString("status.port.disconnected", name));
            }
        } catch (Exception e) {
            handleException(languageManager.getString("error.disconnection.failed", e.getMessage()), e);
        }
    }
    
    /** Clear rendered frames and statistics. */
    private void clearFrames() {
        UiUpdateQueue.get().submit("mavtab.clear", () -> {
            extractedFramesFlow.getChildren().clear();
            rawDataTextArea.clear();
            
            // 重置统计
            totalFrames.set(0);
            successFrames.set(0);
            failedFrames.set(0);
            crcErrors.set(0);
            frameRate.set(0);
            frameCount = 0;
            
            updateFrameStats();
            
            logger.info("Frames cleared");
        });
    }

    // Periodic trimming to avoid long-running memory growth
    private void pruneIfNeeded() {
        UiUpdateQueue.get().submit("mavtab.prune", () -> {
            try {
                if (extractedFramesFlow.getChildren().size() > 500) {
                    extractedFramesFlow.getChildren().remove(0, extractedFramesFlow.getChildren().size() - 500);
                }
                if (rawDataTextArea.getText().length() > 200000) { // 200k chars
                    String t = rawDataTextArea.getText();
                    rawDataTextArea.setText(t.substring(t.length() - 100000));
                    rawDataTextArea.positionCaret(rawDataTextArea.getText().length());
                }
            } catch (Exception ignore) {}
        });
    }

    public void trimBuffers() {
        pruneIfNeeded();
    }
    
    /** Update localized UI texts for the tab. */
    public void updateUI() {
        if (languageManager == null) return;
        
        UiUpdateQueue.get().submit("mavtab.updateUI", () -> {
            // 更新标签文本
            if (protocolVersionLabel != null) {
                protocolVersionLabel.setText(languageManager.getString("ui.mavlink.protocol.version"));
            }
            if (rawDataLabel != null) {
                rawDataLabel.setText(languageManager.getString("ui.mavlink.raw.data"));
            }
            if (extractedFramesLabel != null) {
                extractedFramesLabel.setText(languageManager.getString("ui.mavlink.extracted.frames"));
            }
            if (frameStatsLabel != null) {
                frameStatsLabel.setText(languageManager.getString("ui.mavlink.frame.stats"));
            }
            if (clearFramesButton != null) {
                clearFramesButton.setText(languageManager.getString("ui.mavlink.clear.frames"));
            }
            if (lightModeCheckBox != null) {
                lightModeCheckBox.setText(languageManager.getString("ui.mavlink.light.mode"));
            }
            // 更新协议版本下拉的多语言显示
            if (protocolVersionComboBox != null) {
                String selected;
                switch (currentProtocolVersion) {
                    case V1_ONLY: selected = languageManager.getString("ui.mavlink.version.v1"); break;
                    case V2_ONLY: selected = languageManager.getString("ui.mavlink.version.v2"); break;
                    default: selected = languageManager.getString("ui.mavlink.version.auto");
                }
                protocolVersionComboBox.setItems(FXCollections.observableArrayList(
                    languageManager.getString("ui.mavlink.version.auto"),
                    languageManager.getString("ui.mavlink.version.v1"),
                    languageManager.getString("ui.mavlink.version.v2")
                ));
                protocolVersionComboBox.setValue(selected);
            }
            
            logger.debug("MavlinkTabController updateUI completed");
        });
        // Also refresh localized statistics labels
        updateFrameStats();
    }
    
    /** Resource cleanup: clear buffers, detach listeners, then delegate to super. */
    @Override
    public void cleanup() {
        try {
            logger.info("Starting MavlinkTabController resource cleanup");
            
            synchronized (dataBuffer) {
                dataBuffer.clear();
            }
            
            // Detach light mode listener if attached
            try { detachLightMode(); } catch (Exception ignore) {}

            super.cleanup();
            
            logger.info("MavlinkTabController resource cleanup completed");
        } catch (Exception e) {
            logger.error("Exception occurred during MavlinkTabController resource cleanup", e);
        }
    }
    
    /** Exception handling: log and route to exception handler or alert dialog. */
    @Override
    protected void handleException(String message, Exception e) {
        logger.error("{}: {}", message, e.getMessage(), e);
        
        if (exceptionHandler != null) {
            exceptionHandler.handleException(message, e);
        } else {
            showAlert(Alert.AlertType.ERROR, 
                languageManager.getString("dialog.title.error"), 
                message + ": " + e.getMessage());
        }
    }
    
    /** Status update callback to append timestamped status into raw data area. */
    @Override
    public void updateStatus(String message) {
        Platform.runLater(() -> {
            String timestamp = java.time.LocalTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
            );
            String statusMessage = String.format("[%s] [Status] %s\n", timestamp, message);
            
            rawDataTextArea.appendText(statusMessage);
            
            logger.debug("MAVLink tab status update: {}", message);
        });
    }
}