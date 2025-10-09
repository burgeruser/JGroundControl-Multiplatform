package com.serialcomm.controller;

import com.fazecast.jSerialComm.SerialPort;
import com.serialcomm.serial.SerialRouter;
import com.serialcomm.model.SerialPortConfig;
import com.serialcomm.util.LanguageManager;
import com.serialcomm.util.SerialExceptionHandler;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.IntConsumer;

/**
 * Base controller for serial/network tabs: unified send/receive, exception handling,
 * status callbacks, and active-controller demultiplexing.
 */
public abstract class BaseController {
    /** Logger for serial communication events */
    private static final Logger logger = LoggerFactory.getLogger(BaseController.class);
    
    /** SerialPort instance for physical serial I/O */
    protected SerialPort serialPort;
    
    /** Serial port configuration container */
    protected SerialPortConfig portConfig;
    
    /** Exception handler for transport/UI errors */
    protected SerialExceptionHandler exceptionHandler;
    
    /** Language manager for i18n strings */
    protected LanguageManager languageManager;
    
    /**
     * Deprecated receive timer; centralized routers now push via onBytesFromRouter.
     * Kept for legacy compatibility but not started by default.
     */
    @Deprecated
    protected Timer receiveTimer;
    
    /** Whether this controller is currently active for routed data delivery */
    protected boolean isActive = false;
    
    /** Timer period in milliseconds (default 100ms) */
    protected int timerPeriod = 100;
    
    /** Callback to count received bytes */
    protected IntConsumer bytesReceivedCallback;
    
    /** Callback to count sent bytes */
    protected IntConsumer bytesSentCallback;
    
    /** Status callback used by MainController to show updates */
    protected StatusCallback statusCallback;
    
    /** Status callback interface for UI updates. */
    public interface StatusCallback {
        /** Update the status message displayed in UI. */
        void updateStatus(String message);
    }
    
    /** Receive mode defining how data is rendered and sent. */
    public enum ReceiveMode {
        /** ASCII text mode */
        ASCII, 
        /** HEX mode for binary data */
        HEX
    }
    
    /** Current receive mode; default ASCII. */
    protected ReceiveMode currentReceiveMode = ReceiveMode.ASCII;
    
    /** Initialize language manager, default serial config, and log. */
    @FXML
    public void initialize() {
        // Initialize language manager
        languageManager = LanguageManager.getInstance();
        
        // Create default port config (9600 baud, 8N1, no parity)
        portConfig = new SerialPortConfig();
        
        // Log successful init
        logger.info(languageManager.getString("log.controller.base.init"));
    }
    
    /** Set callbacks for byte counters. */
    public void setByteCountCallback(IntConsumer receivedCallback, IntConsumer sentCallback) {
        // Register receive counter callback
        this.bytesReceivedCallback = receivedCallback;
        
        // Register send counter callback
        this.bytesSentCallback = sentCallback;
    }
    
    /** Set status update callback. */
    public void setStatusCallback(StatusCallback statusCallback) {
        // Assign callback instance
        this.statusCallback = statusCallback;
    }
    
    /** Set receive period (ms) and propagate to centralized router. */
    public void setTimerPeriod(int period) {
        if (period < 10 || period > 1000) {
            logger.warn("Timer period {} is out of recommended range (10-1000ms), using default 100ms", period);
            this.timerPeriod = 100;
        } else {
            this.timerPeriod = period;
            logger.info("Timer period set to {} ms", period);
            // Deprecated: do not start local polling; update centralized router period
            try { com.serialcomm.service.TransportViewModel.getInstance().setTimerPeriod(this.timerPeriod); } catch (Exception e) { com.serialcomm.util.ErrorMonitor.record("ui.base.timerPeriod", e); }
        }
    }
    
    /** Get current receive period (ms). */
    public int getTimerPeriod() {
        return timerPeriod;
    }
    
    /** Set whether this controller should receive routed data. */
    public void setActive(boolean active) {
        // Set active flag
        this.isActive = active;
        
        // Log change
        logger.info(languageManager.getString("log.controller.set.active"), active);

        // If active, register with TransportViewModel as active receiver
        if (active) {
            try {
                com.serialcomm.service.TransportViewModel.getInstance().setActiveController(this);
            } catch (Exception ignore) {
                // ignore
            }
        }
    }
    
    /** Query link connection state (prefer centralized router). */
    public boolean isPortConnected() {
        // Prefer centralized router query
        if (com.serialcomm.service.TransportViewModel.getInstance().isConnected()) return true;
        return serialPort != null && serialPort.isOpen();
    }
    
    /** Connect to serial port.
     * @param portName system port name
     * @param baudRate baud rate
     */
    protected void connectPort(String portName, int baudRate) {
        try {
            // Log connection attempt
            logger.info(languageManager.getString("log.serial.connecting"), portName, baudRate);
            
            // Acquire SerialPort instance
            serialPort = SerialPort.getCommPort(portName);
            
            // Update configuration
            portConfig.setPortName(portName);
            portConfig.setBaudRate(baudRate);
            
            // Apply serial communication parameters
            serialPort.setComPortParameters(
                portConfig.getBaudRate(),    // baud rate
                portConfig.getDataBits(),    // data bits (typically 8)
                portConfig.getStopBits(),    // stop bits (typically 1)
                portConfig.getParity()       // parity (typically none)
            );
            
            // Configure timeouts
            serialPort.setComPortTimeouts(
                SerialPort.TIMEOUT_NONBLOCKING,  // non-blocking
                portConfig.getTimeout(),         // read timeout (ms)
                0                                // write timeout (ms)
            );
            
            // Open port
            boolean opened = serialPort.openPort();
            if (!opened) {
                // If opening failed, throw
                throw new RuntimeException(languageManager.getString("error.serial.open.failed", portName));
            }
            
            // Log connected
            logger.info(languageManager.getString("log.serial.connected"), portName, baudRate);
        } catch (Exception e) {
            // Log connect failure
            logger.error(languageManager.getString("log.serial.connect.failed"), e);
            // Re-throw for caller handling
            throw e;
        }
    }
    
    protected void disconnectPort() {
        try {
            if (serialPort != null && serialPort.isOpen()) {
                String portName = serialPort.getSystemPortName();
                logger.info(languageManager.getString("log.serial.disconnecting"), portName);
                
                stopReceiveTimer();
                boolean closed = serialPort.closePort();
                
                if (closed) {
                    logger.info(languageManager.getString("log.serial.disconnected"), portName);
                } else {
                    logger.warn(languageManager.getString("log.serial.close.warning"), portName);
                }
            }
            serialPort = null;
        } catch (Exception e) {
            logger.error(languageManager.getString("log.serial.disconnect.failed"), e);
            throw e;
        }
    }
    
    protected void sendData(String data) {
        if (data == null || data.isEmpty()) {
            logger.warn(languageManager.getString("log.serial.send.empty"));
            return;
        }
        try {
            // 使用集中路由器进行合流发送
            if (currentReceiveMode == ReceiveMode.HEX) {
                com.serialcomm.service.TransportViewModel.getInstance().sendHex(data);
            } else {
                com.serialcomm.service.TransportViewModel.getInstance().sendAscii(data);
            }
        } catch (Exception e) {
            handleException(languageManager.getString("error.serial.send.failed", e.getMessage()), e);
        }
    }
    
    /** Deprecated: 统一由 Router 推送，本方法不再启动轮询。 */
    @Deprecated
    protected void startReceiveTimer() {
        logger.info("Legacy receive timer is deprecated and disabled; using centralized router push model");
    }
    
    protected void stopReceiveTimer() {
        if (receiveTimer != null) {
            receiveTimer.cancel();
            receiveTimer.purge(); // ensure timer is fully cleaned up
            receiveTimer = null;
            logger.info(languageManager.getString("log.serial.receive.timer.stop"));
        }
    }
    
    /** Deprecated: legacy timed polling receive (kept for compatibility). */
    @Deprecated
    private void receiveData() {
        // Check port state
        if (serialPort == null || !serialPort.isOpen()) {
            return;
        }
        
        try {
            // Check available bytes
            int bytesAvailable = serialPort.bytesAvailable();
            if (bytesAvailable > 0) {
                // Read into buffer
                byte[] buffer = new byte[bytesAvailable];
                int bytesRead = serialPort.readBytes(buffer, bytesAvailable);
                
                if (bytesRead > 0) {
                    // Log received size
                    logger.debug(languageManager.getString("log.serial.data.received"), bytesRead);
                    
                    // Update byte counters
                    if (bytesReceivedCallback != null) {
                        bytesReceivedCallback.accept(bytesRead);
                    }
                    
                    // Convert according to receive mode
                    String receivedData;
                    if (currentReceiveMode == ReceiveMode.HEX) {
                        // HEX mode: bytes -> hex string
                        receivedData = bytesToHex(buffer, bytesRead);
                    } else {
                        // ASCII mode: bytes -> UTF-8 string
                        receivedData = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    }
                    
                    // Log processing state
                    logger.debug(languageManager.getString("log.serial.data.processing"), isActive);
                    
                    // Deliver only to active controller
                    if (isActive) {
                        // Delegate handling to subclass
                        logger.debug(languageManager.getString("log.serial.data.process"));
                        onDataReceived(receivedData);
                    } else {
                        // Not active: ignore
                        logger.debug(languageManager.getString("log.serial.data.ignore"));
                    }
                }
            }
        } catch (Exception e) {
            // Log exceptions encountered while receiving
            logger.error(languageManager.getString("log.serial.receive.error"), e);
        }
    }
    
    private String bytesToHex(byte[] bytes, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString().toUpperCase();
    }
    
    private byte[] hexStringToByteArray(String hexString) {
        // Accept common formats:
        // 1) With spaces: "FD 09 00 02"
        // 2) Compact: "FD090002"
        // 3) Mixed separators/0x: "0xFD,09;00-02"
        if (hexString == null) {
            return new byte[0];
        }
        String cleaned = hexString.replaceAll("[^0-9A-Fa-f]", "");
        if (cleaned.isEmpty()) {
            return new byte[0];
        }
        // If odd-length, pad a leading 0
        if ((cleaned.length() & 1) == 1) {
            cleaned = "0" + cleaned;
        }
        int len = cleaned.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int hi = Character.digit(cleaned.charAt(i * 2), 16);
            int lo = Character.digit(cleaned.charAt(i * 2 + 1), 16);
            if (hi == -1 || lo == -1) {
                logger.warn(languageManager.getString("log.serial.hex.invalid"), cleaned.substring(Math.max(0, i * 2), Math.min(cleaned.length(), i * 2 + 2)));
                out[i] = 0;
            } else {
                out[i] = (byte) ((hi << 4) + lo);
            }
        }
        return out;
    }
    
    /** Subclasses must handle received text (ASCII/HEX). */
    protected abstract void onDataReceived(String data);

    /** Callback from router: convert bytes to current receive-mode text, deliver if active. */
    public void onBytesFromRouter(byte[] buffer, int length) {
        try {
            String receivedData;
            if (currentReceiveMode == ReceiveMode.HEX) {
                receivedData = bytesToHex(buffer, length);
            } else {
                receivedData = new String(buffer, 0, length, StandardCharsets.UTF_8);
            }
            if (isActive) {
                onDataReceived(receivedData);
            }
        } catch (Exception e) {
            logger.error(languageManager.getString("log.serial.receive.error"), e);
        }
    }
    
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
    
    protected void showAlert(Alert.AlertType type, String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
    
    public void cleanup() {
        try {
            logger.info(languageManager.getString("log.cleanup.base.start"));
            stopReceiveTimer();
            disconnectPort();
            logger.info(languageManager.getString("log.cleanup.base.complete"));
        } catch (Exception e) {
            logger.error(languageManager.getString("log.cleanup.base.error"), e);
        }
    }
}