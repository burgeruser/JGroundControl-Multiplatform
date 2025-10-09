package com.serialcomm.controller;

import com.serialcomm.service.VehicleState;
import com.serialcomm.util.LanguageManager;
import com.serialcomm.service.TelemetryRegistry;
import com.serialcomm.service.MessageStatsRegistry;
import javafx.application.Platform;
import com.serialcomm.util.UiUpdateQueue;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live status tab - TreeTableView (2-level): MSG -> fields, with SYS:COMP filter dropdown.
 */
public class StatusTabController extends BaseController {
	private static final Logger logger = LoggerFactory.getLogger(StatusTabController.class);

	@FXML private TreeTableView<NodeRow> statusTree;
	@FXML private TreeTableColumn<NodeRow, String> colName;
	@FXML private TreeTableColumn<NodeRow, String> colValue;
	@FXML private TreeTableColumn<NodeRow, String> colRemark;
	@FXML private TreeTableColumn<NodeRow, String> colEnum;
	@FXML private TreeTableColumn<NodeRow, String> colFreq;
    @FXML private Label statusRefreshLabel; @FXML private javafx.scene.control.ComboBox<String> statusRefreshCombo; @FXML private Button refreshBtn;

    private java.util.concurrent.ScheduledFuture<?> refreshTask;
    private volatile String selectedSysComp;
    private final java.util.Map<String, java.util.Set<String>> expandedBySysComp = new java.util.HashMap<>();
	private final java.util.concurrent.atomic.AtomicBoolean renderEnqueued = new java.util.concurrent.atomic.AtomicBoolean(false);

	@FXML
	public void initialize() {
		try {
			super.initialize();
			currentReceiveMode = ReceiveMode.HEX; // passive

			if (statusTree != null) {
				colName.setCellValueFactory(param -> param.getValue().getValue().nameProperty());
				colValue.setCellValueFactory(param -> param.getValue().getValue().valueProperty());
				if (colRemark != null) colRemark.setCellValueFactory(param -> param.getValue().getValue().remarkProperty());
				colEnum.setCellValueFactory(param -> param.getValue().getValue().enumProperty());
				colFreq.setCellValueFactory(param -> param.getValue().getValue().freqProperty());
				statusTree.setShowRoot(false);
				statusTree.setRoot(new TreeItem<>(new NodeRow("root","","","","")));
			}

			if (refreshBtn != null) refreshBtn.setOnAction(e -> renderOnce());

			if (statusRefreshCombo != null) {
				statusRefreshCombo.setItems(javafx.collections.FXCollections.observableArrayList("250ms", "500ms", "1s"));
				statusRefreshCombo.setValue("250ms");
				statusRefreshCombo.valueProperty().addListener((o,a,b)->restartTimer(b));
			}

            // selectedSysComp now driven by DeviceSelectionService (global toolbar)
            restartTimer(statusRefreshCombo != null ? statusRefreshCombo.getValue() : "250ms");
		} catch (Exception e) {
			logger.error("StatusTab init failed", e);
		}
	}

    private void restartTimer(String label) {
        stopTimer();
        long period = parsePeriodMs(label, 250L);
        java.util.concurrent.ScheduledExecutorService exec = com.serialcomm.service.Scheduler.getInstance().ensureMonitoring();
        refreshTask = exec.scheduleAtFixedRate(this::renderOnce, period, period, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

	private long parsePeriodMs(String label, long def) {
		if (label == null) return def;
		try {
			String s = label.trim();
			// Normalize common units
			s = s.replace("毫秒", "ms").replace("秒", "s");
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(ms|s)?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
			if (m.find()) {
				double num = Double.parseDouble(m.group(1));
				String unit = m.group(2);
				if (unit == null || unit.equalsIgnoreCase("ms")) return (long) num;
				if (unit.equalsIgnoreCase("s")) return (long) (num * 1000.0);
			}
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.status.parse.period", e); }
		return def;
	}

    private void stopTimer() {
        if (refreshTask != null) { try { refreshTask.cancel(true); } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.status.timer.cancel", e); } refreshTask = null; }
    }

	private void renderOnce() {
		if (!renderEnqueued.compareAndSet(false, true)) return;
        final java.util.List<TelemetryRegistry.Item> snapshot = TelemetryRegistry.getInstance().snapshotOrdered();
		final java.util.Map<String, java.lang.Double> msgHz = new java.util.HashMap<>();
		for (MessageStatsRegistry.Entry e : MessageStatsRegistry.getInstance().snapshot(1000)) {
			msgHz.put(e.key, e.hz);
		}
        UiUpdateQueue.get().submit("status.render", () -> {
			TreeItem<NodeRow> root = statusTree.getRoot();
			if (root == null) {
				root = new TreeItem<>(new NodeRow("root","","","",""));
				statusTree.setRoot(root);
			}

            // Use global device selection
            try {
                int selSys = com.serialcomm.service.DeviceSelectionService.getInstance().selectedSys();
                int selComp = com.serialcomm.service.DeviceSelectionService.getInstance().selectedComp();
                selectedSysComp = (selSys < 0 && selComp < 0) ? null : (selSys + ":" + selComp);
            } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.status.sel", e); }

			// Save previous expanded messages for this syscomp
			java.util.Set<String> prevExpanded = new java.util.HashSet<>();
			if (root.getChildren() != null) {
				for (TreeItem<NodeRow> msgItem : root.getChildren()) {
					if (msgItem.isExpanded()) prevExpanded.add(msgItem.getValue().nameProperty().get());
				}
			}
            if (selectedSysComp != null) {
				expandedBySysComp.put(selectedSysComp, prevExpanded);
			}

			// Rebuild as 2-level: MSG -> fields (filtered by selected sys:comp)
			root.getChildren().clear();
			java.util.Map<String, java.util.List<TelemetryRegistry.Item>> fieldsByMsg = new java.util.LinkedHashMap<>();
			for (TelemetryRegistry.Item it : snapshot) {
				int sp = it.key.indexOf(' ');
				if (sp <= 0) continue;
                String syscomp = it.key.substring(0, sp);
                if (selectedSysComp != null && !selectedSysComp.equals(syscomp)) continue;
				String rest = it.key.substring(sp+1);
				int dot = rest.indexOf('.');
				if (dot <= 0) continue;
				String msgName = rest.substring(0, dot);
				fieldsByMsg.computeIfAbsent(msgName, k -> new java.util.ArrayList<>()).add(it);
			}

			java.util.List<String> msgNames = new java.util.ArrayList<>(fieldsByMsg.keySet());
			java.util.Collections.sort(msgNames);
			java.util.Set<String> savedExpanded = selectedSysComp == null ? java.util.Collections.emptySet() : expandedBySysComp.getOrDefault(selectedSysComp, java.util.Collections.emptySet());
			int added = 0;
			for (String msgName : msgNames) {
				String hzKey = (selectedSysComp == null ? "" : selectedSysComp + ":") + msgName;
				TreeItem<NodeRow> msgItem = new TreeItem<>(new NodeRow(msgName, "", "", "", formatHz(msgHz.getOrDefault(hzKey, 0.0))));
				java.util.List<TelemetryRegistry.Item> fields = fieldsByMsg.get(msgName);
				fields.sort(java.util.Comparator.comparing(i -> i.key));
				for (TelemetryRegistry.Item it : fields) {
					String key = it.key;
					int sp = key.indexOf(' ');
					String rest = key.substring(sp+1);
					int dot = rest.indexOf('.');
					String field = rest.substring(dot+1);
					String enumText = com.serialcomm.service.EnumLabeler.label(msgName, field, it.value);
					String remark = com.serialcomm.service.RemarkLabeler.remark(msgName, field);
					msgItem.getChildren().add(new TreeItem<>(new NodeRow(field, remark, it.value, enumText, "")));
					if (++added > 1000) break; // safety cap
				}
				boolean expand = savedExpanded.isEmpty() || savedExpanded.contains(msgName);
				msgItem.setExpanded(expand);
				root.getChildren().add(msgItem);
				if (added > 1000) break;
			}
            statusTree.refresh();
			renderEnqueued.set(false);
		});
	}

	private String formatHz(double h) { return String.format("%.2f", h); }

	@Override
	protected void onDataReceived(String data) {
		// passive; state updates come from central dispatcher
	}

	public void updateUI() {
		try {
			LanguageManager lm = LanguageManager.getInstance();
			UiUpdateQueue.get().submit("status.updateUI", () -> {
				if (refreshBtn != null) refreshBtn.setText(lm.getString("ui.status.refresh"));
				if (statusRefreshLabel != null) statusRefreshLabel.setText(lm.getString("ui.status.refresh.period"));
				if (statusRefreshCombo != null && statusRefreshCombo.getItems().isEmpty()) {
					statusRefreshCombo.setItems(javafx.collections.FXCollections.observableArrayList(
						lm.getString("ui.refresh.250ms"), lm.getString("ui.refresh.500ms"), lm.getString("ui.refresh.1s")
					));
					statusRefreshCombo.setValue(lm.getString("ui.refresh.250ms"));
				}
			if (colName != null) colName.setText(lm.getString("ui.status.name"));
			if (colRemark != null) colRemark.setText(lm.getString("ui.status.remark"));
				if (colValue != null) colValue.setText(lm.getString("ui.status.value"));
				if (colEnum != null) colEnum.setText(lm.getString("ui.status.enum"));
				if (colFreq != null) colFreq.setText(lm.getString("ui.status.freq"));
			});
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.status.updateUI", e); }
		renderOnce();
	}

	@Override
	public void cleanup() {
        stopTimer();
		super.cleanup();
	}

	public static final class NodeRow {
		private final javafx.beans.property.SimpleStringProperty name;
		private final javafx.beans.property.SimpleStringProperty value;
		private final javafx.beans.property.SimpleStringProperty enumLabel;
		private final javafx.beans.property.SimpleStringProperty freq;
		private final javafx.beans.property.SimpleStringProperty remark;
		public NodeRow(String name, String remark, String value, String enumLabel, String freq) {
			this.name = new javafx.beans.property.SimpleStringProperty(name);
			this.remark = new javafx.beans.property.SimpleStringProperty(remark);
			this.value = new javafx.beans.property.SimpleStringProperty(value);
			this.enumLabel = new javafx.beans.property.SimpleStringProperty(enumLabel);
			this.freq = new javafx.beans.property.SimpleStringProperty(freq);
		}
		public javafx.beans.property.SimpleStringProperty nameProperty() { return name; }
		public javafx.beans.property.SimpleStringProperty remarkProperty() { return remark; }
		public javafx.beans.property.SimpleStringProperty valueProperty() { return value; }
		public javafx.beans.property.SimpleStringProperty enumProperty() { return enumLabel; }
		public javafx.beans.property.SimpleStringProperty freqProperty() { return freq; }
	}
}

