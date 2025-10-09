package com.serialcomm;

import com.serialcomm.controller.DebugTabController;
import com.serialcomm.controller.MainController;
import com.serialcomm.controller.MavlinkTabController;
import com.serialcomm.controller.ProtocolTabController;
import com.serialcomm.controller.ParamTabController;
import com.serialcomm.controller.InspectorTabController;
import com.serialcomm.controller.StatusTabController;
import com.serialcomm.controller.VisualStatusTabController;
import com.serialcomm.util.LanguageManager;
import com.serialcomm.util.UiUpdateQueue;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Main application class for the Serial Communication Assistant.
 * Extends JavaFX Application and orchestrates startup, primary stage setup,
 * internationalization, controller wiring, and graceful shutdown.
 */
public class App extends Application {
    /** Logger for application lifecycle and runtime events */
    private static final Logger logger = LoggerFactory.getLogger(App.class);
    
    /** Primary controller that coordinates tab controllers and shared resources */
    private MainController mainController;
    
    /** Language manager instance providing i18n resources */
    private LanguageManager languageManager;
    
    /** Reference to the primary Stage (for title updates) */
    private static Stage mainStage;
    
    /**
     * Application entry point invoked by JavaFX.
     * Initializes the UI and all controllers, restores language, and configures logging.
     *
     * @param primaryStage the primary Stage
     * @throws Exception if startup fails (re-thrown to JavaFX framework)
     */
    @Override
    public void start(Stage primaryStage) throws Exception {
        // Initialize language manager and restore user's previously selected locale before FXML load
        languageManager = LanguageManager.getInstance();
        try {
            java.util.Locale saved = com.serialcomm.util.Settings.getSavedLocale();
            if (saved != null && languageManager.isLanguageSupported(saved)) {
                languageManager.setLanguage(saved);
            }
        } catch (Throwable ignore) {}
        
        // Initialize per-user data directories and set LOG_DIR for logback
        try {
            java.io.File logs = com.serialcomm.util.DataDirs.getLogsDir();
            System.setProperty("LOG_DIR", logs.getAbsolutePath());
        } catch (Throwable ignore) {}

        // Log that the application is starting
        logger.info(languageManager.getString("log.app.starting"));
        
        // Install a global uncaught exception handler for the FX thread
        Thread.currentThread().setUncaughtExceptionHandler((thread, exception) -> {
            logger.error(languageManager.getString("log.exception.uncaught"), exception);
        });
        
        try {
            // Load the main FXML and set the resource bundle for i18n
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
            loader.setResources(languageManager.getCurrentResourceBundle());
            Parent root = loader.load();
            
            // Obtain the main controller instance
            mainController = loader.getController();
            
            // Wire up tab controllers found in the main FXML namespace
            setupTabControllers(loader);
            
            // Initialize the default tab after the UI is shown; run on the JavaFX Application Thread
            Platform.runLater(() -> {
                if (mainController != null) {
                    mainController.initializeDefaultTab();
                }
            });
            
            // Create the primary Scene; 1000x700 default prevents truncated labels
            Scene scene = new Scene(root, 1000, 700);
            
            // Keep a reference to the primary Stage
            mainStage = primaryStage;
            
            // Configure primary Stage
            primaryStage.setTitle(languageManager.getString("ui.main.title"));
            primaryStage.setScene(scene);
            primaryStage.setMinWidth(600);
            primaryStage.setMinHeight(400);
            
            // Attempt to load application icons (prefer classpath /images/, dev-time FS fallback)
            try {
                boolean loaded = false;
                // 1) Classpath: /images/icon.png
                try {
                    java.io.InputStream is = getClass().getResourceAsStream("/images/icon.png");
                    if (is != null) { primaryStage.getIcons().add(new Image(is)); loaded = true; }
                } catch (Throwable ignore) {}
                // 2) Dev-time fallback from filesystem when running via Gradle
                if (!loaded) {
                    // Matches build script: source located at resource/UI/head180.png
                    java.io.File f = new java.io.File("resource/UI/head180.png");
                    if (f.exists()) { primaryStage.getIcons().add(new Image(f.toURI().toString())); loaded = true; }
                }
                if (!loaded) {
                    throw new IllegalStateException("no icon found in /images/icon.png");
                }
            } catch (Exception e) {
                // If icon loading fails, warn but do not interrupt app startup
                logger.warn(languageManager.getString("log.app.icon.load.failed"), e.getMessage());
            }
            
            // Hook graceful shutdown on window close
            primaryStage.setOnCloseRequest(event -> {
                logger.info(languageManager.getString("log.app.shutdown"));
                // Clean up main controller resources
                if (mainController != null) {
                    mainController.cleanup();
                }
                // Clean up language manager resources
                if (languageManager != null) {
                    languageManager.cleanup();
                }
                // Close any map data sources (MBTiles/VectorMBTiles) uniformly
                try {
                    java.util.List<com.serialcomm.map.TileSource> all = com.serialcomm.service.MapDataService.getInstance().getAllSources();
                    for (com.serialcomm.map.TileSource ts : all) {
                        try { if (ts instanceof java.lang.AutoCloseable) ((java.lang.AutoCloseable) ts).close(); } catch (Throwable ignore) {}
                    }
                } catch (Throwable ignore) {}
                logger.info(languageManager.getString("log.app.cleanup"));
            });
            
            // Show the primary window
            primaryStage.show();
            
            // Log that the application has started successfully
            logger.info(languageManager.getString("log.app.started"));

            // Apply persisted log level setting (via service)
            try { com.serialcomm.service.LogLevelService.getInstance().applySavedLevelOnStartup(); } catch (Throwable ignore) {}

            // First-run bootstrap: export ArduPilot + PX4 parameter seeds if missing and load in background
            try {
                com.serialcomm.service.Scheduler.getInstance().ensureBackground()
                    .submit(() -> { try { com.serialcomm.service.ParamMetadataService.getInstance().bootstrapSeedsIfFirstRun(); } catch (Throwable ignore) {} });
            } catch (Throwable ignore) {}

            // Initialize the capability service (subscribes dispatcher for capability/version logs)
            try { com.serialcomm.service.CapabilityService.getInstance(); } catch (Throwable ignore) {}

            // Start periodic ErrorMonitor aggregation (every 10s) via the shared Scheduler
            try {
                com.serialcomm.service.Scheduler.getInstance().ensureBackground()
                    .scheduleAtFixedRate(() -> {
                        try { com.serialcomm.util.ErrorMonitor.flushAll(); }
                        catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("error.monitor.flush", t); }
                    }, 10_000L, 10_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Throwable t) { com.serialcomm.util.ErrorMonitor.record("error.monitor.schedule", t); }

            // Start online-state monitor (3s offline threshold; check every 1s)
            com.serialcomm.service.HeartbeatMonitor.getInstance().start(1000L, 3000L);
        } catch (Exception e) {
            // Log startup failure error
            logger.error(languageManager.getString("log.app.start.failed"), e);
            // Re-throw so JavaFX framework can handle it
            throw e;
        }
    }
    
    private void setupTabControllers(FXMLLoader mainLoader) {
        try {
            logger.info(languageManager.getString("log.controller.tab.setup.start"));
            
            // Retrieve Debug tab controller
            DebugTabController debugController = 
                (DebugTabController) mainLoader.getNamespace().get("debugTabController");
            if (debugController != null && mainController != null) {
                mainController.setDebugTabController(debugController);
                logger.info(languageManager.getString("log.controller.debug.set"));
            } else {
                logger.warn("DebugTabController not found or MainController is null");
            }
            
            // Retrieve Protocol tab controller
            ProtocolTabController protocolController = 
                (ProtocolTabController) mainLoader.getNamespace().get("protocolTabContentController");
            if (protocolController != null && mainController != null) {
                mainController.setProtocolTabController(protocolController);
                logger.info(languageManager.getString("log.controller.protocol.set"));
            } else {
                logger.warn("ProtocolTabController not found or MainController is null");
            }
            
            // Retrieve MAVLink tab controller
            MavlinkTabController mavlinkController = 
                (MavlinkTabController) mainLoader.getNamespace().get("mavlinkTabContentController");
            if (mavlinkController != null && mainController != null) {
                mainController.setMavlinkTabController(mavlinkController);
                logger.info("MavlinkTabController set");
            } else {
                logger.warn("MavlinkTabController not found or MainController is null");
            }
            // Retrieve Inspector tab controller
            InspectorTabController inspectorController =
                (InspectorTabController) mainLoader.getNamespace().get("inspectorTabContentController");
            if (inspectorController != null && mainController != null) {
                mainController.setInspectorTabController(inspectorController);
                logger.info("InspectorTabController set");
            } else {
                logger.warn("InspectorTabController not found or MainController is null");
            }
            // Retrieve Status tab controller
            StatusTabController statusController =
                (StatusTabController) mainLoader.getNamespace().get("statusTabContentController");
            if (statusController != null && mainController != null) {
                mainController.setStatusTabController(statusController);
                logger.info("StatusTabController set");
            } else {
                logger.warn("StatusTabController not found or MainController is null");
            }

            // Retrieve Parameter tab controller
            ParamTabController paramController =
                (ParamTabController) mainLoader.getNamespace().get("paramTabContentController");
            if (paramController != null && mainController != null) {
                paramController.setByteCountCallback(mainController::addReceivedBytes, mainController::addSentBytes);
                paramController.setStatusCallback(mainController);
                mainController.setParamTabController(paramController);
                logger.info("ParamTabController set");
            } else {
                logger.warn("ParamTabController not found or MainController is null");
            }
            // Retrieve Visual Status tab controller
            try {
                VisualStatusTabController visualController =
                    (VisualStatusTabController) mainLoader.getNamespace().get("visualStatusTabContentController");
                if (visualController != null && mainController != null) {
                    mainController.setVisualStatusTabController(visualController);
                    logger.info("VisualStatusTabController set");
                } else {
                    logger.warn("VisualStatusTabController not found or MainController is null");
                }
            } catch (Throwable ignore) {}
            
            logger.info(languageManager.getString("log.controller.tab.setup"));
        } catch (Exception e) {
            logger.error(languageManager.getString("log.controller.tab.setup.error"), e);
            // Do not re-throw here; allow application to continue running
        }
    }
    
    /**
     * Update the window title according to current language.
     * Called when language changes.
     */
    public static void updateWindowTitle() {
        if (mainStage != null) {
            LanguageManager languageManager = LanguageManager.getInstance();
            UiUpdateQueue.get().submit("app.window.title", () -> {
                try {
                    String newTitle = languageManager.getString("ui.main.title");
                    mainStage.setTitle(newTitle);
                    logger.debug("Window title updated to: {}", newTitle);
                } catch (Exception e) {
                    logger.error("{}: {}", "Failed to update window title", e.getMessage(), e);
                }
            });
        } else {
            logger.warn("mainStage is null, cannot update window title");
        }
    }
    
    @Override
    public void stop() {
        logger.info(languageManager.getString("log.app.stopping"));
        if (mainController != null) {
            mainController.cleanup();
        }
        if (languageManager != null) {
            languageManager.cleanup();
        }
        try { com.serialcomm.service.Scheduler.getInstance().shutdownNow(); } catch (Throwable ignore) {}
        Platform.exit();
        logger.info(languageManager.getString("log.app.stopped"));
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}