# HytaleDocs IntelliJ Plugin Changelog

## [1.3.0] - 2026-01-18

### Changed
- **New Plugin Icon**: Fresh HytaleDocs branding with simplified H-style icon
- **Tool Window Renamed**: Changed from "Hytale" to "HytaleDocs" for better brand consistency
- Icon now uses IntelliJ ExpUI color scheme for proper dark/light theme support

## [1.2.0] - 2026-01-17

### Added
- **Auto-Persistence for Authentication**: After successful OAuth authentication, the plugin automatically runs `/auth persistence Encrypted` to save credentials
- Authentication tokens are now persisted across server restarts

### Changed
- **Smarter Auth Detection**: Plugin now only prompts for authentication when seeing the specific `[WARN] [HytaleServer] No server tokens configured` message
- Removed false positive auth triggers from other server messages

### Fixed
- Fixed log format parsing for authentication detection (adapted to new timestamp format)
- Auth success detection now only triggers when an auth session is active (prevents false positives from boot messages)

## [1.1.0] - 2026-01-17

### Changed
- **IntelliJ 2025.3 Support**: Updated minimum version to IntelliJ IDEA 2025.3+
- **Fixed Documentation Rendering**: Rewrote markdown-to-HTML converter for proper list and content display
- **Improved CSS Compatibility**: Simplified CSS for better Java Swing HTML renderer support

### Fixed
- Documentation panel now properly renders markdown content
- Lists (ordered and unordered) are correctly wrapped in `<ul>` and `<ol>` tags
- StyleSheet is now properly applied to the HTML editor

## [1.0.0] - 2026-01-17

### Added
- **Project Wizard**: Create new Hytale plugin projects with pre-configured Gradle structure
- **Live Templates**:
  - `hyevent` - Register event listener
  - `hycmd` - Create command collection
  - `hyecs` - Create ECS event system
  - `hyplugin` - Create plugin main class
  - `hymsg` - Send formatted message
  - `hyconfig` - Create config handler
  - `hyscheduler` - Create scheduled task
  - `hycustom` - Create custom UI element
- **File Templates**: Plugin, Listener, Command, ECS System
- **Tool Window** with three tabs:
  - **Home**: Server controls, quick actions, resources
  - **Docs**: Browse documentation with offline mode support
  - **AI**: MCP server integration for Claude Code
- **Server Management**: Start/Stop/Restart server from IDE
- **MCP Integration**: One-click installation of hytaledocs-mcp-server
- **Offline Documentation**: Download and cache docs from GitHub
- **Server JAR Download**: Tools > Download Hytale Server JAR
- **Run Configurations**: Launch Hytale servers directly from IDE

### Technical
- Built with Kotlin and IntelliJ Platform SDK 2.2.1
- Supports IntelliJ IDEA 2024.3+
- Requires Java 25 for Hytale development
