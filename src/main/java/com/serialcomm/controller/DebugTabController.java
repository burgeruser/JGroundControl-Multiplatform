package com.serialcomm.controller;

import com.fazecast.jSerialComm.SerialPort;
import com.serialcomm.serial.SerialRouter;
import com.serialcomm.util.SerialExceptionHandler;
import javafx.application.Platform;
import com.serialcomm.util.UiUpdateQueue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.function.IntConsumer;

public class DebugTabController extends BaseController {
    // Logger for the Debug tab controller
    private static final Logger logger = LoggerFactory.getLogger(DebugTabController.class);
    
    @FXML
    private TextArea receiveTextArea;
    @FXML
    private TextArea sendTextArea;
    @FXML
    private Button sendButton;
    @FXML
    private RadioButton asciiRadioButton;
    @FXML
    private RadioButton hexRadioButton;
    @FXML
    private Button clearReceiveButton;
    @FXML
    private Label receiveModeLabel;
    @FXML
    private Label receiveAreaLabel;
    @FXML
    private Label sendAreaLabel;
    @FXML
    private Label sendHintLabel;
    
    private ComboBox<String> sharedPortComboBox;
    private ComboBox<String> sharedBaudRateComboBox;
    private Button sharedConnectButton;
    private Button sharedRefreshButton;
    
    public void setSharedResources(ComboBox<String> portComboBox, 
                                   ComboBox<String> baudRateComboBox,
                                   Button connectButton,
                                   Button refreshButton) {
        this.sharedPortComboBox = portComboBox;
        this.sharedBaudRateComboBox = baudRateComboBox;
        this.sharedConnectButton = connectButton;
        this.sharedRefreshButton = refreshButton;
        
        if (sharedRefreshButton != null) {
            sharedRefreshButton.setOnAction(event -> {
                refreshPorts();
                if (statusCallback != null) {
                    statusCallback.updateStatus(languageManager.getString("status.port.refreshed"));
                }
            });
        }
    }
    
    @FXML
    public void initialize() {
        try {
            super.initialize();
            
            exceptionHandler = new SerialExceptionHandler(receiveTextArea);
            
            ToggleGroup receiveModeGroup = new ToggleGroup();
            asciiRadioButton.setToggleGroup(receiveModeGroup);
            hexRadioButton.setToggleGroup(receiveModeGroup);
            // Default to HEX to reduce string rendering and layout overhead
            hexRadioButton.setSelected(true);
            currentReceiveMode = ReceiveMode.HEX;
            
            asciiRadioButton.setOnAction(event -> {
                currentReceiveMode = ReceiveMode.ASCII;
                logger.info(languageManager.getString("log.mode.ascii"));
                if (statusCallback != null) {
                    statusCallback.updateStatus(languageManager.getString("status.mode.ascii"));
                }
                // On mode switch, clear UI and any pending background buffer to avoid
                // waiting for old backlog to flush to the UI
                try {
                    if (uiAppender != null) uiAppender.clearBuffer();
                    receiveTextArea.clear();
                    lastTrimMs = 0L; lastScrollMs = 0L;
                } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.debug.clear", e); }
            });
            hexRadioButton.setOnAction(event -> {
                currentReceiveMode = ReceiveMode.HEX;
                logger.info(languageManager.getString("log.mode.hex"));
                if (statusCallback != null) {
                    statusCallback.updateStatus(languageManager.getString("status.mode.hex"));
                }
                try {
                    if (uiAppender != null) uiAppender.clearBuffer();
                    receiveTextArea.clear();
                    lastTrimMs = 0L; lastScrollMs = 0L;
                } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.debug.clear", e); }
            });
            
            sendButton.setOnAction(this::sendData);
            clearReceiveButton.setOnAction(event -> {
                clearReceiveArea();
                if (statusCallback != null) {
                    statusCallback.updateStatus(languageManager.getString("status.receive.cleared"));
                }
            });
            
            sendTextArea.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.ENTER && event.isControlDown()) {
                    if (serialPort != null && serialPort.isOpen()) {
                        String dataToSend = sendTextArea.getText();
                        if (dataToSend != null && !dataToSend.trim().isEmpty()) {
                            super.sendData(dataToSend);
                            if (statusCallback != null) {
                                statusCallback.updateStatus(languageManager.getString("status.data.sent"));
                            }
                        }
                    } else {
                        if (statusCallback != null) {
                            statusCallback.updateStatus(languageManager.getString("error.serial.not.connected"));
                        }
                    }
                    event.consume();
                }
            });
            
            sendButton.setDisable(true);
            
            logger.info(languageManager.getString("log.controller.debug.init"));
        } catch (Exception e) {
            logger.error(languageManager.getString("log.controller.debug.init.failed"), e);
            if (exceptionHandler != null) {
                exceptionHandler.handleUIException("DebugTabController", e);
            } else {
                showAlert(Alert.AlertType.ERROR, 
                    languageManager.getString("dialog.title.error"), 
                    languageManager.getString("error.controller.init.failed", e.getMessage()));
            }
        }
    }
    
    public void connectPort() {
        try {
            if (sharedPortComboBox.getValue() == null || sharedPortComboBox.getValue().isEmpty()) {
                showAlert(Alert.AlertType.WARNING, 
                    languageManager.getString("dialog.title.warning"), 
                    languageManager.getString("error.port.not.selected"));
                if (statusCallback != null) {
                    statusCallback.updateStatus(languageManager.getString("error.port.not.selected"));
                }
                return;
            }
            
            String baudRateStr = sharedBaudRateComboBox.getValue();
            if (baudRateStr == null || baudRateStr.isEmpty()) {
                showAlert(Alert.AlertType.WARNING, 
                    languageManager.getString("dialog.title.warning"), 
                    languageManager.getString("error.baudrate.not.selected"));
                if (statusCallback != null) {
                    statusCallback.updateStatus(languageManager.getString("error.baudrate.not.selected"));
                }
                return;
            }
            Integer baudRate;
            try {
                baudRate = Integer.parseInt(baudRateStr);
            } catch (NumberFormatException e) {
                showAlert(Alert.AlertType.ERROR, 
                    languageManager.getString("dialog.title.error"), 
                    languageManager.getString("error.baudrate.invalid"));
                if (statusCallback != null) {
                    statusCallback.updateStatus(languageManager.getString("error.baudrate.invalid"));
                }
                return;
            }
            
            // Connect via TransportViewModel to keep transport state consistent across tabs
            com.serialcomm.service.TransportViewModel.getInstance().connectSerial(sharedPortComboBox.getValue(), baudRate);
            com.serialcomm.service.TransportViewModel.getInstance().setActiveController(this);
            updateUIConnected(true);
            appendToReceiveArea(languageManager.getString("status.port.connected", sharedPortComboBox.getValue()) + "\n");
            if (statusCallback != null) {
                statusCallback.updateStatus(languageManager.getString("status.port.connected", sharedPortComboBox.getValue()));
            }
            
        } catch (Exception e) {
            exceptionHandler.handlePortConnectionException(sharedPortComboBox.getValue(), e);
            if (statusCallback != null) {
                statusCallback.updateStatus(languageManager.getString("error.connection.failed", e.getMessage()));
            }
        }
    }
    
    public void disconnectPort() {
        try {
            String portName = (serialPort != null) ? serialPort.getSystemPortName() : languageManager.getString("statusbar.port.unknown");
            com.serialcomm.service.TransportViewModel.getInstance().disconnect();
            updateUIConnected(false);
            appendToReceiveArea(languageManager.getString("status.port.disconnected", portName) + "\n");
            if (statusCallback != null) {
                statusCallback.updateStatus(languageManager.getString("status.port.disconnected", portName));
            }
            
        } catch (Exception e) {
            exceptionHandler.handleException(languageManager.getString("error.disconnection.failed", e.getMessage()), e);
            if (statusCallback != null) {
                statusCallback.updateStatus(languageManager.getString("error.disconnection.failed", e.getMessage()));
            }
        }
    }
    
    private void sendData(ActionEvent event) {
        try {
            String dataToSend = sendTextArea.getText();
            super.sendData(dataToSend);
            if (statusCallback != null) {
                statusCallback.updateStatus(languageManager.getString("status.data.sent.bytes", dataToSend.length()));
            }
        } catch (Exception e) {
            exceptionHandler.handleDataSendException(e);
            if (statusCallback != null) {
                statusCallback.updateStatus(languageManager.getString("error.send.failed", e.getMessage()));
            }
        }
    }
    
    private com.serialcomm.service.UiAppender uiAppender;
    @Override
    protected void onDataReceived(String data) {
        if (uiAppender == null) {
            // In ASCII mode, cap the maximum characters per flush to avoid pushing
            // very long text to the UI thread at once
            uiAppender = new com.serialcomm.service.UiAppender(this::appendToReceiveArea)
                    .setFlushIntervalMs(100)
                    .setMaxFlushChars(2048)
                    .setBufferCapChars(20000);
            uiAppender.start();
        }
        uiAppender.append(data);
    }
    
    private long lastTrimMs = 0L;
    private long lastScrollMs = 0L;
    private void appendToReceiveArea(String text) {
        try {
            if (text == null || text.isEmpty()) {
                return;
            }
            receiveTextArea.appendText(text);
            long now = System.currentTimeMillis();
            // Throttled auto-scroll: avoid moving the caret on every append to reduce layout work
            if (now - lastScrollMs >= 250) {
                lastScrollMs = now;
                receiveTextArea.positionCaret(receiveTextArea.getText().length());
            }
            // Throttled trimming: separate thresholds for ASCII/HEX to prevent unbounded
            // text growth from causing jank
            if (now - lastTrimMs >= 500) {
                lastTrimMs = now;
                int len = receiveTextArea.getText().length();
                int threshold = (currentReceiveMode == ReceiveMode.ASCII) ? 30000 : 120000; // 30KB/120KB
                int keep = (currentReceiveMode == ReceiveMode.ASCII) ? 15000 : 60000;      // keep half
                if (len > threshold) {
                    int remove = Math.max(0, len - keep);
                    // Use deleteText to remove from the beginning to avoid setText memory spikes
                    receiveTextArea.deleteText(0, remove);
                    receiveTextArea.positionCaret(receiveTextArea.getText().length());
                }
            }
        } catch (Exception e) {
            logger.error(languageManager.getString("log.ui.text.update.failed"), e);
            exceptionHandler.handleUIException("receiveTextArea", e);
        }
    }
    
    private void clearReceiveArea() {
        try {
            receiveTextArea.clear();
            logger.info(languageManager.getString("log.ui.receive.cleared"));
        } catch (Exception e) {
            logger.error(languageManager.getString("log.ui.receive.clear.failed"), e);
            exceptionHandler.handleUIException("receiveTextArea", e);
        }
    }
    
    private void refreshPorts() {
        try {
            logger.info(languageManager.getString("log.ui.refresh.start"));
            
            if (sharedPortComboBox == null) {
                logger.warn(languageManager.getString("statusbar.refresh.no.combobox"));
                return;
            }
            
            SerialPort[] ports = SerialPort.getCommPorts();
            
            sharedPortComboBox.getItems().clear();
            
            for (SerialPort port : ports) {
                sharedPortComboBox.getItems().add(port.getSystemPortName());
            }
            
            if (ports.length > 0) {
                sharedPortComboBox.setValue(ports[0].getSystemPortName());
                logger.info(languageManager.getString("log.ui.refresh.success"), 
                           ports.length, ports[0].getSystemPortName());
            } else {
                logger.info(languageManager.getString("log.ui.refresh.no.ports"));
            }
        } catch (Exception e) {
            logger.error(languageManager.getString("log.ui.refresh.failed"), e);
            exceptionHandler.handleException(languageManager.getString("error.refresh.failed", e.getMessage()), e);
        }
    }
    
    private void updateUIConnected(boolean isConnected) {
        try {
            // Update the connect button text
            if (sharedConnectButton != null) {
                String buttonText = isConnected ? 
                    languageManager.getString("ui.main.disconnect") : 
                    languageManager.getString("ui.main.connect");
                sharedConnectButton.setText(buttonText);
            }
            
            // Update the send button enabled state
            if (sendButton != null) {
                sendButton.setDisable(!isConnected);
            }
            
            logger.debug("UI connection state updated: connected={}", isConnected);
        } catch (Exception e) {
            logger.error(languageManager.getString("log.ui.status.update.failed"), e);
            if (exceptionHandler != null) {
                exceptionHandler.handleUIException("UI组件", e);
            }
        }
    }
    
    /**
     * Update UI texts.
     * Refresh all labels, buttons, and prompts according to the current language.
     */
    public void updateUI() {
        if (languageManager == null) return;
        
        UiUpdateQueue.get().submit("debug.updateUI", () -> {
            // Update section labels
            if (receiveModeLabel != null) {
                receiveModeLabel.setText(languageManager.getString("ui.debug.receive.mode"));
            }
            if (receiveAreaLabel != null) {
                receiveAreaLabel.setText(languageManager.getString("ui.debug.receive.area"));
            }
            if (sendAreaLabel != null) {
                sendAreaLabel.setText(languageManager.getString("ui.debug.send.area"));
            }
            if (sendHintLabel != null) {
                sendHintLabel.setText(languageManager.getString("ui.debug.send.hint"));
            }
            
            // Update receive mode labels
            if (asciiRadioButton != null) {
                asciiRadioButton.setText("ASCII");
            }
            if (hexRadioButton != null) {
                hexRadioButton.setText("HEX");
            }
            
            // Update button texts and state
            if (clearReceiveButton != null) {
                clearReceiveButton.setText(languageManager.getString("ui.debug.clear.receive"));
            }
            if (sendButton != null) {
                sendButton.setText(languageManager.getString("ui.debug.send"));
                // Sync connection state -> control send button availability
                boolean connected = com.serialcomm.service.TransportViewModel.getInstance().isConnected();
                sendButton.setDisable(!connected);
            }
            
            logger.debug("DebugTabController updateUI completed");
        });
    }
    
    @Override
    public void cleanup() {
        try {
            logger.info(languageManager.getString("log.cleanup.debug.start"));
            super.cleanup();
            logger.info(languageManager.getString("log.cleanup.debug.complete"));
        } catch (Exception e) {
            logger.error(languageManager.getString("log.cleanup.debug.error"), e);
        }
    }
    
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
}