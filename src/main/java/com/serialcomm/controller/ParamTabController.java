package com.serialcomm.controller;

import com.serialcomm.serial.SerialRouter;
import com.serialcomm.util.LanguageManager;
import com.serialcomm.util.UiUpdateQueue;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ScheduledFuture;

/**
 * Parameter management tab: fetch/search/edit/backup.
 * Phase 1: v1 PARAM_REQUEST_LIST + incremental PARAM_VALUE ingest; write via PARAM_SET.
 */
public class ParamTabController extends BaseController {
    private static final Logger logger = LoggerFactory.getLogger(ParamTabController.class);

    @FXML private Button btnRefresh; @FXML private Button btnWrite; @FXML private Button btnBackup;
    @FXML private Button btnMetaRefresh;
    @FXML private TableView<Row> table;
    @FXML private TreeTableView<TreeRow> tree;
    @FXML private TableColumn<Row, String> colCommand; @FXML private TableColumn<Row, String> colValue;
    @FXML private TableColumn<Row, String> colUnit; @FXML private TableColumn<Row, String> colRange;
    @FXML private TableColumn<Row, String> colDesc;
    @FXML private TableColumn<Row, String> colGroup;
    @FXML private TreeTableColumn<TreeRow, String> tColGroup; @FXML private TreeTableColumn<TreeRow, String> tColCommand;
    @FXML private TreeTableColumn<TreeRow, String> tColValue; @FXML private TreeTableColumn<TreeRow, String> tColUnit;
    @FXML private TreeTableColumn<TreeRow, String> tColRange; @FXML private TreeTableColumn<TreeRow, String> tColDesc;
    @FXML private Label filterLabel; @FXML private TextField filterField; @FXML private Label groupLabel; @FXML private ComboBox<String> groupCombo; @FXML private Label metaStatsLabel;

    private final javafx.collections.ObservableList<Row> rows = javafx.collections.FXCollections.observableArrayList();
    private final java.util.concurrent.ConcurrentHashMap<String, Row> nameToRow = new java.util.concurrent.ConcurrentHashMap<>();
    private int currentGroupLevel = 1; // 0 none, 1 level1, 2 level2
    private volatile String pendingVerifyName = null;
    private volatile ScheduledFuture<?> verifyTimeoutTask = null;
    private volatile boolean viewDirty = false;
    private volatile java.util.concurrent.ScheduledFuture<?> viewRefreshTask = null;
    // Batch buffer for high-frequency PARAM_VALUE UI additions
    private final java.util.concurrent.ConcurrentLinkedQueue<Update> ingestPending = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final class Update {
        final String name; final String val;
        Update(String name, String val) { this.name = name; this.val = val; }
    }

    @FXML
    public void initialize() {
        try {
            super.initialize();
            currentReceiveMode = ReceiveMode.HEX;

            table.setItems(rows);
            colCommand.setCellValueFactory(p -> p.getValue().commandProperty());
            colValue.setCellValueFactory(p -> p.getValue().valueProperty());
            colUnit.setCellValueFactory(p -> p.getValue().unitProperty());
            colRange.setCellValueFactory(p -> p.getValue().rangeProperty());
            colDesc.setCellValueFactory(p -> p.getValue().descriptionProperty());
            if (colGroup != null) colGroup.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(groupKey(p.getValue().commandProperty().get(), currentGroupLevel)));

            if (tree != null) {
                tree.setShowRoot(false);
                tColGroup.setCellValueFactory(p -> p.getValue().getValue().groupProperty());
                tColCommand.setCellValueFactory(p -> p.getValue().getValue().commandProperty());
                tColValue.setCellValueFactory(p -> p.getValue().getValue().valueProperty());
                tColUnit.setCellValueFactory(p -> p.getValue().getValue().unitProperty());
                tColRange.setCellValueFactory(p -> p.getValue().getValue().rangeProperty());
                tColDesc.setCellValueFactory(p -> p.getValue().getValue().descriptionProperty());
                tree.setRoot(new javafx.scene.control.TreeItem<>(new TreeRow("root", "", "", "", "", "")));
                tree.setEditable(true);
                tColValue.setCellFactory(javafx.scene.control.cell.TextFieldTreeTableCell.forTreeTableColumn());
                tColValue.setOnEditCommit(ev -> {
                    try {
                        javafx.scene.control.TreeItem<TreeRow> item = ev.getRowValue();
                        if (item == null) return;
                        String cmd = item.getValue().commandProperty().get();
                        String newVal = ev.getNewValue();
                        item.getValue().valueProperty().set(newVal);
                        if (cmd != null && !cmd.isEmpty()) {
                            Row r = nameToRow.get(cmd);
                            if (r != null) r.valueProperty().set(newVal);
                        }
                    } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.params.tree.edit", e); }
                });
            }

            // allow editing value column
            colValue.setCellFactory(TextFieldTableCell.forTableColumn());
            colValue.setOnEditCommit(ev -> ev.getRowValue().valueProperty().set(ev.getNewValue()));

            btnRefresh.setOnAction(e -> requestAllParams());
            btnBackup.setOnAction(e -> {
                try { com.serialcomm.service.Scheduler.getInstance().ensureBackground().submit(this::backupCsv); } catch (Exception ex1) { com.serialcomm.util.ErrorMonitor.record("ui.params.backup.schedule", ex1); }
            });
            btnWrite.setOnAction(e -> writeSelectedParam());
            if (btnMetaRefresh != null) {
                btnMetaRefresh.setOnAction(e -> {
                    try {
                        java.util.concurrent.ScheduledExecutorService exec = com.serialcomm.service.Scheduler.getInstance().ensureBackground();
                        exec.submit(() -> {
                            try {
                                com.serialcomm.service.ParamMetadataService.getInstance().reload();
                                java.util.List<Row> snapshot = new java.util.ArrayList<>(nameToRow.values());
                                javafx.application.Platform.runLater(() -> {
                                    try {
                                        for (Row r : snapshot) {
                                            String cmd = r.commandProperty().get();
                                            if (cmd == null || cmd.isEmpty()) continue;
                                            com.serialcomm.service.ParamMetadataService.Meta m = com.serialcomm.service.ParamMetadataService.getInstance().lookup(cmd);
                                            if (m != null) {
                                                if ((r.unitProperty().get() == null || r.unitProperty().get().isEmpty()) && m.unit != null && !m.unit.isEmpty()) {
                                                    r.unitProperty().set(m.unit);
                                                }
                                                if ((r.rangeProperty().get() == null || r.rangeProperty().get().isEmpty()) && m.range != null && !m.range.isEmpty()) {
                                                    r.rangeProperty().set(m.range);
                                                }
                                                if ((r.descriptionProperty().get() == null || r.descriptionProperty().get().isEmpty()) && m.description != null && !m.description.isEmpty()) {
                                                    r.descriptionProperty().set(m.description);
                                                }
                                            }
                                        }
                                        applyFilterAndGrouping();
                                        updateMetaStatsLabel();
                                        if (statusCallback != null) statusCallback.updateStatus("Parameter metadata reloaded");
                                    } catch (Exception uiEx) { handleException("reloadMetadata.ui", uiEx); }
                                });
                            } catch (Exception ex) {
                                javafx.application.Platform.runLater(() -> handleException("reloadMetadata", ex));
                            }
                        });
                    } catch (Exception ex2) { com.serialcomm.util.ErrorMonitor.record("ui.params.meta.reload", ex2); }
                });
            }

            // fetch/import removed per design

            // Group options: none | top-level prefix | second-level prefix (split by '_')
            if (groupCombo != null) {
                LanguageManager lm = LanguageManager.getInstance();
                groupCombo.getItems().setAll(
                    lm.getString("ui.params.view.tree"),
                    lm.getString("ui.params.view.flat"),
                    lm.getString("ui.params.view.flat.l1"),
                    lm.getString("ui.params.view.flat.l2")
                );
                groupCombo.setValue(lm.getString("ui.params.view.flat.l1"));
                currentGroupLevel = 1;
                groupCombo.valueProperty().addListener((o,a,b) -> {
                    if (b == null) return;
                    handleViewModeChange(b);
                });
            }
            if (filterField != null) {
                filterField.textProperty().addListener((o,a,b) -> applyFilterAndGrouping());
            }

            // Subscribe to dispatcher to ingest PARAM_VALUE
            com.serialcomm.service.MavlinkDispatcher.getInstance().addListener(pkt -> {
                if (pkt == null) return;
                if (pkt.msgid == com.MAVLink.common.msg_param_value.MAVLINK_MSG_ID_PARAM_VALUE) {
                    // Global device filter (ALL means no filter)
                    try {
                        int selSys = com.serialcomm.service.DeviceSelectionService.getInstance().selectedSys();
                        int selComp = com.serialcomm.service.DeviceSelectionService.getInstance().selectedComp();
                        if (!(selSys < 0 && selComp < 0)) {
                            if (selSys >= 0 && pkt.sysid != selSys) return;
                            if (selComp >= 0 && pkt.compid != selComp) return;
                        }
                    } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.params.meta.verify.cancel", e); }
                    try {
                        com.MAVLink.common.msg_param_value pv = (com.MAVLink.common.msg_param_value) pkt.unpack();
                        onParamValue(pv);
                    } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("param.ingest", t); }
                }
            });
        } catch (Exception e) {
            logger.error("ParamTab init failed", e);
        }
    }

    private void requestAllParams() {
        try {
            int gcsSys = 253, gcsComp = 190;
            int targetSys = globalTargetSys();
            int targetComp = globalTargetComp();
            // If ALL is selected but exactly one device is online, target that device to improve first-refresh success (PX4 friendliness)
            if (targetSys == 0 && targetComp == 0) {
                try {
                    long offlineThresholdMs = 3000L;
                    java.util.List<String> devs = com.serialcomm.service.DeviceSelectionService.getInstance().enumerateOnlineDevices(offlineThresholdMs);
                    if (devs != null && devs.size() == 2) {
                        String[] parts = devs.get(1).split(":");
                        if (parts.length == 2) { targetSys = Integer.parseInt(parts[0]); targetComp = Integer.parseInt(parts[1]); }
                    }
                } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.params.meta.dialog", e); }
            }
            com.MAVLink.common.msg_param_request_list req = new com.MAVLink.common.msg_param_request_list();
            req.sysid = gcsSys; req.compid = gcsComp; req.isMavlink2 = true;
            req.target_system = (short) targetSys;
            req.target_component = (short) targetComp;
            com.MAVLink.MAVLinkPacket pkt = req.pack();
            pkt.seq = com.serialcomm.service.TransportViewModel.getInstance().nextSequence();
            com.serialcomm.service.TransportViewModel.getInstance().sendPacket(pkt, com.serialcomm.serial.SerialRouter.Priority.LOW);
            if (statusCallback != null) statusCallback.updateStatus("TX PARAM_REQUEST_LIST");
        } catch (Exception e) {
            handleException("requestAllParams", e);
        }
    }

    private void onParamValue(com.MAVLink.common.msg_param_value pv) {
        if (pv == null) return;
        String name = new String(pv.param_id).trim().replace("\0", "");
        String val = String.valueOf(pv.param_value);
        // Lookup metadata (unit/range/description)
        com.serialcomm.service.ParamMetadataService.Meta meta = com.serialcomm.service.ParamMetadataService.getInstance().lookup(name);
        String unit = meta.unit; String range = meta.range; String desc = meta.description;
        Row row = nameToRow.computeIfAbsent(name, k -> new Row(k, val, unit, range, desc));
        row.valueProperty().set(val);
        if (unit != null && !unit.isEmpty()) row.unitProperty().set(unit);
        if (range != null && !range.isEmpty()) row.rangeProperty().set(range);
        if (desc != null && !desc.isEmpty()) row.descriptionProperty().set(desc);
        // Buffer this update; drain on FX via UiUpdateQueue to avoid per-item coalesce drop
        ingestPending.add(new Update(name, val));
        UiUpdateQueue.get().submit("params.ingest", this::drainIngestPending);
    }

    private void writeSelectedParam() {
        try {
            Row sel = (table != null) ? table.getSelectionModel().getSelectedItem() : null;
            String name = null; String valueStr = null;
            if (sel != null) { name = sel.commandProperty().get(); valueStr = sel.valueProperty().get(); }
            if ((name == null || name.isEmpty()) && tree != null && tree.isVisible()) {
                javafx.scene.control.TreeItem<TreeRow> tri = tree.getSelectionModel().getSelectedItem();
                if (tri != null && tri.getValue() != null) {
                    name = tri.getValue().commandProperty().get();
                    valueStr = tri.getValue().valueProperty().get();
                }
            }
            if (name == null || name.isEmpty()) return;
            float value;
            try { value = Float.parseFloat(valueStr); }
            catch (Exception ex) { showAlert(Alert.AlertType.WARNING, LanguageManager.getInstance().getString("dialog.title.warning"), "Invalid value"); return; }
            com.MAVLink.common.msg_param_set set = new com.MAVLink.common.msg_param_set();
            set.sysid = 253; set.compid = 190; set.isMavlink2 = true; set.target_system = (short) globalTargetSys(); set.target_component = (short) globalTargetComp();
            byte[] id = new byte[16]; byte[] src = name.getBytes(); System.arraycopy(src, 0, id, 0, Math.min(16, src.length));
            set.param_id = id; set.param_value = value; set.param_type = (short) com.MAVLink.enums.MAV_PARAM_TYPE.MAV_PARAM_TYPE_REAL32;
            com.MAVLink.MAVLinkPacket pkt = set.pack(); pkt.seq = com.serialcomm.service.TransportViewModel.getInstance().nextSequence();
            com.serialcomm.service.TransportViewModel.getInstance().sendPacket(pkt, com.serialcomm.serial.SerialRouter.Priority.NORMAL);
            // Confirmation dialog with two choices
            showWriteConfirmDialog(name, value);
        } catch (Exception e) {
            handleException("writeSelectedParam", e);
        }
    }

    private void showWriteConfirmDialog(String name, float value) {
        try {
            LanguageManager lm = LanguageManager.getInstance();
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(lm.getString("dialog.title.info"));
            alert.setHeaderText(null);
            String content = String.format(lm.getString("dialog.param.write.sent"), name, String.valueOf(value));
            alert.setContentText(content);
            ButtonType acknowledge = new ButtonType(lm.getString("ui.params.btn.ack"), ButtonBar.ButtonData.OK_DONE);
            ButtonType verify = new ButtonType(lm.getString("ui.params.btn.verify"), ButtonBar.ButtonData.OTHER);
            alert.getButtonTypes().setAll(acknowledge, verify);
            java.util.Optional<ButtonType> res = alert.showAndWait();
            if (res.isPresent() && res.get() == verify) {
                // Re-request this parameter and verify (30s timeout)
                requestSingleParam(name);
                pendingVerifyName = name;
                if (verifyTimeoutTask != null) { try { verifyTimeoutTask.cancel(true); } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.params.verify.cancel", e); } }
                java.util.concurrent.ScheduledExecutorService exec = com.serialcomm.service.Scheduler.getInstance().ensureMonitoring();
                verifyTimeoutTask = exec.schedule(() -> {
                    if (pendingVerifyName != null && pendingVerifyName.equals(name)) {
                        LanguageManager lm2 = LanguageManager.getInstance();
                        Platform.runLater(() -> showAlert(Alert.AlertType.WARNING, lm2.getString("dialog.title.warning"), String.format(lm2.getString("dialog.param.verify.timeout"), name)));
                        pendingVerifyName = null;
                    }
                }, 30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            // best effort
        }
    }

    private void requestSingleParam(String name) {
        try {
            com.MAVLink.common.msg_param_request_read rr = new com.MAVLink.common.msg_param_request_read();
            rr.sysid = 253; rr.compid = 190; rr.isMavlink2 = true; rr.target_system = (short) globalTargetSys(); rr.target_component = (short) globalTargetComp();
            byte[] id = new byte[16]; byte[] src = name.getBytes(); System.arraycopy(src, 0, id, 0, Math.min(16, src.length));
            rr.param_id = id; rr.param_index = -1;
            com.MAVLink.MAVLinkPacket pkt = rr.pack(); pkt.seq = com.serialcomm.service.TransportViewModel.getInstance().nextSequence();
            com.serialcomm.service.TransportViewModel.getInstance().sendPacket(pkt, com.serialcomm.serial.SerialRouter.Priority.NORMAL);
            if (statusCallback != null) statusCallback.updateStatus("Verify PARAM_REQUEST_READ " + name);
        } catch (Exception e) {
            handleException("requestSingleParam", e);
        }
    }

    @Override
    public void cleanup() {
        try {
            if (verifyTimeoutTask != null) { try { verifyTimeoutTask.cancel(true); } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.params.verify.cancel", e); } verifyTimeoutTask = null; }
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.params.cleanup", e); }
        super.cleanup();
    }

    private int globalTargetSys() {
        try {
            int sys = com.serialcomm.service.DeviceSelectionService.getInstance().selectedSys();
            return (sys >= 0) ? sys : 0; // ALL -> 0 per MAVLink broadcast semantics
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.params.target.sys", e); return 0; }
    }
    private int globalTargetComp() {
        try {
            int comp = com.serialcomm.service.DeviceSelectionService.getInstance().selectedComp();
            return (comp >= 0) ? comp : 0; // ALL -> 0 per MAVLink broadcast semantics
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.params.target.comp", e); return 0; }
    }

    /** Called when global device selection changes: clear current rows and wait for new values. */
    public void onGlobalDeviceChanged() {
        try {
            // Clear flat table rows and map
            nameToRow.clear();
            UiUpdateQueue.get().submit("params.device.changed", () -> {
                try {
                    if (table != null) table.getItems().clear();
                    if (tree != null) {
                        javafx.scene.control.TreeItem<TreeRow> root = new javafx.scene.control.TreeItem<>(new TreeRow("root", "", "", "", "", ""));
                        tree.setRoot(root);
                        tree.refresh();
                    }
                    applyFilterAndGrouping();
                } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.params.onGlobalDeviceChanged.ui", e); }
            });
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.params.onGlobalDeviceChanged", e); }
    }

    private void backupCsv() {
        try {
            java.io.File dir = new java.io.File("logs"); if (!dir.exists()) dir.mkdirs();
            String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            java.io.File csv = new java.io.File(dir, "params_backup_" + ts + ".csv");
            try (java.io.FileWriter fw = new java.io.FileWriter(csv, false)) {
                fw.write("command,value,unit,range,description\n");
                for (Row r : table.getItems()) {
                    fw.write(String.format("%s,%s,%s,%s,%s\n",
                        esc(r.commandProperty().get()), esc(r.valueProperty().get()), esc(r.unitProperty().get()), esc(r.rangeProperty().get()), esc(r.descriptionProperty().get())));
                }
            }
            if (statusCallback != null) statusCallback.updateStatus("Params backup saved: " + csv.getAbsolutePath());
            LanguageManager lm = LanguageManager.getInstance();
            showAlert(Alert.AlertType.INFORMATION, lm.getString("dialog.title.info"), String.format(lm.getString("dialog.param.backup.saved"), csv.getAbsolutePath()));
        } catch (Exception e) { handleException("backupCsv", e); }
    }

    private void applyFilterAndGrouping() {
        try {
            String query = filterField != null && filterField.getText() != null ? filterField.getText().trim().toLowerCase() : "";
            java.util.List<Row> all = new java.util.ArrayList<>(nameToRow.values());
            java.util.List<Row> filtered = new java.util.ArrayList<>();
            for (Row r : all) {
                String cmd = r.commandProperty().get();
                if (query.isEmpty() || cmd.toLowerCase().contains(query)) filtered.add(r);
            }
            // For now grouping just orders by prefix level
            int level = currentGroupLevel;
            java.util.Comparator<Row> cmp;
            final int sortLevel = level;
            if (sortLevel == 0) cmp = java.util.Comparator.comparing(r -> r.commandProperty().get());
            else {
                cmp = java.util.Comparator.comparing(r -> prefix(r.commandProperty().get(), sortLevel));
            }
            filtered.sort(cmp.thenComparing(r -> r.commandProperty().get()));
            UiUpdateQueue.get().submit("params.view.update", () -> {
                // update flat table and tree based on current view mode
                updateViews(filtered);
            });
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.params.applyFilter", e); }
    }

    // Drain buffered PARAM_VALUE updates on FX thread respecting UiUpdateQueue tick
    private void drainIngestPending() {
        try {
            Update u;
            int added = 0;
            while ((u = ingestPending.poll()) != null) {
                final String name = u.name;
                final String value = u.val;
                Row row = nameToRow.computeIfAbsent(name, k -> new Row(k, value, "", "", ""));
                row.valueProperty().set(value);
                if (!rows.contains(row)) rows.add(row);
                try {
                    com.serialcomm.service.ParamMetadataService.Meta mm = com.serialcomm.service.ParamMetadataService.getInstance().lookup(name);
                    if (mm != null) {
                        if ((row.unitProperty().get() == null || row.unitProperty().get().isEmpty()) && mm.unit != null && !mm.unit.isEmpty()) row.unitProperty().set(mm.unit);
                        if ((row.rangeProperty().get() == null || row.rangeProperty().get().isEmpty()) && mm.range != null && !mm.range.isEmpty()) row.rangeProperty().set(mm.range);
                        if ((row.descriptionProperty().get() == null || row.descriptionProperty().get().isEmpty()) && mm.description != null && !mm.description.isEmpty()) row.descriptionProperty().set(mm.description);
                    }
                } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.params.meta.autofill.ui", e); }
                added++;
            }
            if (added > 0) {
                markViewDirtyAndSchedule();
            }
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.params.ingest.drain", e); }
    }

    private void markViewDirtyAndSchedule() {
        viewDirty = true;
        try {
            if (viewRefreshTask == null || viewRefreshTask.isCancelled()) {
                java.util.concurrent.ScheduledExecutorService exec = com.serialcomm.service.Scheduler.getInstance().ensureMonitoring();
                viewRefreshTask = exec.schedule(() -> {
                    if (!viewDirty) return;
                    viewDirty = false;
                    try { applyFilterAndGrouping(); } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.params.view.refresh", e); }
                }, 150, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.params.view.schedule", e); }
    }

    // --- simple fetcher placeholder: downloads ArduPilot Copter parameters page and writes a demo JSON ---
    // fetch/import removed per design

    private void handleViewModeChange(String sel) {
        LanguageManager lm = LanguageManager.getInstance();
        if (sel.equals(lm.getString("ui.params.view.tree"))) {
            currentGroupLevel = 1; // grouping depth for display; tree will expand as prefixes
            if (table != null) { table.setVisible(false); table.setManaged(false); }
            if (tree != null) { tree.setVisible(true); tree.setManaged(true); }
        } else if (sel.equals(lm.getString("ui.params.view.flat"))) {
            currentGroupLevel = 0;
            if (table != null) { table.setVisible(true); table.setManaged(true); }
            if (tree != null) { tree.setVisible(false); tree.setManaged(false); }
        } else if (sel.equals(lm.getString("ui.params.view.flat.l1"))) {
            currentGroupLevel = 1;
            if (table != null) { table.setVisible(true); table.setManaged(true); }
            if (tree != null) { tree.setVisible(false); tree.setManaged(false); }
        } else if (sel.equals(lm.getString("ui.params.view.flat.l2"))) {
            currentGroupLevel = 2;
            if (table != null) { table.setVisible(true); table.setManaged(true); }
            if (tree != null) { tree.setVisible(false); tree.setManaged(false); }
        }
        applyFilterAndGrouping();
    }

    private void updateViews(java.util.List<Row> filtered) {
        // flat table
        if (table != null && table.isVisible()) {
            table.getItems().setAll(filtered);
            if (colGroup != null) colGroup.setText(LanguageManager.getInstance().getString("ui.params.col.group"));
        }
        // tree grouping view (multi-level by underscores)
        if (tree != null && tree.isVisible()) {
            javafx.scene.control.TreeItem<TreeRow> root = new javafx.scene.control.TreeItem<>(new TreeRow("root", "", "", "", "", ""));
            java.util.Map<String, javafx.scene.control.TreeItem<TreeRow>> pathMap = new java.util.HashMap<>();
            pathMap.put("", root);
            for (Row r : filtered) {
                String cmd = r.commandProperty().get();
                String[] parts = cmd.split("_");
                StringBuilder path = new StringBuilder();
                javafx.scene.control.TreeItem<TreeRow> parent = root;
                for (int i = 0; i < parts.length - 1; i++) {
                    if (i > 0) path.append('/');
                    path.append(parts[i]);
                    String key = path.toString();
                    javafx.scene.control.TreeItem<TreeRow> node = pathMap.get(key);
                    if (node == null) {
                        node = new javafx.scene.control.TreeItem<>(new TreeRow(parts[i], "", "", "", "", ""));
                        node.setExpanded(false);
                        parent.getChildren().add(node);
                        pathMap.put(key, node);
                    }
                    parent = node;
                }
                // leaf
                javafx.scene.control.TreeItem<TreeRow> leaf = new javafx.scene.control.TreeItem<>(new TreeRow(
                    (parts.length > 0 ? parts[0] : ""), cmd, r.valueProperty().get(), r.unitProperty().get(), r.rangeProperty().get(), r.descriptionProperty().get()
                ));
                parent.getChildren().add(leaf);
            }
            tree.setRoot(root);
        }
    }

    private String prefix(String name, int level) {
        String[] parts = name.split("_");
        if (level <= 0 || parts.length == 0) return name;
        if (level == 1) return parts[0];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(level, parts.length); i++) { if (i>0) sb.append('_'); sb.append(parts[i]); }
        return sb.toString();
    }

    private String groupKey(String name, int level) {
        if (level <= 0) return "";
        return prefix(name, level);
    }

    public void updateUI() {
        try {
            LanguageManager lm = LanguageManager.getInstance();
            UiUpdateQueue.get().submit("params.updateUI", () -> {
                if (btnRefresh != null) btnRefresh.setText(lm.getString("ui.params.refresh"));
                if (btnWrite != null) btnWrite.setText(lm.getString("ui.params.write"));
                if (btnBackup != null) btnBackup.setText(lm.getString("ui.params.backup"));
                if (btnMetaRefresh != null) {
                    btnMetaRefresh.setText(lm.getString("ui.params.meta.refresh"));
                    try {
                        String hint = lm.getString("ui.params.meta.refresh.hint");
                        if (hint != null && !hint.isEmpty()) {
                            btnMetaRefresh.setTooltip(new javafx.scene.control.Tooltip(hint));
                        }
                    } catch (Exception ignore) {}
                }
                if (filterLabel != null) filterLabel.setText(lm.getString("ui.params.filter"));
                if (filterField != null) filterField.setPromptText(lm.getString("ui.params.filter.prompt"));
                if (groupLabel != null) groupLabel.setText(lm.getString("ui.params.view"));
                if (groupCombo != null) {
                    String sel;
                    if (tree != null && tree.isVisible()) sel = lm.getString("ui.params.view.tree");
                    else if (currentGroupLevel == 0) sel = lm.getString("ui.params.view.flat");
                    else if (currentGroupLevel == 2) sel = lm.getString("ui.params.view.flat.l2");
                    else sel = lm.getString("ui.params.view.flat.l1");
                    groupCombo.getItems().setAll(
                        lm.getString("ui.params.view.tree"),
                        lm.getString("ui.params.view.flat"),
                        lm.getString("ui.params.view.flat.l1"),
                        lm.getString("ui.params.view.flat.l2")
                    );
                    groupCombo.setValue(sel);
                }
                if (colCommand != null) colCommand.setText(lm.getString("ui.params.col.command"));
                if (colValue != null) colValue.setText(lm.getString("ui.params.col.value"));
                if (colUnit != null) colUnit.setText(lm.getString("ui.params.col.unit"));
                if (colRange != null) colRange.setText(lm.getString("ui.params.col.range"));
                if (colDesc != null) colDesc.setText(lm.getString("ui.params.col.description"));
                if (tColGroup != null) tColGroup.setText(lm.getString("ui.params.col.group"));
                if (tColCommand != null) tColCommand.setText(lm.getString("ui.params.col.command"));
                if (tColValue != null) tColValue.setText(lm.getString("ui.params.col.value"));
                if (tColUnit != null) tColUnit.setText(lm.getString("ui.params.col.unit"));
                if (tColRange != null) tColRange.setText(lm.getString("ui.params.col.range"));
                if (tColDesc != null) tColDesc.setText(lm.getString("ui.params.col.description"));
                updateMetaStatsLabel();
            });
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.params.updateUI", e); }
    }

    private void updateMetaStatsLabel() {
        try {
            com.serialcomm.service.ParamMetadataService svc = com.serialcomm.service.ParamMetadataService.getInstance();
            int total = svc.totalEntries();
            int apm = svc.apmEntries();
            int px4 = svc.px4Entries();
            long ts = svc.lastReloadMillis();
            java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            String timeStr = java.time.Instant.ofEpochMilli(ts)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(fmt);
            LanguageManager lm = LanguageManager.getInstance();
            String txt = String.format(lm.getString("ui.params.meta.stats"), total, apm, px4, timeStr)
                    .replace("%d", "%d").replace("%s", "%s");
            UiUpdateQueue.get().submit("params.meta.stats", () -> { if (metaStatsLabel != null) metaStatsLabel.setText(txt); });
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.params.meta.stats", e); }
    }

    private String esc(String s) { if (s == null) return ""; String t=s.replace("\"","\"\""); if (t.contains(",")) return '"'+t+'"'; return t; }

    @Override protected void onDataReceived(String data) {}

    public static final class Row {
        private final javafx.beans.property.SimpleStringProperty command;
        private final javafx.beans.property.SimpleStringProperty value;
        private final javafx.beans.property.SimpleStringProperty unit;
        private final javafx.beans.property.SimpleStringProperty range;
        private final javafx.beans.property.SimpleStringProperty description;
        public Row(String command, String value, String unit, String range, String description) {
            this.command = new javafx.beans.property.SimpleStringProperty(command);
            this.value = new javafx.beans.property.SimpleStringProperty(value);
            this.unit = new javafx.beans.property.SimpleStringProperty(unit);
            this.range = new javafx.beans.property.SimpleStringProperty(range);
            this.description = new javafx.beans.property.SimpleStringProperty(description);
        }
        public javafx.beans.property.SimpleStringProperty commandProperty() { return command; }
        public javafx.beans.property.SimpleStringProperty valueProperty() { return value; }
        public javafx.beans.property.SimpleStringProperty unitProperty() { return unit; }
        public javafx.beans.property.SimpleStringProperty rangeProperty() { return range; }
        public javafx.beans.property.SimpleStringProperty descriptionProperty() { return description; }
    }

    public static final class TreeRow {
        private final javafx.beans.property.SimpleStringProperty group;
        private final javafx.beans.property.SimpleStringProperty command;
        private final javafx.beans.property.SimpleStringProperty value;
        private final javafx.beans.property.SimpleStringProperty unit;
        private final javafx.beans.property.SimpleStringProperty range;
        private final javafx.beans.property.SimpleStringProperty description;
        public TreeRow(String group, String command, String value, String unit, String range, String description) {
            this.group = new javafx.beans.property.SimpleStringProperty(group);
            this.command = new javafx.beans.property.SimpleStringProperty(command);
            this.value = new javafx.beans.property.SimpleStringProperty(value);
            this.unit = new javafx.beans.property.SimpleStringProperty(unit);
            this.range = new javafx.beans.property.SimpleStringProperty(range);
            this.description = new javafx.beans.property.SimpleStringProperty(description);
        }
        public javafx.beans.property.SimpleStringProperty groupProperty() { return group; }
        public javafx.beans.property.SimpleStringProperty commandProperty() { return command; }
        public javafx.beans.property.SimpleStringProperty valueProperty() { return value; }
        public javafx.beans.property.SimpleStringProperty unitProperty() { return unit; }
        public javafx.beans.property.SimpleStringProperty rangeProperty() { return range; }
        public javafx.beans.property.SimpleStringProperty descriptionProperty() { return description; }
    }
}

