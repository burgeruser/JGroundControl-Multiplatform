package com.serialcomm.controller;

import com.fazecast.jSerialComm.SerialPort;
import com.serialcomm.controller.BaseController.StatusCallback;
import com.serialcomm.serial.SerialRouter;
import com.serialcomm.link.LinkManager;
import com.serialcomm.util.LanguageChangeListener;
import com.serialcomm.util.LanguageManager;
import com.serialcomm.service.VehicleState;
import com.serialcomm.util.UiUpdateQueue;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MainController implements StatusCallback, LanguageChangeListener {
    private static final Logger logger = LoggerFactory.getLogger(MainController.class);
    
    /** Language manager instance providing localized strings */
    private LanguageManager languageManager;
    
    @FXML
    private ComboBox<String> portComboBox;
    @FXML
    private ComboBox<String> baudRateComboBox;
    @FXML
    private Button connectButton;
    @FXML
    private Button refreshButton;
    @FXML
    private TabPane mainTabPane;
    @FXML
    private Label statusLabel;
    @FXML
    private Label byteCountLabel;
    @FXML
    private Label rxBandwidthLabel;
    @FXML
    private Label txBandwidthLabel;
    @FXML
    private Button clearByteCountButton;
    @FXML
    private ComboBox<String> languageComboBox;
    @FXML
    private ComboBox<com.serialcomm.link.LinkManager.Transport> transportComboBox;
    @FXML
    private ComboBox<String> deviceComboBox;
    @FXML
    private Label deviceLabel;
    @FXML
    private Label logLevelLabel;
    @FXML
    private ComboBox<com.serialcomm.service.LogLevelService.Level> logLevelComboBox;
    @FXML
    private Label transportLabel;
    @FXML
    private Label portLabel;
    @FXML
    private Label baudRateLabel;
    @FXML
    private Label languageLabel;
    @FXML
    private ComboBox<String> timerPeriodComboBox;
    @FXML
    private Label timerPeriodLabel;
    @FXML
    private Label onlineLabel;
    @FXML
    private Label onlineValueLabel;
    
    private DebugTabController debugTabController;
    private ProtocolTabController protocolTabController;
    private MavlinkTabController mavlinkTabController;
    private InspectorTabController inspectorTabController;
    private StatusTabController statusTabController;
    private ParamTabController paramTabController;
    private VisualStatusTabController visualStatusTabController;
    
    private java.util.concurrent.ScheduledFuture<?> uiMonitorTask;
    private java.util.concurrent.ScheduledFuture<?> autoDevicePickTask;

    private final List<String> recentLogs = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger logIndex = new AtomicInteger(0);
    
    private final AtomicLong totalBytesReceived = new AtomicLong(0);
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private long lastBytesReceived = 0;
    private long lastBytesSent = 0;
    private final AtomicLong rxBitPerSec = new AtomicLong(0);
    private final AtomicLong txBitPerSec = new AtomicLong(0);
    private boolean transportInitialized = false;
    
    @FXML
    public void initialize() {
        try {
            // 初始化语言管理器
            languageManager = LanguageManager.getInstance();
            languageManager.addLanguageChangeListener(this);
            
            ObservableList<String> baudRates = FXCollections.observableArrayList(
                    "9600", "19200", "38400", "57600", "100000", "115200", "230400", "460800", "921600", "1000000", "1500000", "2000000"
            );
            baudRateComboBox.setItems(baudRates);
            baudRateComboBox.setValue("115200");
            
            // 初始化定时器周期选择器
            ObservableList<String> timerPeriods = FXCollections.observableArrayList(
                    "10ms", "20ms", "50ms", "75ms", "100ms", "150ms", "200ms", "250ms", "500ms"
            );
            timerPeriodComboBox.setItems(timerPeriods);
            timerPeriodComboBox.setValue("50ms");
            
            // 添加定时器周期更改监听器
            timerPeriodComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null) {
                    handleTimerPeriodChange(newVal);
                }
            });
            
            refreshButton.setOnAction(this::refreshPorts);
            connectButton.setOnAction(this::toggleConnection);
            clearByteCountButton.setOnAction(this::clearByteCount);
            
            // 初始化语言选择器
            initializeLanguageSelector();

            // 初始化传输层选择器
            initializeTransportSelector();
            // 初始化设备选择器
            initializeDeviceSelector();
            
            mainTabPane.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldTab, newTab) -> handleTabChange(oldTab, newTab)
            );
            
            refreshPorts(null);
            updateByteCountDisplay();
            
            // 初始化界面文本
            updateUI();
            
            com.serialcomm.service.StatusBarService.getInstance().bind(statusLabel, languageManager);
            updateStatusBar(languageManager.getString("status.app.started"));
            MDC.put("sid", safeCurrentSessionId());
            try { logger.info(languageManager.getString("log.controller.main.init")); }
            finally { MDC.remove("sid"); }

            // 将全局字节统计回调设置到统一链路管理（同时覆盖串口与网络）
            com.serialcomm.service.TransportViewModel.getInstance().setByteCountCallbacks(this::addReceivedBytes, this::addSentBytes);
            // 初始时确保网络侧的活动控制器也同步
            BaseController owner = getActiveController();
            if (owner != null) com.serialcomm.service.TransportViewModel.getInstance().setActiveController(owner);
            // 启动UI状态监控任务（每秒一次）
            startUiMonitor();
        } catch (Exception e) {
            MDC.put("sid", safeCurrentSessionId());
            try { logger.error(languageManager.getString("log.controller.main.init.failed"), e); }
            finally { MDC.remove("sid"); }
            updateStatusBar(languageManager.getString("error.controller.init.failed", e.getMessage()));
            showAlert(Alert.AlertType.ERROR, languageManager.getString("dialog.title.error"), 
                languageManager.getString("error.controller.init.failed", e.getMessage()));
        }
    }

    private void initializeDeviceSelector() {
        try {
            if (deviceComboBox == null) return;
            refreshDeviceComboItems();
            deviceComboBox.valueProperty().addListener((obs, oldV, newV) -> applySelectedDevice(newV));
            java.util.List<String> list = com.serialcomm.service.DeviceSelectionService.getInstance().enumerateDevices();
            if (!list.isEmpty()) {
                String first = list.get(0);
                deviceComboBox.getSelectionModel().select(first);
                applySelectedDevice(first);
            }
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.device.init", e); }
    }

    private void refreshDeviceComboItems() {
        try {
            java.util.List<String> raw = com.serialcomm.service.DeviceSelectionService.getInstance().enumerateDevices();
            // Map "ALL" to localized label for display
            String allLabel = null;
            try { allLabel = LanguageManager.getInstance().getString("ui.main.device.all"); } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.device.label", e); }
            final String displayAll = (allLabel != null && !allLabel.isEmpty()) ? allLabel : "ALL";
            java.util.List<String> display = new java.util.ArrayList<>();
            if (!raw.isEmpty()) {
                // First item is "ALL" by service contract
                display.add(displayAll);
                for (int i = 1; i < raw.size(); i++) display.add(raw.get(i));
            }
            if (deviceComboBox != null) {
                UiUpdateQueue.get().submit("main.device.items", () -> {
                    java.util.List<String> current = new java.util.ArrayList<>(deviceComboBox.getItems());
                    if (!current.equals(display)) {
                        String prev = deviceComboBox.getValue();
                        deviceComboBox.getItems().setAll(display);
                        // Keep previous selection if still present; otherwise select first
                        if (prev != null && display.contains(prev)) {
                            deviceComboBox.getSelectionModel().select(prev);
                        } else if (!display.isEmpty()) {
                            deviceComboBox.getSelectionModel().select(display.get(0));
                        }
                    }
                });
            }
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.device.refresh", e); }
    }

    

    private void applySelectedDevice(String sysComp) {
        try {
            if (sysComp == null || sysComp.isEmpty()) return;
            if (isAllDevice(sysComp)) {
                com.serialcomm.service.DeviceSelectionService.getInstance().setSelected(-1, -1);
                if (inspectorTabController != null) inspectorTabController.onGlobalDeviceChanged();
                if (statusTabController != null) statusTabController.updateUI();
                if (paramTabController != null) paramTabController.onGlobalDeviceChanged();
                if (visualStatusTabController != null) visualStatusTabController.onGlobalDeviceChanged();
                return;
            }
            String[] parts = sysComp.split(":");
            if (parts.length != 2) return;
            int sys = Integer.parseInt(parts[0]);
            int comp = Integer.parseInt(parts[1]);
            com.serialcomm.service.DeviceSelectionService.getInstance().setSelected(sys, comp);
            if (inspectorTabController != null) inspectorTabController.onGlobalDeviceChanged();
            if (statusTabController != null) statusTabController.updateUI();
            if (paramTabController != null) paramTabController.onGlobalDeviceChanged();
            if (visualStatusTabController != null) visualStatusTabController.onGlobalDeviceChanged();
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.device.apply", e); }
    }

    private boolean isAllDevice(String v) {
        if (v == null) return false;
        String allZh = null, allEn = null;
        try { allZh = LanguageManager.getInstance().getString("ui.main.device.all"); } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.device.all.zh", e); }
        try { allEn = "ALL"; } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.device.all.en", e); }
        return v.equalsIgnoreCase("ALL") || (allZh != null && v.equals(allZh)) || (allEn != null && v.equals(allEn));
    }
    
    /**
     * 初始化语言选择器
     * 设置支持的语言选项和当前选择
     */
    private void initializeLanguageSelector() {
        if (languageComboBox == null || languageManager == null) return;
        
        // 获取支持的语言列表
        String[] languageNames = languageManager.getSupportedLanguageNames();
        languageComboBox.getItems().addAll(languageNames);
        
        // 设置当前语言
        languageComboBox.setValue(languageManager.getCurrentLanguageName());
        
        // 添加语言切换监听器
        languageComboBox.setOnAction(event -> {
            String selectedLanguage = languageComboBox.getValue();
            if (selectedLanguage != null) {
                // 根据显示名称找到对应的Locale
                Locale[] supportedLanguages = languageManager.getSupportedLanguages();
                for (Locale locale : supportedLanguages) {
                    if (locale.getDisplayName(locale).equals(selectedLanguage)) {
                        languageManager.setLanguage(locale);
                        try { com.serialcomm.util.Settings.setSavedLocale(locale); } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.locale.save", e); }
                        break;
                    }
                }
            }
        });
    }

    private void initializeLogLevelSelector() {
        if (logLevelComboBox == null) return;
        try {
            com.serialcomm.service.LogLevelService svc = com.serialcomm.service.LogLevelService.getInstance();
            javafx.collections.ObservableList<com.serialcomm.service.LogLevelService.Level> items = javafx.collections.FXCollections.observableArrayList(svc.supportedLevels());
            if (logLevelComboBox.getItems() == null || logLevelComboBox.getItems().isEmpty()) {
                logLevelComboBox.setItems(items);
                String saved = com.serialcomm.util.Settings.getSavedLogLevel();
                com.serialcomm.service.LogLevelService.Level def = svc.parseLevelOrDefault(saved, com.serialcomm.service.LogLevelService.Level.WARN);
                logLevelComboBox.setValue(def);
                logLevelComboBox.valueProperty().addListener((obs, oldV, newV) -> applyLogLevel(newV));
                // Render enum names localized if需要（当前直接显示枚举名）
                logLevelComboBox.setCellFactory(cb -> new javafx.scene.control.ListCell<com.serialcomm.service.LogLevelService.Level>() {
                    @Override protected void updateItem(com.serialcomm.service.LogLevelService.Level item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? "" : item.name());
                    }
                });
                javafx.scene.control.ListCell<com.serialcomm.service.LogLevelService.Level> btn = new javafx.scene.control.ListCell<com.serialcomm.service.LogLevelService.Level>() {
                    @Override protected void updateItem(com.serialcomm.service.LogLevelService.Level item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? "" : item.name());
                    }
                };
                logLevelComboBox.setButtonCell(btn);
            } else {
                int idx = logLevelComboBox.getSelectionModel().getSelectedIndex();
                logLevelComboBox.setItems(items);
                logLevelComboBox.getSelectionModel().select(idx >= 0 ? idx : 2);
            }
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.transport.selector", e); }
    }

    private void applyLogLevel(com.serialcomm.service.LogLevelService.Level level) {
        try {
            com.serialcomm.service.LogLevelService.getInstance().applyLevel(level);
            updateStatusBar("Log level set to: " + (level == null ? "" : level.name()));
        } catch (Exception e) {
            MDC.put("sid", safeCurrentSessionId());
            try { logger.error("Failed to apply log level: {}", e.getMessage(), e); }
            finally { MDC.remove("sid"); }
        }
    }

    private void initializeTransportSelector() {
        try {
            if (transportComboBox == null) return;
            if (!transportInitialized) {
                javafx.collections.ObservableList<com.serialcomm.link.LinkManager.Transport> items = javafx.collections.FXCollections.observableArrayList(
                    com.serialcomm.link.LinkManager.Transport.SERIAL,
                    com.serialcomm.link.LinkManager.Transport.TCP,
                    com.serialcomm.link.LinkManager.Transport.UDP
                );
                transportComboBox.setItems(items);
                com.serialcomm.link.LinkManager.Transport current = com.serialcomm.link.LinkManager.getInstance().getCurrentTransport();
                transportComboBox.getSelectionModel().select(current);
                transportComboBox.valueProperty().addListener((obs, oldV, newV) -> { updateTransportVisibility(); updateConnectButtonText(); });
                setTransportCellFactory();
                transportInitialized = true;
            } else {
                setTransportCellFactory();
            }
            updateTransportVisibility();
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.transport.cellFactory", e); }
    }

    private void setTransportCellFactory() {
        try {
            if (transportComboBox == null || languageManager == null) return;
            java.util.function.Function<com.serialcomm.link.LinkManager.Transport, String> toLabel = this::localizedTransport;
            transportComboBox.setCellFactory(cb -> new javafx.scene.control.ListCell<com.serialcomm.link.LinkManager.Transport>() {
                @Override protected void updateItem(com.serialcomm.link.LinkManager.Transport item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? "" : toLabel.apply(item));
                }
            });
            javafx.scene.control.ListCell<com.serialcomm.link.LinkManager.Transport> btn = new javafx.scene.control.ListCell<com.serialcomm.link.LinkManager.Transport>() {
                @Override protected void updateItem(com.serialcomm.link.LinkManager.Transport item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? "" : toLabel.apply(item));
                }
            };
            transportComboBox.setButtonCell(btn);
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.transport.localized", e); }
    }

    private String localizedTransport(com.serialcomm.link.LinkManager.Transport t) {
        try {
            if (t == com.serialcomm.link.LinkManager.Transport.SERIAL) return languageManager.getString("ui.main.transport.serial");
            if (t == com.serialcomm.link.LinkManager.Transport.TCP) return languageManager.getString("ui.main.transport.tcp");
            if (t == com.serialcomm.link.LinkManager.Transport.UDP) return languageManager.getString("ui.main.transport.udp");
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.transport.visibility", e); }
        return String.valueOf(t);
    }

    private void updateTransportVisibility() {
        try {
            boolean isSerial = isSerialSelected();
            if (portLabel != null) portLabel.setVisible(isSerial);
            if (portComboBox != null) portComboBox.setVisible(isSerial);
            if (baudRateLabel != null) baudRateLabel.setVisible(isSerial);
            if (baudRateComboBox != null) baudRateComboBox.setVisible(isSerial);
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.transport.visibility", e); }
    }

    private boolean isSerialSelected() {
        return transportComboBox != null && transportComboBox.getValue() == com.serialcomm.link.LinkManager.Transport.SERIAL;
    }
    
    /**
     * 更新连接按钮文本
     * 根据当前连接状态更新按钮显示文本
     */
    private void updateConnectButtonText() {
        if (connectButton != null) {
            boolean connected = com.serialcomm.service.TransportViewModel.getInstance().isConnected();
            String buttonText = connected ? 
                languageManager.getString("ui.main.disconnect") : 
                languageManager.getString("ui.main.connect");
            connectButton.setText(buttonText);
            MDC.put("sid", safeCurrentSessionId());
            try { logger.debug("Connect button text updated to: {} (connected: {})", buttonText, connected); }
            finally { MDC.remove("sid"); }
        }
    }
    
    /**
     * 更新界面文本
     * 根据当前语言设置更新所有界面元素的文本
     */
    private void updateUI() {
        if (languageManager == null) return;
        
        UiUpdateQueue.get().submit("main.updateUI", () -> {
            // 更新标签文本
            if (portLabel != null) {
                portLabel.setText(languageManager.getString("ui.main.port"));
            }
            if (baudRateLabel != null) {
                baudRateLabel.setText(languageManager.getString("ui.main.baudrate"));
            }
            if (timerPeriodLabel != null) {
                timerPeriodLabel.setText(languageManager.getString("ui.main.timer.period"));
            }
            if (languageLabel != null) {
                languageLabel.setText(languageManager.getString("ui.main.language"));
            }
            if (transportLabel != null) {
                transportLabel.setText(languageManager.getString("ui.main.transport"));
            }
            if (onlineLabel != null) {
                onlineLabel.setText(languageManager.getString("ui.main.online"));
            }
            if (logLevelLabel != null) {
                logLevelLabel.setText(languageManager.getString("ui.main.loglevel"));
            }
            // 更新连接按钮文本
            updateConnectButtonText();
            
            // 更新刷新按钮文本
            if (refreshButton != null) {
                refreshButton.setText(languageManager.getString("ui.main.refresh"));
            }

            // 更新传输层下拉（保持当前选择与连接状态，不重置为串口）
            initializeTransportSelector();

            // 日志级别下拉
            initializeLogLevelSelector();
            
            // 更新清除统计按钮文本
            if (clearByteCountButton != null) {
                clearByteCountButton.setText(languageManager.getString("ui.main.clear.stats"));
            }
            
            // 更新标签页文本
            if (mainTabPane != null) {
                try {
                    for (Tab tab : mainTabPane.getTabs()) {
                        if (tab == null) continue;
                        String id = tab.getId();
                        if (id == null) id = "";
                        switch (id) {
                            case "tabDebug": tab.setText(languageManager.getString("ui.main.tab.debug")); break;
                            case "tabProtocol": tab.setText(languageManager.getString("ui.main.tab.protocol")); break;
                            case "tabMavlink": tab.setText(languageManager.getString("ui.main.tab.mavlink")); break;
                            case "tabInspector": tab.setText(languageManager.getString("ui.main.tab.inspector")); break;
                            case "tabStatus": tab.setText(languageManager.getString("ui.main.tab.status")); break;
                            case "tabVisual": tab.setText(languageManager.getString("ui.main.tab.visual")); break;
                            case "tabParams": tab.setText(languageManager.getString("ui.params.tab")); break;
                            default:
                                // 兜底：按当前匹配覆盖
                                String tabText = tab.getText();
                                if (languageManager.getString("ui.main.tab.debug").equals(tabText)) tab.setText(languageManager.getString("ui.main.tab.debug"));
                                if (languageManager.getString("ui.main.tab.protocol").equals(tabText)) tab.setText(languageManager.getString("ui.main.tab.protocol"));
                                if (languageManager.getString("ui.main.tab.mavlink").equals(tabText)) tab.setText(languageManager.getString("ui.main.tab.mavlink"));
                                if (languageManager.getString("ui.main.tab.inspector").equals(tabText)) tab.setText(languageManager.getString("ui.main.tab.inspector"));
                                if (languageManager.getString("ui.main.tab.status").equals(tabText)) tab.setText(languageManager.getString("ui.main.tab.status"));
                                if (languageManager.getString("ui.main.tab.visual").equals(tabText)) tab.setText(languageManager.getString("ui.main.tab.visual"));
                        }
                    }
                } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.tab.text", e); }
            }
            
            // 更新状态标签
            if (statusLabel != null) {
                statusLabel.setText(languageManager.getString("ui.main.status") + ": ");
            }
            if (deviceLabel != null) {
                deviceLabel.setText(languageManager.getString("ui.main.device"));
            }
            // 更新在线指示
            updateOnlineIndicator();
            // 刷新设备列表
            refreshDeviceComboItems();
            
            // 更新字节统计显示
            updateByteCountDisplay();
            updateBandwidthDisplay();
            
            // 通知子控制器更新界面
            if (debugTabController != null) debugTabController.updateUI();
            if (protocolTabController != null) protocolTabController.updateUI();
            if (mavlinkTabController != null) mavlinkTabController.updateUI();
            if (inspectorTabController != null) inspectorTabController.updateUI();
            if (statusTabController != null) statusTabController.updateUI();
            if (paramTabController != null) paramTabController.updateUI();
            if (visualStatusTabController != null) visualStatusTabController.updateUI();
            
            // 更新窗口标题
            com.serialcomm.App.updateWindowTitle();
        });
    }
    
    /**
     * 语言切换监听器实现
     * 当语言发生切换时，更新界面文本
     */
    @Override
    public void onLanguageChanged(Locale oldLocale, Locale newLocale) {
        MDC.put("sid", safeCurrentSessionId());
        try {
            logger.info("MainController收到语言切换通知: {} -> {}",
                oldLocale != null ? oldLocale.getDisplayName() : "null",
                newLocale.getDisplayName());
        } finally { MDC.remove("sid"); }
        updateUI();
            try { com.serialcomm.service.StatusBarService.getInstance().refresh(); } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("ui.main.status.refresh", t); }
    }
    
    private void updateStatusBar(String message) {
        try { com.serialcomm.service.StatusBarService.getInstance().push(message); } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.status.push", e); }
    }
    
    private void handleTimerPeriodChange(String periodStr) {
        try {
            // 解析定时器周期值
            int period = Integer.parseInt(periodStr.replace("ms", ""));
            // 更新集中路由器的定时器周期（统一由 TransportViewModel 代理）
            com.serialcomm.service.TransportViewModel.getInstance().setTimerPeriod(period);

            updateStatusBar(languageManager.getString("status.timer.period.changed", period));
            MDC.put("sid", safeCurrentSessionId());
            try { logger.info("Timer period changed to {} ms", period); }
            finally { MDC.remove("sid"); }
        } catch (NumberFormatException e) {
            MDC.put("sid", safeCurrentSessionId());
            try { logger.error("Invalid timer period format: {}", periodStr); }
            finally { MDC.remove("sid"); }
        }
    }
    
    private void updateByteCountDisplay() {
        if (byteCountLabel != null && languageManager != null) {
            UiUpdateQueue.get().submit("main.byteCount", () -> {
                String format = languageManager.getString("statusbar.bytes.format");
                String text = String.format(format.replace("{}", "%d"), totalBytesReceived.get(), totalBytesSent.get());
                byteCountLabel.setText(text);
            });
        }
    }

    private void updateBandwidthDisplay() {
        if (languageManager == null) return;
        UiUpdateQueue.get().submit("main.byteBandwidth", () -> {
            String rxf = languageManager.getString("statusbar.bandwidth.rx.format");
            String txf = languageManager.getString("statusbar.bandwidth.tx.format");
            String rxStr = humanReadableBitsPerSecond(rxBitPerSec.get());
            String txStr = humanReadableBitsPerSecond(txBitPerSec.get());
            if (rxBandwidthLabel != null) {
                rxBandwidthLabel.setText(String.format(rxf, rxStr));
            }
            if (txBandwidthLabel != null) {
                txBandwidthLabel.setText(String.format(txf, txStr));
            }
        });
    }

    private static String humanReadableBitsPerSecond(long bitsPerSecond) {
        if (bitsPerSecond < 1000) {
            return bitsPerSecond + " bit/s";
        }
        double kbps = bitsPerSecond / 1000.0;
        if (kbps < 1000) {
            return String.format(java.util.Locale.ROOT, "%.1f Kbit/s", kbps);
        }
        double mbps = kbps / 1000.0;
        if (mbps < 1000) {
            return String.format(java.util.Locale.ROOT, "%.2f Mbit/s", mbps);
        }
        double gbps = mbps / 1000.0;
        return String.format(java.util.Locale.ROOT, "%.2f Gbit/s", gbps);
    }
    
    public void addReceivedBytes(int bytes) {
        totalBytesReceived.addAndGet(bytes);
        updateByteCountDisplay();
    }
    
    public void addSentBytes(int bytes) {
        totalBytesSent.addAndGet(bytes);
        updateByteCountDisplay();
    }
    
    private void clearByteCount(ActionEvent event) {
        totalBytesReceived.set(0);
        totalBytesSent.set(0);
        lastBytesReceived = 0;
        lastBytesSent = 0;
        updateByteCountDisplay();
        updateBandwidthDisplay();
        updateStatusBar(languageManager.getString("status.bytes.cleared"));
    }
    
    public void setDebugTabController(DebugTabController controller) {
        this.debugTabController = controller;
        if (debugTabController != null) {
            debugTabController.setSharedResources(portComboBox, baudRateComboBox, connectButton, refreshButton);
            debugTabController.setByteCountCallback(this::addReceivedBytes, this::addSentBytes);
            debugTabController.setStatusCallback(this);
        }
    }
    
    public void setProtocolTabController(ProtocolTabController controller) {
        this.protocolTabController = controller;
        if (protocolTabController != null) {
            protocolTabController.setByteCountCallback(this::addReceivedBytes, this::addSentBytes);
            protocolTabController.setStatusCallback(this);
        }
    }
    
    public void setMavlinkTabController(MavlinkTabController controller) {
        this.mavlinkTabController = controller;
        if (mavlinkTabController != null) {
            mavlinkTabController.setByteCountCallback(this::addReceivedBytes, this::addSentBytes);
            mavlinkTabController.setStatusCallback(this);
        }
    }

    public void setInspectorTabController(InspectorTabController controller) {
        this.inspectorTabController = controller;
        if (inspectorTabController != null) {
            inspectorTabController.setByteCountCallback(this::addReceivedBytes, this::addSentBytes);
            inspectorTabController.setStatusCallback(this);
        }
    }

    public void setStatusTabController(StatusTabController controller) {
        this.statusTabController = controller;
        if (statusTabController != null) {
            statusTabController.setByteCountCallback(this::addReceivedBytes, this::addSentBytes);
            statusTabController.setStatusCallback(this);
        }
    }

    // Reflection-called from App.setupTabControllers
    public void setParamTabController(ParamTabController controller) {
        this.paramTabController = controller;
        if (paramTabController != null) {
            paramTabController.setByteCountCallback(this::addReceivedBytes, this::addSentBytes);
            paramTabController.setStatusCallback(this);
        }
    }
    
    public void initializeDefaultTab() {
        // 设置初始活动标签页
        Tab selectedTab = mainTabPane.getSelectionModel().getSelectedItem();
        if (selectedTab != null) {
            String tabId = selectedTab.getId();
            if ("tabDebug".equals(tabId) && debugTabController != null) {
                debugTabController.setActive(true);
                        MDC.put("sid", safeCurrentSessionId());
                        try { logger.info(languageManager.getString("log.ui.debug.active")); }
                        finally { MDC.remove("sid"); }
            } else if ("tabProtocol".equals(tabId) && protocolTabController != null) {
                protocolTabController.setActive(true);
                        MDC.put("sid", safeCurrentSessionId());
                        try { logger.info(languageManager.getString("log.ui.protocol.active")); }
                        finally { MDC.remove("sid"); }
            } else if ("tabMavlink".equals(tabId) && mavlinkTabController != null) {
                mavlinkTabController.setActive(true);
                        MDC.put("sid", safeCurrentSessionId());
                        try { logger.info("MAVLink tab set active on init"); }
                        finally { MDC.remove("sid"); }
            }
            // 启动后将活动控制器注册到当前传输层
            BaseController owner = getActiveController();
            if (owner != null) com.serialcomm.service.TransportViewModel.getInstance().setActiveController(owner);
        }
    }
    
    private void handleTabChange(Tab oldTab, Tab newTab) {
        try {
            MDC.put("sid", safeCurrentSessionId());
            try { logger.info(languageManager.getString("log.ui.tab.switch"), 
                oldTab != null ? oldTab.getText() : languageManager.getString("statusbar.port.unknown"), 
                newTab != null ? newTab.getText() : languageManager.getString("statusbar.port.unknown")); }
            finally { MDC.remove("sid"); }
            
            updateStatusBar(languageManager.getString("status.tab.switched", newTab != null ? newTab.getText() : languageManager.getString("statusbar.port.unknown")));
            
            if (debugTabController != null) debugTabController.setActive(false);
            if (protocolTabController != null) protocolTabController.setActive(false);
            if (mavlinkTabController != null) mavlinkTabController.setActive(false);
            if (inspectorTabController != null) inspectorTabController.setActive(false);
            if (statusTabController != null) statusTabController.setActive(false);
            
            if (newTab != null) {
                String tabId = newTab.getId();
                if ("tabDebug".equals(tabId) && debugTabController != null) {
                    debugTabController.setActive(true);
                } else if ("tabProtocol".equals(tabId) && protocolTabController != null) {
                    protocolTabController.setActive(true);
                } else if ("tabMavlink".equals(tabId) && mavlinkTabController != null) {
                    mavlinkTabController.setActive(true);
                } else if ("tabInspector".equals(tabId) && inspectorTabController != null) {
                    inspectorTabController.setActive(true);
                } else if ("tabStatus".equals(tabId) && statusTabController != null) {
                    statusTabController.setActive(true);
                } else if ("tabVisual".equals(tabId) && visualStatusTabController != null) {
                    visualStatusTabController.setActive(true);
                } else if ("tabParams".equals(tabId) && paramTabController != null) {
                    paramTabController.setActive(true);
                }
            }
            // 切换后更新连接按钮文本
            updateConnectButtonText();
            // 确保当前传输层的活动接收端与当前标签同步
            BaseController owner = getActiveController();
            if (owner != null) {
                com.serialcomm.service.TransportViewModel.getInstance().setActiveController(owner);
            }
        } catch (Exception e) {
            MDC.put("sid", safeCurrentSessionId());
            try { logger.error(languageManager.getString("log.ui.tab.switch.failed"), e); }
            finally { MDC.remove("sid"); }
            updateStatusBar(languageManager.getString("error.tab.switch.failed", e.getMessage()));
        }
    }
    
    private void refreshPorts(ActionEvent event) {
        try {
            MDC.put("sid", safeCurrentSessionId());
            try { logger.info(languageManager.getString("log.ui.refresh.start")); }
            finally { MDC.remove("sid"); }
            updateStatusBar(languageManager.getString("status.refresh.started"));
            
            SerialPort[] ports = SerialPort.getCommPorts();
            
            portComboBox.getItems().clear();
            
            for (SerialPort port : ports) {
                portComboBox.getItems().add(port.getSystemPortName());
            }
            
            if (ports.length > 0) {
                portComboBox.setValue(ports[0].getSystemPortName());
                MDC.put("sid", safeCurrentSessionId());
                try { logger.info(languageManager.getString("log.ui.refresh.success"), 
                           ports.length, ports[0].getSystemPortName());
                updateStatusBar(languageManager.getString("status.refresh.completed", ports.length)); }
                finally { MDC.remove("sid"); }
            } else {
                MDC.put("sid", safeCurrentSessionId());
                try { logger.info(languageManager.getString("log.ui.refresh.no.ports")); }
                finally { MDC.remove("sid"); }
                updateStatusBar(languageManager.getString("status.refresh.no.ports"));
            }
        } catch (Exception e) {
            MDC.put("sid", safeCurrentSessionId());
            try { logger.error(languageManager.getString("log.ui.refresh.failed"), e); }
            finally { MDC.remove("sid"); }
            updateStatusBar(languageManager.getString("error.refresh.failed", e.getMessage()));
            if (debugTabController != null) {
                debugTabController.handleException(languageManager.getString("error.refresh.failed", e.getMessage()), e);
            } else {
                showAlert(Alert.AlertType.ERROR, languageManager.getString("dialog.title.error"), 
                    languageManager.getString("error.refresh.failed", e.getMessage()));
            }
        }
    }
    
    private void toggleConnection(ActionEvent event) {
        try {
            // Network path with proper toggle behavior
            if (!isSerialSelected()) {
                if (com.serialcomm.service.TransportViewModel.getInstance().isConnected()) {
                    com.serialcomm.service.TransportViewModel.getInstance().disconnect();
                    updateStatusBar(languageManager.getString("status.network.disconnected"));
                    updateConnectButtonText();
                    cancelAutoDevicePick();
                    return;
                }
                boolean isTcp = transportComboBox != null && transportComboBox.getValue() == com.serialcomm.link.LinkManager.Transport.TCP;
                if (isTcp) {
                    String def = languageManager.getString("ui.dialog.tcp.input.default");
                    javafx.scene.control.TextInputDialog hostDlg = new javafx.scene.control.TextInputDialog(def);
                    hostDlg.setHeaderText(languageManager.getString("ui.main.transport.tcp"));
                    hostDlg.setContentText(languageManager.getString("ui.dialog.tcp.input.content"));
                    java.util.Optional<String> res = hostDlg.showAndWait();
                    if (res.isPresent()) {
                        String val = res.get();
                        try {
                            String[] hp = val.split(":");
                            if (hp.length != 2) throw new IllegalArgumentException(languageManager.getString("error.tcp.input.format"));
                            String host = hp[0]; int port = Integer.parseInt(hp[1]);
                            boolean ok = com.serialcomm.service.TransportViewModel.getInstance().connectTcp(host, port);
                            if (!ok) { showAlert(Alert.AlertType.ERROR, languageManager.getString("dialog.title.error"), languageManager.getString("error.connection.failed", "TCP")); return; }
                            updateStatusBar(String.format(languageManager.getString("status.network.tcp.connected"), host, port));
                            BaseController owner = getActiveController();
                            if (owner != null) com.serialcomm.service.TransportViewModel.getInstance().setActiveController(owner);
                            updateConnectButtonText();
                            startAutoDevicePickOnce();
                            return;
                        } catch (Exception e) {
                            String msg = e.getMessage() != null ? e.getMessage() : languageManager.getString("error.tcp.input.format");
                            showAlert(Alert.AlertType.ERROR, languageManager.getString("dialog.title.error"), msg);
                            return;
                        }
                    } else return;
                } else {
                    // UDP listen
                    String defPort = languageManager.getString("ui.dialog.udp.input.default");
                    javafx.scene.control.TextInputDialog portDlg = new javafx.scene.control.TextInputDialog(defPort);
                    portDlg.setHeaderText(languageManager.getString("ui.main.transport.udp"));
                    portDlg.setContentText(languageManager.getString("ui.dialog.udp.input.content"));
                    java.util.Optional<String> res = portDlg.showAndWait();
                    if (res.isPresent()) {
                        try {
                            int port = Integer.parseInt(res.get().trim());
                            boolean ok = com.serialcomm.service.TransportViewModel.getInstance().listenUdp(port);
                            if (!ok) { showAlert(Alert.AlertType.ERROR, languageManager.getString("dialog.title.error"), languageManager.getString("error.connection.failed", "UDP")); return; }
                            updateStatusBar(String.format(languageManager.getString("status.network.udp.listening"), port));
                            BaseController owner = getActiveController();
                            if (owner != null) com.serialcomm.service.TransportViewModel.getInstance().setActiveController(owner);
                            updateConnectButtonText();
                            startAutoDevicePickOnce();
                            return;
                        } catch (Exception e) {
                            String msg = languageManager.getString("error.udp.input.format");
                            showAlert(Alert.AlertType.ERROR, languageManager.getString("dialog.title.error"), msg);
                            return;
                        }
                    } else return;
                }
            }

            if (portComboBox.getValue() == null || portComboBox.getValue().isEmpty()) {
                showAlert(Alert.AlertType.WARNING, languageManager.getString("dialog.title.warning"), 
                    languageManager.getString("error.port.not.selected"));
                return;
            }

            String baudRateStr = baudRateComboBox.getValue();
            if (baudRateStr == null || baudRateStr.isEmpty()) {
                showAlert(Alert.AlertType.WARNING, languageManager.getString("dialog.title.warning"), 
                    languageManager.getString("error.baudrate.not.selected"));
                return;
            }
            int baud = Integer.parseInt(baudRateStr);

            String port = portComboBox.getValue();
            if (com.serialcomm.service.TransportViewModel.getInstance().isConnected()) {
                if (transportComboBox != null && transportComboBox.getValue() != null
                    && !transportComboBox.getValue().equals(languageManager.getString("ui.main.transport.serial"))) {
                    com.serialcomm.service.TransportViewModel.getInstance().disconnect();
                } else {
                    com.serialcomm.service.TransportViewModel.getInstance().disconnect();
                }
                updateStatusBar(languageManager.getString("status.port.disconnected", port));
                // 广播到各Tab以更新控件状态
                if (debugTabController != null) debugTabController.updateUI();
                if (protocolTabController != null) protocolTabController.updateUI();
                if (mavlinkTabController != null) mavlinkTabController.updateUI();
                cancelAutoDevicePick();
            } else {
                if (transportComboBox != null && transportComboBox.getValue() != com.serialcomm.link.LinkManager.Transport.SERIAL) {
                    // already connected in dialog step (network)
                } else {
                    // Route serial connect via LinkManager to set current transport correctly
                    com.serialcomm.service.TransportViewModel.getInstance().connectSerial(port, baud);
                }
                updateStatusBar(languageManager.getString("status.port.connected", port));
                // 设置当前活动控制器
                BaseController owner = getActiveController();
                if (owner != null) {
                    com.serialcomm.service.TransportViewModel.getInstance().setActiveController(owner);
                }
                // 广播到各Tab以更新控件状态
                if (debugTabController != null) debugTabController.updateUI();
                if (protocolTabController != null) protocolTabController.updateUI();
                if (mavlinkTabController != null) mavlinkTabController.updateUI();
                startAutoDevicePickOnce();
            }

            updateConnectButtonText();
        } catch (Exception e) {
            MDC.put("sid", safeCurrentSessionId());
            try { logger.error(languageManager.getString("error.connection.failed", e.getMessage()), e); }
            finally { MDC.remove("sid"); }
            updateStatusBar(languageManager.getString("error.connection.failed", e.getMessage()));
            showAlert(Alert.AlertType.ERROR, languageManager.getString("dialog.title.error"), 
                languageManager.getString("error.connection.failed", e.getMessage()));
        }
    }

    /**
     * Start a one-time scheduler to auto-select the single observed device (SYS:COMP)
     * and auto-fill Protocol tab targets when exactly one device is present.
     * Runs every 1s, cancels itself after taking action or when multiple devices are observed.
     */
    private void startAutoDevicePickOnce() {
        try {
            cancelAutoDevicePick();
            java.util.concurrent.ScheduledExecutorService exec = com.serialcomm.service.Scheduler.getInstance().ensureMonitoring();
            autoDevicePickTask = exec.schedule(() -> {
                try {
                    long offlineThresholdMs = 3000L;
                    java.util.List<String> devices = com.serialcomm.service.DeviceSelectionService.getInstance().enumerateOnlineDevices(offlineThresholdMs);
                    if (devices == null || devices.isEmpty()) return; // one-shot: nothing to do
                    if (devices.size() > 2) { // ALL + 2+ ONLINE devices => do nothing
                        return;
                    }
                    if (devices.size() == 2) { // ALL + one device
                        String dev = devices.get(1); // format SYS:COMP
                        String[] parts = dev.split(":");
                        if (parts.length == 2) {
                            int sys = Integer.parseInt(parts[0]);
                            int comp = Integer.parseInt(parts[1]);
                            // Select in global device ComboBox
                            if (deviceComboBox != null) {
                                Platform.runLater(() -> deviceComboBox.getSelectionModel().select(dev));
                            }
                            // Auto-fill Protocol tab target fields
                            if (protocolTabController != null) {
                                protocolTabController.setTargetIds(sys, comp);
                            }
                        }
                        return;
                    }
                    // devices.size()==1 (only ALL) -> nothing to do in one-shot
                } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.autopick.fill", e); }
            }, 3000, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.autopick.schedule", e); }
    }

    private void cancelAutoDevicePick() {
        try { if (autoDevicePickTask != null) { autoDevicePickTask.cancel(true); autoDevicePickTask = null; } } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.autopick.cancel", e); }
    }

    private BaseController getActiveController() {
        Tab current = mainTabPane.getSelectionModel().getSelectedItem();
        if (current == null) return null;
        String tabId = current.getId();
        if ("tabDebug".equals(tabId)) return debugTabController;
        if ("tabProtocol".equals(tabId)) return protocolTabController;
        if ("tabMavlink".equals(tabId)) return mavlinkTabController;
        if ("tabInspector".equals(tabId)) return inspectorTabController;
        if ("tabStatus".equals(tabId)) return statusTabController;
        if ("tabVisual".equals(tabId)) return visualStatusTabController;
        if ("tabParams".equals(tabId)) return paramTabController;
        return null;
    }

    public void setVisualStatusTabController(VisualStatusTabController controller) {
        this.visualStatusTabController = controller;
        if (visualStatusTabController != null) {
            visualStatusTabController.setByteCountCallback(this::addReceivedBytes, this::addSentBytes);
            visualStatusTabController.setStatusCallback(this);
        }
    }
    
    private void showAlert(Alert.AlertType type, String title, String message) {
        try {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        } catch (Exception e) {
            MDC.put("sid", safeCurrentSessionId());
            try { logger.error(languageManager.getString("statusbar.dialog.show.failed"), e); }
            finally { MDC.remove("sid"); }
        }
    }
    
    public void cleanup() {
        try {
            MDC.put("sid", safeCurrentSessionId());
            try { logger.info(languageManager.getString("log.cleanup.main.start")); }
            finally { MDC.remove("sid"); }
            updateStatusBar(languageManager.getString("log.cleanup.main.start"));
            
            com.serialcomm.service.TransportViewModel.getInstance().disconnect();

            if (uiMonitorTask != null) { try { uiMonitorTask.cancel(true); } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.uimonitor.cancel", e); } uiMonitorTask = null; }
            
            // 移除语言切换监听器
            if (languageManager != null) {
                languageManager.removeLanguageChangeListener(this);
            }
            try { if (visualStatusTabController != null) visualStatusTabController.cleanup(); } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.cleanup.visual", e); }
            
            MDC.put("sid", safeCurrentSessionId());
            try { logger.info(languageManager.getString("log.cleanup.main.complete")); }
            finally { MDC.remove("sid"); }
            updateStatusBar(languageManager.getString("log.cleanup.main.complete"));
        } catch (Exception e) {
            MDC.put("sid", safeCurrentSessionId());
            try { logger.error(languageManager.getString("log.cleanup.main.error"), e); }
            finally { MDC.remove("sid"); }
            updateStatusBar(languageManager.getString("log.cleanup.main.error", e.getMessage()));
        }
    }

    private String safeCurrentSessionId() {
        try {
            com.serialcomm.link.LinkSession s = com.serialcomm.link.LinkManager.getInstance().currentSession();
            return (s != null && s.getSessionId() != null && !s.getSessionId().isEmpty()) ? s.getSessionId() : "ui";
        } catch (Throwable t) {
            return "ui";
        }
    }
    
    @Override
    public void updateStatus(String message) {
        updateStatusBar(message);
    }

    private void startUiMonitor() {
        if (uiMonitorTask != null && !uiMonitorTask.isCancelled()) return;
        java.util.concurrent.ScheduledExecutorService exec = com.serialcomm.service.Scheduler.getInstance().ensureMonitoring();
        uiMonitorTask = exec.scheduleAtFixedRate(() -> {
            try {
                boolean connected = com.serialcomm.service.TransportViewModel.getInstance().isConnected();
                String port = portComboBox != null && portComboBox.getValue() != null ? portComboBox.getValue() : languageManager.getString("statusbar.port.unknown");
                String tab = mainTabPane != null && mainTabPane.getSelectionModel().getSelectedItem() != null ? mainTabPane.getSelectionModel().getSelectedItem().getText() : "";
                // 仅在需要时做轻量检查
                if (connected) {
                    try {
                        if (mavlinkTabController != null) mavlinkTabController.trimBuffers();
                        if (protocolTabController != null) protocolTabController.trimBuffers();
                    } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.statusbar.update", e); }
                }
                // 计算最近1秒带宽（bit/s）
                long currentRx = totalBytesReceived.get();
                long currentTx = totalBytesSent.get();
                long rxDeltaBytes = Math.max(0L, currentRx - lastBytesReceived);
                long txDeltaBytes = Math.max(0L, currentTx - lastBytesSent);
                rxBitPerSec.set(rxDeltaBytes * 8L);
                txBitPerSec.set(txDeltaBytes * 8L);
                lastBytesReceived = currentRx;
                lastBytesSent = currentTx;
                updateBandwidthDisplay();
                updateOnlineIndicator();
                refreshDeviceComboItems();
            } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.main.monitor.tick", e); }
        }, 1000, 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void updateOnlineIndicator() {
        try {
            if (onlineValueLabel == null || languageManager == null) return;
            java.util.List<VehicleState.Snapshot> snaps = VehicleState.getInstance().getSnapshots();
            long now = System.currentTimeMillis();
            long offlineThresholdMs = 3000L;

            StringBuilder sb = new StringBuilder();
            for (VehicleState.Snapshot s : snaps) {
                long hb = s.heartbeatTimeMs;
                long ageSec = hb <= 0 ? -1L : Math.max(0L, (now - hb) / 1000L);
                boolean isOnline = hb > 0 && (now - hb) <= offlineThresholdMs;
                String vendor = vendorName(s.autopilot);
                String key = isOnline ? "ui.main.online.value.vendor" : "ui.main.offline.value.vendor";
                String line = String.format(languageManager.getString(key),
                    vendor,
                    Math.max(0L, ageSec),
                    (s.systemId > 0 ? (s.systemId + ":" + s.componentId) : "-"),
                    (s.mavlink2 ? "v2" : "v1"));
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            if (sb.length() == 0) {
                // no devices yet, show generic offline line with unknown vendor
                String line = String.format(languageManager.getString("ui.main.offline.value.vendor"), "-", 0, "-", "v2");
                sb.append(line);
            }
            String text = sb.toString();
            UiUpdateQueue.get().submit("main.onlineIndicator", () -> onlineValueLabel.setText(text));
        } catch (Exception ignore) {}
    }

    private String vendorName(int autopilot) {
        try {
            // Prefer EnumLabeler friendly label if available
            String label = com.serialcomm.service.EnumLabeler.label("msg_heartbeat", "autopilot", Integer.toString(autopilot));
            if (label != null && !label.isEmpty()) {
                if (label.toUpperCase().contains("PX4")) return "PX4";
                if (label.toUpperCase().contains("ARDUPILOT")) return "ArduPilot";
                return label;
            }
        } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("ui.main.cleanup", t); }
        // Fallback to known constants
        int APM = com.MAVLink.enums.MAV_AUTOPILOT.MAV_AUTOPILOT_ARDUPILOTMEGA;
        int PX4 = com.MAVLink.enums.MAV_AUTOPILOT.MAV_AUTOPILOT_PX4;
        if (autopilot == PX4) return "PX4";
        if (autopilot == APM) return "ArduPilot";
        return "-";
    }
}