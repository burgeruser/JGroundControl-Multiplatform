package com.serialcomm.controller;

import com.MAVLink.MAVLinkPacket;
import com.MAVLink.Messages.MAVLinkMessage;
import com.MAVLink.Parser;
import com.serialcomm.util.LanguageManager;
import javafx.application.Platform;
import com.serialcomm.util.UiUpdateQueue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Inspector tab: shows recent messages and frequency within a sliding window.
 * Data ingestion is driven by SerialRouter -> active controller bytes; here we rely on BaseController mode HEX.
 */
public class InspectorTabController extends BaseController {
	private static final Logger logger = LoggerFactory.getLogger(InspectorTabController.class);

	@FXML private Label filterLabel; @FXML private TextField filterField;
	@FXML private Label windowLabel; @FXML private ComboBox<String> windowCombo;
	@FXML private Button clearBtn;

	@FXML private TableView<Row> table;
	@FXML private TableColumn<Row, String> colTime;
	@FXML private TableColumn<Row, String> colSys;
	@FXML private TableColumn<Row, String> colComp;
	@FXML private TableColumn<Row, String> colMsgId;
	@FXML private TableColumn<Row, String> colName;
	@FXML private TableColumn<Row, String> colFreq;

	private final ObservableList<Row> rows = FXCollections.observableArrayList();
	private final Map<String, Row> keyToRow = new ConcurrentHashMap<>();
	private final Parser parser = new Parser();
	private final Map<String, Counter> freq = new ConcurrentHashMap<>();
	private volatile long windowMs = 1000;
    private final ConcurrentLinkedQueue<Update> pending = new ConcurrentLinkedQueue<>();
    private static final class Update {
        final int sys; final int comp; final int msgid; final String name; final long ts;
        Update(int sys, int comp, int msgid, String name, long ts) { this.sys = sys; this.comp = comp; this.msgid = msgid; this.name = name; this.ts = ts; }
    }

	@FXML
	public void initialize() {
		try {
			super.initialize();

			currentReceiveMode = ReceiveMode.HEX;

			colTime.setCellValueFactory(data -> data.getValue().timeProperty());
			colSys.setCellValueFactory(data -> data.getValue().sysProperty());
			colComp.setCellValueFactory(data -> data.getValue().compProperty());
			colMsgId.setCellValueFactory(data -> data.getValue().msgidProperty());
			colName.setCellValueFactory(data -> data.getValue().nameProperty());
			colFreq.setCellValueFactory(data -> data.getValue().freqProperty());

			table.setItems(rows);

			windowCombo.setItems(FXCollections.observableArrayList("1s", "2s", "5s"));
			windowCombo.setValue("1s");
			windowCombo.valueProperty().addListener((o, a, b) -> {
				if (b == null) return;
				String val = b;
				if (val.endsWith("秒")) val = val.substring(0, val.length()-1) + "s"; // zh label to seconds
                if (val.endsWith("s")) {
                    try { windowMs = Long.parseLong(val.substring(0, val.length()-1)) * 1000L; } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.inspector.period", e); }
                }
			});

			clearBtn.setOnAction(e -> rows.clear());
			clearBtn.setOnAction(e -> {
				rows.clear();
				keyToRow.clear();
				freq.clear();
			});

			// Subscribe to central dispatcher for real-time updates
            try {
                com.serialcomm.service.MavlinkDispatcher.getInstance().addListener(pkt -> {
                    try { handlePacket(pkt); } catch (Exception t) { com.serialcomm.util.ErrorMonitor.record("inspector.handlePacket", t); }
                });
            } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("inspector.subscribe", t); }

			logger.info("InspectorTabController initialized");
		} catch (Exception e) {
			logger.error("InspectorTab initialization failed", e);
		}
	}

    @Override
    protected void onDataReceived(String data) {
        // Real-time packets are already delivered via central dispatcher subscription;
        // do not re-parse here to avoid double-counting frequencies
    }

    private void handlePacket(MAVLinkPacket pkt) {
        MAVLinkMessage msg = pkt.unpack();
        String name = (msg != null) ? msg.getClass().getSimpleName() : ("MSG#" + pkt.msgid);
        String key = pkt.sysid + ":" + pkt.compid + ":" + name;
        Counter c = freq.computeIfAbsent(key, k -> new Counter());
        long now = System.currentTimeMillis();
        c.add(now);
        pending.add(new Update(pkt.sysid, pkt.compid, pkt.msgid, name, now));
        UiUpdateQueue.get().submit("inspector.tick", this::drainAndApply);
    }

    private void drainAndApply() {
        try {
            String filter = filterField != null ? filterField.getText() : null;
            String f = (filter == null) ? null : filter.trim().toLowerCase();
            int selSys = -1, selComp = -1;
            try {
                selSys = com.serialcomm.service.DeviceSelectionService.getInstance().selectedSys();
                selComp = com.serialcomm.service.DeviceSelectionService.getInstance().selectedComp();
            } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.inspector.filter", e); }

            Update u;
            while ((u = pending.poll()) != null) {
                if (f != null && !f.isBlank()) {
                    if (!(u.name.toLowerCase().contains(f) || (""+u.msgid).contains(f))) continue;
                }
                if (!(selSys < 0 && selComp < 0)) {
                    if (selSys >= 0 && u.sys != selSys) continue;
                    if (selComp >= 0 && u.comp != selComp) continue;
                }
                String key = u.sys + ":" + u.comp + ":" + u.name;
                String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
                String sys = String.valueOf(u.sys);
                String comp = String.valueOf(u.comp);
                String msgIdStr = String.valueOf(u.msgid);
                Row row = keyToRow.get(key);
                if (row == null) {
                    row = new Row(time, sys, comp, msgIdStr, u.name, "0.0");
                    keyToRow.put(key, row);
                    rows.add(row);
                    if (rows.size() > 500) rows.remove(0);
                }
                row.timeProperty().set(time);
            }
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Row> e : keyToRow.entrySet()) {
                Counter cc = freq.get(e.getKey());
                if (cc == null) continue;
                double hz = cc.hz(now, windowMs);
                e.getValue().freqProperty().set(String.format("%.2f", hz));
            }
        } catch (Exception ex) { com.serialcomm.util.ErrorMonitor.record("ui.inspector.drain", ex); }
    }

	public void updateUI() {
		LanguageManager lm = languageManager;
		if (lm == null) return;
        UiUpdateQueue.get().submit("inspector.updateUI", () -> {
			// Ensure table headers and controls update with i18n
			if (colTime != null) colTime.setText(lm.getString("ui.inspector.time"));
			if (colSys != null) colSys.setText(lm.getString("ui.inspector.sys"));
			if (colComp != null) colComp.setText(lm.getString("ui.inspector.comp"));
			if (colMsgId != null) colMsgId.setText(lm.getString("ui.inspector.msgid"));
			if (colName != null) colName.setText(lm.getString("ui.inspector.name"));
			if (colFreq != null) colFreq.setText(lm.getString("ui.inspector.freq"));
			if (filterField != null) filterField.setPromptText(lm.getString("ui.inspector.filter.prompt"));
			if (filterLabel != null) filterLabel.setText(lm.getString("ui.inspector.filter"));
			if (windowLabel != null) windowLabel.setText(lm.getString("ui.inspector.window"));
			if (clearBtn != null) clearBtn.setText(lm.getString("ui.inspector.clear"));
			if (windowCombo != null && windowCombo.getItems().isEmpty()) {
				windowCombo.setItems(FXCollections.observableArrayList(
					lm.getString("ui.window.1s"), lm.getString("ui.window.2s"), lm.getString("ui.window.5s")
				));
				windowCombo.setValue(lm.getString("ui.window.1s"));
			}
        });
	}

    /** Called when global device selection changes to prune non-matching rows immediately. */
    public void onGlobalDeviceChanged() {
        try {
            int selSys = com.serialcomm.service.DeviceSelectionService.getInstance().selectedSys();
            int selComp = com.serialcomm.service.DeviceSelectionService.getInstance().selectedComp();
            UiUpdateQueue.get().submit("inspector.prune", () -> {
                try {
                    if (selSys < 0 && selComp < 0) {
                        // ALL: keep existing rows
                        return;
                    }
                    java.util.List<Row> toRemove = new java.util.ArrayList<>();
                    for (java.util.Map.Entry<String, Row> e : keyToRow.entrySet()) {
                        String key = e.getKey(); // sys:comp:name
                        String[] parts = key.split(":", 3);
                        if (parts.length < 3) continue;
                        int sys = Integer.parseInt(parts[0]);
                        int comp = Integer.parseInt(parts[1]);
                        if ((selSys >= 0 && sys != selSys) || (selComp >= 0 && comp != selComp)) {
                            toRemove.add(e.getValue());
                        }
                    }
                    // Remove from rows and maps
                    for (Row r : toRemove) {
                        rows.remove(r);
                    }
                    keyToRow.entrySet().removeIf(en -> {
                        String[] parts = en.getKey().split(":", 3);
                        if (parts.length < 3) return false;
                        int sys = Integer.parseInt(parts[0]);
                        int comp = Integer.parseInt(parts[1]);
                        return (selSys >= 0 && sys != selSys) || (selComp >= 0 && comp != selComp);
                    });
                    freq.keySet().removeIf(k -> {
                        String[] parts = k.split(":", 3);
                        if (parts.length < 3) return false;
                        int sys = Integer.parseInt(parts[0]);
                        int comp = Integer.parseInt(parts[1]);
                        return (selSys >= 0 && sys != selSys) || (selComp >= 0 && comp != selComp);
                    });
                    if (table != null) table.refresh();
                } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.inspector.prune", e); }
            });
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.inspector.globalChange", e); }
    }

	private static final class Counter {
		private final java.util.ArrayDeque<Long> times = new java.util.ArrayDeque<>();
		synchronized void add(long t) { times.addLast(t); }
		synchronized double hz(long now, long windowMs) {
			while (!times.isEmpty() && now - times.peekFirst() > windowMs) {
				times.removeFirst();
			}
			return times.size() * 1000.0 / Math.max(1, windowMs);
		}
	}

	public static final class Row {
		private final javafx.beans.property.SimpleStringProperty time;
		private final javafx.beans.property.SimpleStringProperty sys;
		private final javafx.beans.property.SimpleStringProperty comp;
		private final javafx.beans.property.SimpleStringProperty msgid;
		private final javafx.beans.property.SimpleStringProperty name;
		private final javafx.beans.property.SimpleStringProperty freq;
		public Row(String time, String sys, String comp, String msgid, String name, String freq) {
			this.time = new javafx.beans.property.SimpleStringProperty(time);
			this.sys = new javafx.beans.property.SimpleStringProperty(sys);
			this.comp = new javafx.beans.property.SimpleStringProperty(comp);
			this.msgid = new javafx.beans.property.SimpleStringProperty(msgid);
			this.name = new javafx.beans.property.SimpleStringProperty(name);
			this.freq = new javafx.beans.property.SimpleStringProperty(freq);
		}
		public javafx.beans.property.SimpleStringProperty timeProperty() { return time; }
		public javafx.beans.property.SimpleStringProperty sysProperty() { return sys; }
		public javafx.beans.property.SimpleStringProperty compProperty() { return comp; }
		public javafx.beans.property.SimpleStringProperty msgidProperty() { return msgid; }
		public javafx.beans.property.SimpleStringProperty nameProperty() { return name; }
		public javafx.beans.property.SimpleStringProperty freqProperty() { return freq; }
	}
}

