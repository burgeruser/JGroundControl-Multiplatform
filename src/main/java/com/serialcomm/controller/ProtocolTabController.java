package com.serialcomm.controller;

import com.serialcomm.controller.BaseController.StatusCallback;
import com.serialcomm.serial.SerialRouter;
import com.serialcomm.util.SerialExceptionHandler;
import javafx.application.Platform;
import com.serialcomm.util.UiUpdateQueue;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.CheckBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.IntConsumer;

// MAVLink classes
import com.MAVLink.minimal.msg_heartbeat;
import com.MAVLink.enums.MAV_TYPE;
import com.MAVLink.enums.MAV_AUTOPILOT;
import com.MAVLink.common.msg_request_data_stream;
import com.MAVLink.enums.MAV_DATA_STREAM;
import com.MAVLink.enums.MAV_MODE_FLAG;
import com.MAVLink.enums.MAV_STATE;
import com.MAVLink.common.msg_param_request_list;
import com.MAVLink.MAVLinkPacket;

/**
 * Protocol analysis tab controller.
 * Sends selected MAVLink requests and renders protocol-related output.
 */
public class ProtocolTabController extends BaseController implements StatusCallback {
    /** Logger for protocol-analysis related events */
    private static final Logger logger = LoggerFactory.getLogger(ProtocolTabController.class);
    
    /** Text area for protocol output rendering */
    @FXML
    private TextArea protocolTextArea;
    @FXML
    private Label protocolAnalysisLabel;

    // New UI controls
    @FXML private TextField gcsSysIdField;
    @FXML private TextField gcsCompIdField;
    @FXML private TextField targetSysIdField;
    @FXML private TextField targetCompIdField;
    @FXML private ComboBox<String> protoVersionCombo;
    @FXML private ComboBox<String> mavTypeCombo;
    @FXML private ComboBox<String> autopilotCombo;
    @FXML private ComboBox<String> baseModeCombo;
    @FXML private ComboBox<String> sysStateCombo;
    @FXML private TextField hbIntervalField;
    @FXML private Button sendHeartbeatBtn;
    @FXML private ToggleButton autoHeartbeatToggle;
    @FXML private ComboBox<String> streamIdCombo;
    @FXML private TextField streamRateField;
    @FXML private Button sendStreamReqBtn;
    @FXML private Button paramRequestBtn;
    @FXML private Button requestAutopilotVersionBtn;
    @FXML private Button clearTxButton;
    @FXML private TitledPane capabilityPane;
    @FXML private Label capabilitySummaryLabel;
    @FXML private Button copyCapabilityBtn;
    @FXML private TextArea capabilitySummaryArea;

    private java.util.concurrent.ScheduledFuture<?> heartbeatTask;
    @FXML private TitledPane heartbeatPane;
    @FXML private TitledPane requestPane;
    @FXML private CheckBox cbModeCustom;
    @FXML private CheckBox cbModeTest;
    @FXML private CheckBox cbModeAuto;
    @FXML private CheckBox cbModeGuided;
    @FXML private CheckBox cbModeStab;
    @FXML private CheckBox cbModeHil;
    @FXML private CheckBox cbModeManual;
    @FXML private CheckBox cbModeArmed;
    
    /** Initialize protocol tab controls, combos, and actions. */
    @FXML
    public void initialize() {
        try {
            // Initialize base controller facilities
            super.initialize();
            
            // Create exception handler bound to protocol text area
            exceptionHandler = new SerialExceptionHandler(protocolTextArea);
            
            // Log successful initialization
            logger.info("ProtocolTabController initialized successfully");

            // Populate combos
            if (protoVersionCombo != null) {
                protoVersionCombo.getItems().setAll("MAVLink v1.0", "MAVLink v2.0");
                protoVersionCombo.setValue("MAVLink v2.0");
            }
            populateEnumsByReflection();

            // Wire actions
            if (sendHeartbeatBtn != null) sendHeartbeatBtn.setOnAction(e -> sendHeartbeatOnce());
            if (sendStreamReqBtn != null) sendStreamReqBtn.setOnAction(e -> java.util.concurrent.CompletableFuture.runAsync(this::sendRequestDataStream));
            if (paramRequestBtn != null) paramRequestBtn.setOnAction(e -> java.util.concurrent.CompletableFuture.runAsync(this::sendParamRequestList));
            if (requestAutopilotVersionBtn != null) requestAutopilotVersionBtn.setOnAction(e -> java.util.concurrent.CompletableFuture.runAsync(this::sendRequestAutopilotVersion));
            if (autoHeartbeatToggle != null) autoHeartbeatToggle.setOnAction(e -> toggleAutoHeartbeat());
            if (clearTxButton != null) clearTxButton.setOnAction(e -> clearTxArea());

            if (copyCapabilityBtn != null && capabilitySummaryArea != null) {
                copyCapabilityBtn.setOnAction(e -> {
                    try {
                        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                        content.putString(capabilitySummaryArea.getText());
                        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                        appendStatus("Capability summary copied\n");
                    } catch (Exception ex) {
                        com.serialcomm.util.ErrorMonitor.record("ui.protocol.cap.copy", ex);
                        handleException("copyCapability", ex);
                    }
                });
            }

            // Subscribe dispatcher to fill capability summary on relevant messages
            com.serialcomm.service.MavlinkDispatcher.getInstance().addListener(pkt -> {
                try {
                    if (pkt == null) return;
                    if (pkt.msgid == com.MAVLink.minimal.msg_protocol_version.MAVLINK_MSG_ID_PROTOCOL_VERSION || pkt.msgid == 148) {
                        updateCapabilitySummary(pkt);
                    }
                } catch (Exception ex) {
                    com.serialcomm.util.ErrorMonitor.record("protocol.cap.summary", ex);
                }
            });
        } catch (Exception e) {
            // Log initialization failure
            logger.error("{}: {}", "ProtocolTabController initialization failed", e.getMessage(), e);
            
            // Handle initialization exception
            if (exceptionHandler != null) {
                // Route UI exception via dedicated handler
                exceptionHandler.handleUIException("ProtocolTabController", e);
            } else {
                // Fallback to default error dialog
                showAlert(Alert.AlertType.ERROR, 
                    languageManager.getString("dialog.title.error"), 
                    languageManager.getString("error.controller.init.failed", e.getMessage()));
            }
        }
    }

    private void populateEnumsByReflection() {
        try {
            if (mavTypeCombo != null) {
                java.util.List<String> options = extractEnumNames("com.MAVLink.enums.MAV_TYPE", "MAV_TYPE_");
                if (!options.isEmpty()) {
                    mavTypeCombo.getItems().setAll(options);
                    mavTypeCombo.setValue("GENERIC");
                }
            }
            if (autopilotCombo != null) {
                java.util.List<String> options = extractEnumNames("com.MAVLink.enums.MAV_AUTOPILOT", "MAV_AUTOPILOT_");
                if (!options.isEmpty()) {
                    autopilotCombo.getItems().setAll(options);
                    autopilotCombo.setValue("INVALID");
                }
            }
            if (baseModeCombo != null) {
                java.util.List<String> options = extractEnumNames("com.MAVLink.enums.MAV_MODE_FLAG", "MAV_MODE_FLAG_");
                if (!options.isEmpty()) {
                    // Provide a NONE first for convenience
                    java.util.ArrayList<String> list = new java.util.ArrayList<>();
                    list.add("NONE");
                    for (String s : options) {
                        if (!"MAV_MODE_FLAG_ENUM_END".equals("MAV_MODE_FLAG_" + s)) list.add(s);
                    }
                    baseModeCombo.getItems().setAll(list);
                    baseModeCombo.setValue("NONE");
                }
            }
            if (sysStateCombo != null) {
                java.util.List<String> options = extractEnumNames("com.MAVLink.enums.MAV_STATE", "MAV_STATE_");
                if (!options.isEmpty()) {
                    sysStateCombo.getItems().setAll(options);
                    sysStateCombo.setValue("ACTIVE");
                }
            }
            if (streamIdCombo != null) {
                java.util.List<String> options = extractEnumNames("com.MAVLink.enums.MAV_DATA_STREAM", "MAV_DATA_STREAM_");
                if (!options.isEmpty()) {
                    streamIdCombo.getItems().setAll(options);
                    streamIdCombo.setValue("ALL");
                }
            }
        } catch (Exception e) {
            logger.warn("populateEnumsByReflection failed: {}", e.getMessage());
        }
    }

    private java.util.List<String> extractEnumNames(String className, String prefix) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        try {
            Class<?> clazz = Class.forName(className);
            java.lang.reflect.Field[] fields = clazz.getDeclaredFields();
            for (java.lang.reflect.Field f : fields) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                String name = f.getName();
                if (name.startsWith(prefix)) {
                    String label = name.substring(prefix.length());
                    if (label.endsWith("_ENUM_END")) continue;
                    out.add(label);
                }
            }
            out.sort(String::compareTo);
        } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("ui.protocol.init", t); }
        return out;
    }
    
    /** Receive handling: protocol tab suppresses raw RX to keep the TX area clean. */
    @Override
    protected void onDataReceived(String data) {
        // Protocol tab acts as a TX-focused area; ignore raw RX bytes here to avoid garbled output
    }

    private int parseIdField(TextField field, int def) {
        return parseIntFieldRange(field, def, 0, 255, "error.field.range");
    }

    private int parseIntFieldRange(TextField field, int def, int min, int max, String errorKey) {
        try {
            int v = Integer.parseInt(field.getText().trim());
            if (v < min || v > max) {
                showAlert(Alert.AlertType.WARNING, languageManager.getString("dialog.title.warning"), languageManager.getString(errorKey));
                return def;
            }
            return v;
        } catch (Exception ignore) {
            return def;
        }
    }

    private int mapMavType(String s) {
        if (s == null) return MAV_TYPE.MAV_TYPE_GENERIC;
        s = s.toUpperCase();
        switch (s) {
            case "FIXED_WING": return MAV_TYPE.MAV_TYPE_FIXED_WING;
            case "QUADROTOR": return MAV_TYPE.MAV_TYPE_QUADROTOR;
            case "HELICOPTER": return MAV_TYPE.MAV_TYPE_HELICOPTER;
            case "GROUND_ROVER": return MAV_TYPE.MAV_TYPE_GROUND_ROVER;
            case "SURFACE_BOAT": return MAV_TYPE.MAV_TYPE_SURFACE_BOAT;
            case "SUBMARINE": return MAV_TYPE.MAV_TYPE_SUBMARINE;
            case "HEXAROTOR": return MAV_TYPE.MAV_TYPE_HEXAROTOR;
            case "OCTOROTOR": return MAV_TYPE.MAV_TYPE_OCTOROTOR;
            case "TRICOPTER": return MAV_TYPE.MAV_TYPE_TRICOPTER;
            case "VTOL_TILTROTOR": return MAV_TYPE.MAV_TYPE_VTOL_TILTROTOR;
            default: return MAV_TYPE.MAV_TYPE_GENERIC;
        }
    }

    private int mapAutopilot(String s) {
        if (s == null) return com.MAVLink.enums.MAV_AUTOPILOT.MAV_AUTOPILOT_GENERIC;
        s = s.toUpperCase();
        switch (s) {
            case "ARDUPILOTMEGA": return MAV_AUTOPILOT.MAV_AUTOPILOT_ARDUPILOTMEGA;
            case "PX4": return MAV_AUTOPILOT.MAV_AUTOPILOT_PX4;
            case "INVALID": return MAV_AUTOPILOT.MAV_AUTOPILOT_INVALID;
            default: return MAV_AUTOPILOT.MAV_AUTOPILOT_GENERIC;
        }
    }

    private int mapBaseMode(String s) {
        if (s == null) return 0;
        switch (s.toUpperCase()) {
            case "CUSTOM_MODE_ENABLED": return MAV_MODE_FLAG.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED;
            case "TEST_ENABLED": return MAV_MODE_FLAG.MAV_MODE_FLAG_TEST_ENABLED;
            case "AUTO_ENABLED": return MAV_MODE_FLAG.MAV_MODE_FLAG_AUTO_ENABLED;
            case "GUIDED_ENABLED": return MAV_MODE_FLAG.MAV_MODE_FLAG_GUIDED_ENABLED;
            case "STABILIZE_ENABLED": return MAV_MODE_FLAG.MAV_MODE_FLAG_STABILIZE_ENABLED;
            case "HIL_ENABLED": return MAV_MODE_FLAG.MAV_MODE_FLAG_HIL_ENABLED;
            case "MANUAL_INPUT_ENABLED": return MAV_MODE_FLAG.MAV_MODE_FLAG_MANUAL_INPUT_ENABLED;
            case "SAFETY_ARMED": return MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED;
            default: return 0;
        }
    }

    private int mapSysState(String s) {
        if (s == null) return MAV_STATE.MAV_STATE_STANDBY;
        switch (s.toUpperCase()) {
            case "UNINIT": return MAV_STATE.MAV_STATE_UNINIT;
            case "BOOT": return MAV_STATE.MAV_STATE_BOOT;
            case "CALIBRATING": return MAV_STATE.MAV_STATE_CALIBRATING;
            case "STANDBY": return MAV_STATE.MAV_STATE_STANDBY;
            case "ACTIVE": return MAV_STATE.MAV_STATE_ACTIVE;
            case "CRITICAL": return MAV_STATE.MAV_STATE_CRITICAL;
            case "EMERGENCY": return MAV_STATE.MAV_STATE_EMERGENCY;
            case "POWEROFF": return MAV_STATE.MAV_STATE_POWEROFF;
            case "FLIGHT_TERMINATION": return MAV_STATE.MAV_STATE_FLIGHT_TERMINATION;
            default: return MAV_STATE.MAV_STATE_STANDBY;
        }
    }

    private int buildBaseMode() {
        int mode = 0;
        if (cbModeCustom != null && cbModeCustom.isSelected()) mode |= MAV_MODE_FLAG.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED;
        if (cbModeTest != null && cbModeTest.isSelected()) mode |= MAV_MODE_FLAG.MAV_MODE_FLAG_TEST_ENABLED;
        if (cbModeAuto != null && cbModeAuto.isSelected()) mode |= MAV_MODE_FLAG.MAV_MODE_FLAG_AUTO_ENABLED;
        if (cbModeGuided != null && cbModeGuided.isSelected()) mode |= MAV_MODE_FLAG.MAV_MODE_FLAG_GUIDED_ENABLED;
        if (cbModeStab != null && cbModeStab.isSelected()) mode |= MAV_MODE_FLAG.MAV_MODE_FLAG_STABILIZE_ENABLED;
        if (cbModeHil != null && cbModeHil.isSelected()) mode |= MAV_MODE_FLAG.MAV_MODE_FLAG_HIL_ENABLED;
        if (cbModeManual != null && cbModeManual.isSelected()) mode |= MAV_MODE_FLAG.MAV_MODE_FLAG_MANUAL_INPUT_ENABLED;
        if (cbModeArmed != null && cbModeArmed.isSelected()) mode |= MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED;
        if (mode == 0 && baseModeCombo != null && baseModeCombo.getValue() != null) {
            mode = mapBaseMode(baseModeCombo.getValue());
        }
        return mode;
    }

    private void sendHeartbeatOnce() {
        int gcsSys = parseIdField(gcsSysIdField, 253);
        int gcsComp = parseIdField(gcsCompIdField, 190);
        boolean isV2 = !"MAVLink v1.0".equals(protoVersionCombo.getValue());

        msg_heartbeat hb = new msg_heartbeat();
        hb.sysid = gcsSys;      // Sender (GCS) System ID
        hb.compid = gcsComp;    // Sender (GCS) Component ID
        hb.isMavlink2 = isV2;
        hb.type = (short) mapMavType(mavTypeCombo.getValue());
        hb.autopilot = (short) mapAutopilot(autopilotCombo.getValue());
        hb.base_mode = (short) buildBaseMode();
        hb.system_status = (short) mapSysState(sysStateCombo != null ? sysStateCombo.getValue() : null);
        hb.mavlink_version = (short) (isV2 ? 3 : 2);
        MAVLinkPacket pkt = hb.pack();
        // Note: HEARTBEAT is broadcast and does not carry target_system/target_component.
        // The target IDs that user sets in the UI are used by other request messages.
        int seq = com.serialcomm.service.TransportViewModel.getInstance().nextSequence();
        pkt.seq = seq;
        byte[] bytes = pkt.encodePacket();
        com.serialcomm.service.TransportViewModel.getInstance().sendPacket(pkt, com.serialcomm.serial.SerialRouter.Priority.HIGH);
        appendStatus(String.format("TX HEARTBEAT sys:%d comp:%d v%s\n", gcsSys, gcsComp, isV2?"2":"1"));
    }

    private void toggleAutoHeartbeat() {
        if (heartbeatTask != null && !heartbeatTask.isCancelled()) {
            try { heartbeatTask.cancel(true); } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.protocol.hb.cancel", e); }
            heartbeatTask = null;
            appendStatus("Auto heartbeat stopped\n");
            return;
        }
        int interval = parseIntFieldRange(hbIntervalField, 1000, 50, 60000, "status.timer.period.changed");
        java.util.concurrent.ScheduledExecutorService exec = com.serialcomm.service.Scheduler.getInstance().ensureMonitoring();
        heartbeatTask = exec.scheduleAtFixedRate(this::sendHeartbeatOnce, 0, Math.max(200, interval), java.util.concurrent.TimeUnit.MILLISECONDS);
        appendStatus("Auto heartbeat started\n");
    }

    private int mapStreamId(String s) {
        if (s == null) return MAV_DATA_STREAM.MAV_DATA_STREAM_ALL;
        s = s.toUpperCase();
        switch (s) {
            case "RAW_SENSORS": return MAV_DATA_STREAM.MAV_DATA_STREAM_RAW_SENSORS;
            case "EXTENDED_STATUS": return MAV_DATA_STREAM.MAV_DATA_STREAM_EXTENDED_STATUS;
            case "RC_CHANNELS": return MAV_DATA_STREAM.MAV_DATA_STREAM_RC_CHANNELS;
            case "POSITION": return MAV_DATA_STREAM.MAV_DATA_STREAM_POSITION;
            case "EXTRA1": return MAV_DATA_STREAM.MAV_DATA_STREAM_EXTRA1;
            case "EXTRA2": return MAV_DATA_STREAM.MAV_DATA_STREAM_EXTRA2;
            case "EXTRA3": return MAV_DATA_STREAM.MAV_DATA_STREAM_EXTRA3;
            default: return MAV_DATA_STREAM.MAV_DATA_STREAM_ALL;
        }
    }

    private void sendRequestDataStream() {
        int gcsSys = parseIdField(gcsSysIdField, 253);
        int gcsComp = parseIdField(gcsCompIdField, 190);
        int targetSys = parseIdField(targetSysIdField, 1);
        int targetComp = parseIdField(targetCompIdField, 1);
        boolean isV2 = !"MAVLink v1.0".equals(protoVersionCombo.getValue());

        msg_request_data_stream req = new msg_request_data_stream();
        req.sysid = gcsSys; req.compid = gcsComp; req.isMavlink2 = isV2;
        req.target_system = (short) targetSys;
        req.target_component = (short) targetComp;
        req.req_stream_id = (short) mapStreamId(streamIdCombo.getValue());
        req.req_message_rate = Integer.parseInt(streamRateField.getText().trim());
        req.start_stop = 1;
        MAVLinkPacket pkt = req.pack();
        pkt.seq = com.serialcomm.service.TransportViewModel.getInstance().nextSequence();
        com.serialcomm.service.TransportViewModel.getInstance().sendPacket(pkt, com.serialcomm.serial.SerialRouter.Priority.NORMAL);
        appendStatus(String.format("TX REQ_DATA_STREAM id:%d rate:%sHz\n", req.req_stream_id, req.req_message_rate));
    }

    private void sendParamRequestList() {
        int gcsSys = parseIdField(gcsSysIdField, 253);
        int gcsComp = parseIdField(gcsCompIdField, 190);
        int targetSys = parseIdField(targetSysIdField, 1);
        int targetComp = parseIdField(targetCompIdField, 1);
        boolean isV2 = !"MAVLink v1.0".equals(protoVersionCombo.getValue());

        msg_param_request_list req = new msg_param_request_list();
        req.sysid = gcsSys; req.compid = gcsComp; req.isMavlink2 = isV2;
        req.target_system = (short) targetSys;
        req.target_component = (short) targetComp;
        MAVLinkPacket pkt = req.pack();
        pkt.seq = com.serialcomm.service.TransportViewModel.getInstance().nextSequence();
        com.serialcomm.service.TransportViewModel.getInstance().sendPacket(pkt, com.serialcomm.serial.SerialRouter.Priority.NORMAL);
        appendStatus("TX PARAM_REQUEST_LIST (background)\n");
    }

    private void sendRequestAutopilotVersion() {
        try {
            int gcsSys = parseIdField(gcsSysIdField, 253);
            int gcsComp = parseIdField(gcsCompIdField, 190);
            int targetSys = parseIdField(targetSysIdField, 1);
            int targetComp = parseIdField(targetCompIdField, 1);
            boolean isV2 = !"MAVLink v1.0".equals(protoVersionCombo.getValue());

            // Build COMMAND_LONG for MAV_CMD_REQUEST_MESSAGE (512), param1 = 148 (AUTOPILOT_VERSION)
            com.MAVLink.common.msg_command_long cmd = new com.MAVLink.common.msg_command_long();
            cmd.sysid = gcsSys; cmd.compid = gcsComp; cmd.isMavlink2 = isV2;
            cmd.target_system = (short) targetSys;
            cmd.target_component = (short) targetComp;
            cmd.command = (short) 512; // MAV_CMD_REQUEST_MESSAGE
            cmd.confirmation = 0;
            cmd.param1 = 148f; // AUTOPILOT_VERSION
            cmd.param2 = 0f; cmd.param3 = 0f; cmd.param4 = 0f; cmd.param5 = 0f; cmd.param6 = 0f; cmd.param7 = 0f;

            com.MAVLink.MAVLinkPacket pkt = cmd.pack();
            pkt.seq = com.serialcomm.service.TransportViewModel.getInstance().nextSequence();
            com.serialcomm.service.TransportViewModel.getInstance().sendPacket(pkt, com.serialcomm.serial.SerialRouter.Priority.NORMAL);
            appendStatus("TX MAV_CMD_REQUEST_MESSAGE AUTOPILOT_VERSION\n");
        } catch (Exception e) {
            handleException("sendRequestAutopilotVersion", e);
        }
    }

    // No global override: Protocol tab always honors its own target fields

    // Protocol tab is intentionally decoupled from global device selection

    private void updateCapabilitySummary(com.MAVLink.MAVLinkPacket pkt) {
        try {
            com.MAVLink.Messages.MAVLinkMessage msg = pkt.unpack();
            StringBuilder sb = new StringBuilder();
            sb.append("SYS:").append(pkt.sysid).append(" COMP:").append(pkt.compid).append('\n');
            if (pkt.msgid == com.MAVLink.minimal.msg_protocol_version.MAVLINK_MSG_ID_PROTOCOL_VERSION) {
                String v = readFieldSafe(msg, "version");
                String minv = readFieldSafe(msg, "min_version");
                String maxv = readFieldSafe(msg, "max_version");
                sb.append("PROTOCOL_VERSION v=").append(v).append(" min=").append(minv).append(" max=").append(maxv).append('\n');
            }
            if (pkt.msgid == 148) { // AUTOPILOT_VERSION
                String caps = readFieldSafe(msg, "capabilities");
                String capsText = com.serialcomm.service.EnumLabeler.label("msg_autopilot_version", "capabilities", caps);
                String fsv = readFieldSafe(msg, "flight_sw_version");
                String msv = readFieldSafe(msg, "middleware_sw_version");
                String osv = readFieldSafe(msg, "os_sw_version");
                String bvers = readFieldSafe(msg, "board_version");
                String vendor = readFieldSafe(msg, "vendor_id");
                String product = readFieldSafe(msg, "product_id");
                sb.append("AUTOPILOT_VERSION caps=").append(caps).append(" (").append((capsText==null||capsText.isEmpty())?"NONE":capsText).append(")\n");
                sb.append("flight=").append(fsv).append(" middleware=").append(msv).append(" os=").append(osv).append(" board=").append(bvers)
                  .append(" vendor=").append(vendor).append(" product=").append(product).append('\n');
            }
            String text = sb.toString();
            if (capabilitySummaryArea != null && !text.isBlank()) {
                UiUpdateQueue.get().submit("protocol.capability.summary", () -> capabilitySummaryArea.setText(text.trim()));
            }
        } catch (Exception ex) {
            com.serialcomm.util.ErrorMonitor.record("protocol.cap.summary.fill", ex);
        }
    }

    private static String readFieldSafe(Object obj, String fieldName) {
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(obj);
            if (v == null) return "-";
            if (v.getClass().isArray()) {
                if (v instanceof byte[]) return toHex((byte[]) v);
                if (v instanceof short[]) return toHex((short[]) v);
            }
            return String.valueOf(v);
        } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("ui.protocol.fmt", t); return "-"; }
    }

    private static String toHex(byte[] a) {
        if (a == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : a) sb.append(String.format("%02X", b));
        return sb.toString();
    }
    private static String toHex(short[] a) {
        if (a == null) return "";
        StringBuilder sb = new StringBuilder();
        for (short s : a) sb.append(String.format("%02X", s & 0xFF));
        return sb.toString();
    }

    private com.serialcomm.service.UiAppender uiAppender;
    private void appendStatus(String s) {
        if (uiAppender == null) {
            uiAppender = new com.serialcomm.service.UiAppender(this::appendStatusBatch).setFlushIntervalMs(100);
            uiAppender.start();
        }
        uiAppender.append(s);
    }

    private void appendStatusBatch(String batch) {
        Platform.runLater(() -> {
            protocolTextArea.appendText(batch);
            pruneIfNeeded();
        });
    }
    
    /**
     * Update localized UI texts across labels and controls.
     */
    public void updateUI() {
        if (languageManager == null) return;
        
        UiUpdateQueue.get().submit("protocol.updateUI", () -> {
            // Update section labels
            if (protocolAnalysisLabel != null) {
                protocolAnalysisLabel.setText(languageManager.getString("ui.protocol.analysis.area"));
            }
            // Localize labels for heartbeat/request/capability sections
            try {
                if (heartbeatPane != null) heartbeatPane.setText(languageManager.getString("ui.protocol.heartbeat.section"));
                if (requestPane != null) requestPane.setText(languageManager.getString("ui.protocol.request.section"));
                if (capabilityPane != null) capabilityPane.setText(languageManager.getString("ui.protocol.capability.section"));
                java.util.Map<javafx.scene.control.Labeled, String> labelMap = new java.util.LinkedHashMap<>();
                labelMap.put((javafx.scene.control.Labeled) ((javafx.scene.Node) protocolAnalysisLabel).getScene().lookup("#gcsSysIdLabel"), "ui.protocol.gcs.sysid");
                labelMap.put((javafx.scene.control.Labeled) ((javafx.scene.Node) protocolAnalysisLabel).getScene().lookup("#gcsCompIdLabel"), "ui.protocol.gcs.compid");
                labelMap.put((javafx.scene.control.Labeled) ((javafx.scene.Node) protocolAnalysisLabel).getScene().lookup("#targetSysIdLabel"), "ui.protocol.target.sysid");
                labelMap.put((javafx.scene.control.Labeled) ((javafx.scene.Node) protocolAnalysisLabel).getScene().lookup("#targetCompIdLabel"), "ui.protocol.target.compid");
                labelMap.put((javafx.scene.control.Labeled) ((javafx.scene.Node) protocolAnalysisLabel).getScene().lookup("#versionLabel"), "ui.protocol.version");
                labelMap.put((javafx.scene.control.Labeled) ((javafx.scene.Node) protocolAnalysisLabel).getScene().lookup("#mavTypeLabel"), "ui.protocol.mav.type");
                labelMap.put((javafx.scene.control.Labeled) ((javafx.scene.Node) protocolAnalysisLabel).getScene().lookup("#autopilotLabel"), "ui.protocol.autopilot");
                labelMap.put((javafx.scene.control.Labeled) ((javafx.scene.Node) protocolAnalysisLabel).getScene().lookup("#baseModeLabel"), "ui.protocol.base.mode");
                labelMap.put((javafx.scene.control.Labeled) ((javafx.scene.Node) protocolAnalysisLabel).getScene().lookup("#sysStateLabel"), "ui.protocol.system.status");
                labelMap.put((javafx.scene.control.Labeled) ((javafx.scene.Node) protocolAnalysisLabel).getScene().lookup("#hbIntervalLabel"), "ui.protocol.hb.interval");
                labelMap.put(capabilitySummaryLabel, "ui.protocol.capability.summary");
                labelMap.put((javafx.scene.control.Labeled) ((javafx.scene.Node) protocolAnalysisLabel).getScene().lookup("#streamLabel"), "ui.protocol.stream");
                labelMap.put((javafx.scene.control.Labeled) ((javafx.scene.Node) protocolAnalysisLabel).getScene().lookup("#streamRateLabel"), "ui.protocol.stream.rate");
                for (java.util.Map.Entry<javafx.scene.control.Labeled, String> e : labelMap.entrySet()) {
                    if (e.getKey() != null) e.getKey().setText(languageManager.getString(e.getValue()));
                }
                if (sendHeartbeatBtn != null) sendHeartbeatBtn.setText(languageManager.getString("ui.protocol.hb.send"));
                if (autoHeartbeatToggle != null) autoHeartbeatToggle.setText(languageManager.getString("ui.protocol.hb.auto"));
                if (sendStreamReqBtn != null) sendStreamReqBtn.setText(languageManager.getString("ui.protocol.stream.send"));
                if (paramRequestBtn != null) paramRequestBtn.setText(languageManager.getString("ui.protocol.param.request"));
                if (clearTxButton != null) clearTxButton.setText(languageManager.getString("ui.protocol.tx.clear"));
                if (copyCapabilityBtn != null) copyCapabilityBtn.setText(languageManager.getString("ui.protocol.capability.copy"));
            } catch (Exception ignore) {}
            logger.debug("ProtocolTabController updateUI completed");
        });
    }

    private void clearTxArea() {
        UiUpdateQueue.get().submit("protocol.tx.clear", () -> protocolTextArea.clear());
    }

    /**
     * 供主控制器调用的连接/断开包装
     * 使用顶部共享的端口与波特率，由MainController负责校验
     */
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
    
    /** Cleanup: stop scheduled tasks and release references. */
    @Override
    public void cleanup() {
        try {
            // 记录清理开始日志
            logger.info("Starting ProtocolTabController resource cleanup");
            
            // 调用父类清理方法，清理串口连接等基础资源
            super.cleanup();
            // 停止心跳任务
            try { if (heartbeatTask != null) { heartbeatTask.cancel(true); heartbeatTask = null; } } catch (Exception ignore) {}
            
            // 记录清理完成日志
            logger.info("ProtocolTabController resource cleanup completed");
        } catch (Exception e) {
            // 记录清理过程中的异常，但不抛出异常
            logger.error("{}: {}", "Exception occurred during ProtocolTabController resource cleanup", e.getMessage(), e);
        }
    }

    private void pruneIfNeeded() {
        try {
            String t = protocolTextArea.getText();
            if (t != null && t.length() > 200000) { // 200k chars
                protocolTextArea.setText(t.substring(t.length() - 100000));
                protocolTextArea.positionCaret(protocolTextArea.getText().length());
            }
        } catch (Exception ignore) {}
    }

    public void trimBuffers() { pruneIfNeeded(); }
    
    /**
     * Programmatically set Protocol tab target SysID/CompID fields.
     * This does not link to global device selection and is used for one-time auto-fill.
     */
    public void setTargetIds(int sysId, int compId) {
        try {
            Platform.runLater(() -> {
                if (targetSysIdField != null) targetSysIdField.setText(Integer.toString(sysId));
                if (targetCompIdField != null) targetCompIdField.setText(Integer.toString(compId));
            });
        } catch (Exception ignore) {}
    }

    /** Exception handling: log and show via handler or dialog. */
    @Override
    protected void handleException(String message, Exception e) {
        // 记录异常日志，包含异常描述和详细信息
        logger.error("{}: {}", message, e.getMessage(), e);
        
        // 使用异常处理器处理异常
        if (exceptionHandler != null) {
            // 使用专门的异常处理器处理异常
            exceptionHandler.handleException(message, e);
        } else {
            // 使用默认错误对话框显示异常信息
            showAlert(Alert.AlertType.ERROR, 
                languageManager.getString("dialog.title.error"), 
                message + ": " + e.getMessage());
        }
    }

    /** Status update: append timestamped status line into text area. */
    @Override
    public void updateStatus(String message) {
        // 在JavaFX应用线程中执行UI更新
        Platform.runLater(() -> {
            // 添加时间戳和状态标识
            String timestamp = java.time.LocalTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
            );
            String statusMessage = String.format("[%s] [Status] %s\n", timestamp, message);
            
            // 将状态消息追加到协议文本区域
            protocolTextArea.appendText(statusMessage);
            
            // 自动滚动到最新内容
            protocolTextArea.positionCaret(protocolTextArea.getText().length());
        });
        
        // 记录状态更新日志
        logger.debug("Protocol analysis tab status update: {}", message);
    }
}