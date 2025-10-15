# JGroundControl - Comprehensive User Guide

## Table of Contents
1. [Overview](#overview)
2. [System Architecture](#system-architecture)
3. [Installation & Setup](#installation--setup)
4. [User Interface Guide](#user-interface-guide)
5. [Feature Documentation](#feature-documentation)
6. [Advanced Configuration](#advanced-configuration)
7. [Troubleshooting](#troubleshooting)
8. [Performance Optimization](#performance-optimization)

## Overview

JGroundControl is a modern, cross-platform ground station software built with JavaFX 21 and JDK 21. It provides comprehensive support for MAVLink-based unmanned aerial vehicles (UAVs) with advanced features including multi-device communication, asynchronous UI, multi-language support, and intelligent map integration.

### Key Features
- **Cross-Platform Support**: Windows, Linux, macOS
- **Multi-Device Communication**: Serial ports, TCP, UDP connections
- **MAVLink Protocol Support**: Full v1/v2 compatibility with official parser
- **Asynchronous Architecture**: Non-blocking UI with 60Hz update frequency
- **Multi-Language Interface**: Real-time language switching (English/Chinese)
- **Advanced Mapping**: Offline/online maps with intelligent caching
- **Real-time Telemetry**: Live flight data visualization
- **Parameter Management**: Complete UAV parameter editing and synchronization

## System Architecture

### Layered Design
```
┌─────────────────────────────────────────┐
│                UI Layer                 │
│  (Controllers + FXML + CSS + i18n)      │
├─────────────────────────────────────────┤
│              Service Layer              │
│  (MavlinkDispatcher, TelemetryRegistry, │
│   Scheduler, LanguageManager, etc.)     │
├─────────────────────────────────────────┤
│              Map Layer                  │
│  (MapView, TileSource, Cache, etc.)     │
└─────────────────────────────────────────┘
```

### Core Components

#### Controllers (UI Layer)
- **MainController**: Central coordinator managing all tab controllers
- **DebugTabController**: Serial communication debugging interface
- **ProtocolTabController**: MAVLink frame generation and transmission
- **MavlinkTabController**: MAVLink frame reception and analysis
- **InspectorTabController**: Message frequency and content analysis
- **StatusTabController**: Real-time telemetry data display
- **ParamTabController**: UAV parameter management
- **VisualStatusTabController**: Flight data visualization with maps

#### Services (Business Logic)
- **MavlinkDispatcher**: MAVLink protocol parsing and message routing
- **TelemetryRegistry**: Telemetry data snapshot storage and management
- **Scheduler**: Unified task scheduling with monitoring and background thread pools
- **LanguageManager**: Multi-language support with real-time switching
- **TransportViewModel**: Unified transport layer abstraction
- **StatusBarService**: Status message management
- **LogLevelService**: Dynamic log level management

#### Link Layer (Communication)
- **LinkManager**: Unified link management supporting "single-active" mode
- **LinkAdapter SPI**: Pluggable link interface for extensibility
- **AbstractRouter**: Shared base class for outbound queues, priority, and backpressure
- **SerialRouter/NetworkRouter**: Specialized implementations for different transport types

#### Map Layer (Visualization)
- **MapView**: Interactive map component with zoom/pan controls
- **TileSource**: Abstract interface for map data sources
- **Offline Sources**: MBTiles, OSM PBF support
- **Online Sources**: OpenStreetMap, Map4Fly integration
- **Intelligent Caching**: LRU cache with disk persistence

### Asynchronous Features

#### UI Update Queue
- **Frequency**: ~60Hz batch processing to prevent UI flooding
- **Thread Separation**: Monitoring threads (single) + background threads (2)
- **Unified Thread Factory**: `Scheduler.namedFactory` with consistent naming and exception handling
- **Backpressure Control**: Outbound queue capacity limits with priority-based dropping
- **Exception Sampling**: `ErrorGuard` + `ErrorMonitor` for exception aggregation

#### Performance Optimizations
- **UI Batching**: `UiUpdateQueue` consolidates updates to prevent `Platform.runLater` flooding
- **Asynchronous Processing**: Background threads handle I/O and computation tasks
- **Smart Caching**: Multi-level caching strategy with LRU and disk persistence
- **Memory Management**: Soft reference caching to prevent memory leaks

## Installation & Setup

### System Requirements
- **Java**: JDK 21 (Temurin recommended)
- **Operating System**: Windows 10/11, Linux (Ubuntu/CentOS), macOS
- **Memory**: Minimum 2GB RAM, 4GB recommended
- **Storage**: 500MB for application, additional space for maps and logs

### Linux Dependencies
```bash
# Ubuntu/Debian
sudo apt-get install libgtk-3-0 libx11-6 libnss3 libasound2 libxrender1 libxext6 libxtst6 libfreetype6

# CentOS/RHEL
sudo yum install gtk3 libX11 nss alsa-lib libXrender libXext libXtst freetype
```

### Installation Methods

#### Method 1: Pre-built Packages
1. Download the appropriate package for your platform:
   - `JGroundControl-1.0.0-windows-x64.zip` (Windows)
   - `JGroundControl-1.0.0-linux.zip` (Linux)
   - `JGroundControl-1.0.0-macos.zip` (macOS)
2. Extract to desired location
3. Run `JGroundControl.exe` (Windows) or `./JGroundControl` (Linux/macOS)

#### Method 2: From Source
```bash
# Clone repository
git clone https://github.com/burgeruser/JGroundControl.git
cd JGroundControl

# Build application
gradle clean --refresh-dependencies stageApp jpackageImage --warning-mode all

# Run application
cd ./build/jpackage/JGroundControl/
./JGroundControl.exe

# Create distribution packages
./gradlew jlinkZip          # Runtime image
./gradlew jpackageImage     # Application image
./gradlew jpackageZip       # Compressed package
```

## User Interface Guide

### Main Window Layout

The main window is organized into three primary sections:

#### Top Toolbar
Contains connection controls and system settings:

**Transport Selection**
- **Serial**: Traditional serial port communication
- **TCP**: Connect to remote TCP host
- **UDP**: Listen for incoming UDP data

**Connection Controls**
- **Port Dropdown**: Available serial ports (Serial mode only)
- **Baud Rate**: Communication speed (Serial mode only)
- **Refresh Button**: Scan for available ports
- **Connect/Disconnect Button**: Establish or terminate connection

**System Settings**
- **Language**: Real-time language switching (English/Chinese)
- **Timer Period**: Update frequency (10ms-500ms)
- **Log Level**: Runtime logging detail (OFF/ERROR/WARN/INFO/DEBUG)
- **Device Selection**: Choose specific UAV or "All" devices

#### Central Tab Area
Seven functional tabs providing different aspects of UAV communication:

1. **Serial Debug Tab**: Raw data communication
2. **MAVLink TX Tab**: Protocol frame generation
3. **MAVLink RX Tab**: Frame reception and analysis
4. **MAVLink Inspect Tab**: Message frequency analysis
5. **Live Status Tab**: Real-time telemetry display
6. **Visual Status Tab**: Flight visualization with maps
7. **Parameters Tab**: UAV parameter management

#### Bottom Status Bar
Real-time system information:
- **Connection Status**: Current link state
- **Byte Statistics**: Total received/sent bytes
- **Bandwidth Display**: Real-time data rates (bit/s, Kbit/s, Mbit/s)
- **Online Status**: UAV heartbeat information
- **Clear Stats Button**: Reset byte counters

### Detailed Tab Descriptions

#### 1. Serial Debug Tab
**Purpose**: Direct serial communication for debugging and testing

**Interface Elements**:
- **Receive Mode Toggle**: Switch between ASCII and HEX display
- **Receive Area**: Large text area showing incoming data
- **Send Area**: Text input for outgoing data
- **Send Button**: Transmit data (Ctrl+Enter shortcut available)
- **Clear Receive Button**: Clear incoming data display
- **Auto-scroll Toggle**: Automatic scrolling to latest data

**Usage**:
1. Select appropriate port and baud rate
2. Click "Connect" to establish serial link
3. Monitor incoming data in receive area
4. Type commands in send area and click "Send"
5. Use "Clear Receive" to reset display when needed

#### 2. MAVLink TX Tab
**Purpose**: Generate and transmit MAVLink protocol frames

**Interface Elements**:
- **Message Type Dropdown**: Select MAVLink message type
- **Parameter Fields**: Dynamic form based on selected message
- **Target System/Component**: Destination UAV identification
- **Send Button**: Transmit constructed frame
- **Frame Preview**: HEX representation of generated frame
- **Send History**: Log of transmitted messages

**Common Messages**:
- **HEARTBEAT**: Vehicle status and capabilities
- **REQUEST_DATA_STREAM**: Request telemetry streams
- **SET_MODE**: Change flight mode
- **COMMAND_LONG**: Execute specific commands
- **PARAM_REQUEST_LIST**: Request parameter list

**Usage**:
1. Select message type from dropdown
2. Fill required parameters
3. Set target system/component IDs
4. Review frame preview
5. Click "Send" to transmit

#### 3. MAVLink RX Tab
**Purpose**: Receive and analyze incoming MAVLink frames

**Interface Elements**:
- **Frame Display**: HEX representation of received frames
- **Statistics Panel**: Frame count, success rate, error rate
- **Filter Options**: Show only specific message types
- **Clear Button**: Reset display and statistics
- **Export Button**: Save received data to file

**Statistics Display**:
- **Total Frames**: Complete frame count
- **Successful**: Valid MAVLink frames
- **Failed**: Invalid or corrupted frames
- **CRC Errors**: Checksum failures
- **Frame Rate**: Messages per second

**Usage**:
1. Establish connection to UAV
2. Monitor incoming frames in real-time
3. Use filters to focus on specific messages
4. Review statistics for connection quality
5. Export data for analysis if needed

#### 4. MAVLink Inspect Tab
**Purpose**: Analyze message frequency and content patterns

**Interface Elements**:
- **Message List**: All received message types with frequencies
- **Frequency Chart**: Visual representation of message rates
- **Message Details**: Detailed breakdown of specific messages
- **Time Range Selector**: Analyze specific time periods
- **Export Analysis**: Save analysis results

**Analysis Features**:
- **Message Frequency**: Hz rate for each message type
- **Content Analysis**: Parameter value ranges and trends
- **Pattern Detection**: Identify unusual message patterns
- **Performance Metrics**: Communication efficiency analysis

**Usage**:
1. Connect to UAV and let data accumulate
2. Review message frequency list
3. Select specific messages for detailed analysis
4. Use time range selector for focused analysis
5. Export results for further analysis

#### 5. Live Status Tab
**Purpose**: Real-time display of UAV telemetry data

**Interface Elements**:
- **Telemetry Tree**: Hierarchical display of all received data
- **Value Updates**: Real-time parameter value changes
- **Status Indicators**: Visual indicators for system health
- **Refresh Rate**: Configurable update frequency
- **Data Export**: Save telemetry data to file

**Display Categories**:
- **Attitude**: Roll, pitch, yaw angles
- **Position**: GPS coordinates and altitude
- **Velocity**: Ground speed and climb rate
- **System**: Battery, GPS status, flight mode
- **Sensors**: IMU, compass, barometer readings

**Usage**:
1. Establish connection to UAV
2. Monitor real-time telemetry updates
3. Expand tree nodes for detailed information
4. Use refresh controls to adjust update rate
5. Export data for logging and analysis

#### 6. Visual Status Tab
**Purpose**: Advanced flight visualization with integrated mapping

**Interface Elements**:
- **Artificial Horizon**: 3D attitude indicator
- **Telemetry Grid**: 18 key flight parameters in card format
- **Interactive Map**: Real-time UAV position tracking
- **Map Controls**: Zoom, pan, layer selection
- **Flight Path**: Historical position tracking

**Visual Components**:
- **Attitude Display**: Roll, pitch, yaw visualization
- **Throttle Indicator**: Engine power visualization
- **Compass**: Heading and course information
- **Altitude Display**: Barometric and GPS altitude
- **Speed Indicators**: Ground speed and vertical velocity

**Map Features**:
- **Offline Maps**: MBTiles and OSM PBF support
- **Online Maps**: OpenStreetMap, Map4Fly integration
- **UAV Tracking**: Real-time position updates
- **Flight Path**: Historical track display
- **Map Layers**: Multiple data source support

**Usage**:
1. Connect to UAV for real-time data
2. Use artificial horizon for attitude monitoring
3. Monitor key parameters in telemetry grid
4. Track UAV position on interactive map
5. Use map controls for navigation and analysis

#### 7. Parameters Tab
**Purpose**: Complete UAV parameter management and editing

**Interface Elements**:
- **Parameter List**: All available UAV parameters
- **Search/Filter**: Find specific parameters
- **Value Editor**: Modify parameter values
- **Read/Write Controls**: Synchronize with UAV
- **Parameter Groups**: Organized by functional categories

**Parameter Categories**:
- **Flight Modes**: Mode-specific configurations
- **Navigation**: GPS and waypoint settings
- **Sensors**: IMU, compass, barometer calibration
- **Motors**: ESC and motor configurations
- **Safety**: Failsafe and emergency settings

**Operations**:
- **Read All**: Download complete parameter set
- **Write Selected**: Upload modified parameters
- **Reset to Defaults**: Restore factory settings
- **Import/Export**: Save/load parameter sets
- **Validation**: Check parameter consistency

**Usage**:
1. Connect to UAV
2. Click "Read All" to download parameters
3. Search for specific parameters
4. Modify values as needed
5. Click "Write Selected" to upload changes
6. Verify changes with UAV

### Advanced Interface Features

#### Multi-Language Support
- **Real-time Switching**: Change language without restart
- **Complete Localization**: All UI elements translated
- **Persistent Settings**: Language preference saved
- **Supported Languages**: English, Chinese (Simplified)

#### Device Management
- **Multi-Device Support**: Handle multiple UAVs simultaneously
- **Device Selection**: Choose specific UAV for communication
- **Auto-Detection**: Automatic device discovery
- **Session Management**: Track individual UAV sessions

#### Status Monitoring
- **Connection Quality**: Real-time link assessment
- **Data Rates**: Bandwidth monitoring and display
- **Error Tracking**: Communication error analysis
- **Performance Metrics**: System resource usage

## Feature Documentation

### Communication Protocols

#### MAVLink Support
- **Version Support**: MAVLink v1 and v2
- **Message Types**: Complete message set support
- **Protocol Features**: Heartbeat, command acknowledgment, parameter management
- **Error Handling**: CRC validation, sequence checking, timeout management

#### Transport Layers
- **Serial Communication**: Direct serial port access
- **TCP Client**: Connect to remote ground stations
- **UDP Listener**: Receive broadcast data
- **Connection Management**: Automatic reconnection, error recovery

### Mapping System

#### Offline Maps
- **MBTiles Support**: Vector and raster tile support
- **OSM PBF**: OpenStreetMap Protocol Buffer Format
- **Local Storage**: Efficient disk-based caching
- **Performance**: Fast rendering with minimal memory usage

#### Online Maps
- **OpenStreetMap**: Standard OSM tile server
- **Map4Fly**: Aviation-specific mapping
- **Proxy Support**: HTTP proxy configuration
- **Caching**: Intelligent tile caching system

#### Map Features
- **UAV Tracking**: Real-time position display
- **Flight Path**: Historical track visualization
- **Waypoint Display**: Mission waypoint overlay
- **Layer Management**: Multiple data source support

### Parameter Management

#### Parameter Types
- **Flight Parameters**: Mode-specific settings
- **Sensor Calibration**: IMU, compass, barometer
- **Motor Configuration**: ESC and motor settings
- **Safety Parameters**: Failsafe and emergency settings

#### Operations
- **Read Operations**: Download parameter sets
- **Write Operations**: Upload parameter changes
- **Validation**: Parameter consistency checking
- **Backup/Restore**: Parameter set management

### Real-time Visualization

#### Flight Instruments
- **Artificial Horizon**: 3D attitude display
- **Compass**: Heading and course indication
- **Altitude Display**: Barometric and GPS altitude
- **Speed Indicators**: Ground speed and vertical velocity
- **Throttle Display**: Engine power visualization

#### Telemetry Display
- **Real-time Updates**: Live parameter monitoring
- **Historical Data**: Trend analysis and logging
- **Alert System**: Critical parameter warnings
- **Data Export**: CSV and JSON export options

## Advanced Configuration

### System Settings

#### Logging Configuration
- **Log Levels**: OFF, ERROR, WARN, INFO, DEBUG
- **Log Files**: Separate files for different components
- **Log Rotation**: Automatic log file management
- **Performance Impact**: Minimal logging overhead

#### Performance Tuning
- **Update Frequency**: Configurable UI refresh rates
- **Memory Management**: Cache size optimization
- **Thread Management**: Background task optimization
- **Resource Usage**: CPU and memory monitoring

#### Network Configuration
- **Proxy Settings**: HTTP proxy support
- **Timeout Values**: Connection timeout configuration
- **Retry Logic**: Automatic reconnection settings
- **Bandwidth Limits**: Data rate throttling

### Customization Options

#### UI Customization
- **Language Selection**: Multi-language support
- **Display Options**: Font size and color schemes
- **Layout Preferences**: Window size and position
- **Shortcut Keys**: Keyboard shortcut configuration

#### Map Customization
- **Map Sources**: Custom tile server configuration
- **Layer Management**: Data source selection
- **Display Options**: Zoom levels and rendering
- **Cache Settings**: Storage and performance tuning

## Troubleshooting

### Common Issues

#### Connection Problems
- **Port Not Found**: Check device connections and drivers
- **Permission Denied**: Run with appropriate privileges
- **Timeout Errors**: Verify baud rate and connection settings
- **Data Corruption**: Check cable and port quality

#### Performance Issues
- **UI Lag**: Reduce update frequency or log level
- **Memory Usage**: Clear caches and restart application
- **CPU Usage**: Optimize background task settings
- **Map Loading**: Check network connection and cache settings

#### Data Issues
- **Missing Telemetry**: Verify MAVLink message requests
- **Parameter Errors**: Check UAV compatibility and settings
- **Map Display**: Verify map data availability and format
- **Export Problems**: Check file permissions and disk space

### Diagnostic Tools

#### Log Analysis
- **Application Logs**: Main application events
- **Link Logs**: Communication layer diagnostics
- **MAVLink Logs**: Protocol-specific information
- **Map Logs**: Mapping system diagnostics

#### Performance Monitoring
- **Resource Usage**: CPU, memory, and disk usage
- **Network Statistics**: Data rates and connection quality
- **UI Performance**: Update frequency and responsiveness
- **Error Tracking**: Exception monitoring and reporting

### Support Resources

#### Documentation
- **User Manual**: Comprehensive usage guide
- **API Documentation**: Developer reference
- **Configuration Guide**: Advanced setup options
- **Troubleshooting Guide**: Common problem solutions

#### Community Support
- **GitHub Issues**: Bug reports and feature requests
- **Discussion Forums**: User community support
- **Wiki Documentation**: Community-maintained guides
- **Video Tutorials**: Step-by-step usage guides

## Performance Optimization

### System Requirements
- **Minimum**: 2GB RAM, 1GHz CPU, 500MB storage
- **Recommended**: 4GB RAM, 2GHz CPU, 1GB storage
- **Optimal**: 8GB RAM, 3GHz CPU, 2GB storage

### Optimization Strategies

#### Memory Management
- **Cache Sizing**: Optimize cache sizes for available memory
- **Garbage Collection**: Tune JVM garbage collection settings
- **Object Pooling**: Reuse objects to reduce allocation overhead
- **Memory Monitoring**: Track memory usage and leaks

#### CPU Optimization
- **Thread Management**: Optimize thread pool sizes
- **Update Frequency**: Balance responsiveness with performance
- **Background Tasks**: Minimize CPU-intensive operations
- **Profiling**: Identify and optimize performance bottlenecks

#### Network Optimization
- **Data Compression**: Reduce network bandwidth usage
- **Connection Pooling**: Reuse network connections
- **Timeout Settings**: Optimize connection timeouts
- **Error Handling**: Efficient error recovery mechanisms

### Best Practices

#### Usage Guidelines
- **Regular Updates**: Keep application and dependencies current
- **Resource Monitoring**: Monitor system resource usage
- **Backup Procedures**: Regular data backup and recovery
- **Security Considerations**: Network security and data protection

#### Maintenance
- **Log Rotation**: Regular log file cleanup
- **Cache Management**: Periodic cache optimization
- **Update Procedures**: Safe application updates
- **Troubleshooting**: Systematic problem resolution

---

## Conclusion

JGroundControl represents a modern, feature-rich ground station solution that combines advanced MAVLink protocol support with intuitive user interface design. Its asynchronous architecture ensures smooth performance even under high data loads, while the comprehensive feature set provides everything needed for professional UAV operations.

The software's modular design allows for easy extension and customization, while the multi-language support makes it accessible to users worldwide. Whether you're conducting research, commercial operations, or educational activities, JGroundControl provides the tools and reliability needed for successful UAV missions.

For additional support, documentation, and community resources, please visit the project's GitHub repository and community forums.
