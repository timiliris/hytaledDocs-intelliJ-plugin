# HytaleDocs IntelliJ Plugin Changelog

## [Unreleased]

## [1.3.5] - 2026-01-22

### Fixed

- **Offline Docs Download**: Fixed threading error when downloading documentation (WriteAction was called from background thread) ([#7](https://github.com/timiliris/hytaledDocs-intelliJ-plugin/issues/7))
- **deployToServer Gradle Task**: Fixed conflict between default `jar` task and `shadowJar` by disabling the default jar task ([#7](https://github.com/timiliris/hytaledDocs-intelliJ-plugin/issues/7))
- **Cross-Platform Paths**: Improved path handling for better Windows/macOS/Linux compatibility
  - Fixed hardcoded slashes in cache directory paths
  - Now uses `Paths.get()` and `File.separator` consistently

## [1.3.4] - 2026-01-19

### Added

- **Mac & Linux Support**: Full cross-platform compatibility
  - OS-specific paths for Hytale launcher detection
  - Architecture detection (amd64/aarch64) for Java downloads
  - Platform-specific executable handling

### Contributors

- [@maartenpeels](https://github.com/maartenpeels) - Mac/Linux support

## [1.3.1] - 2026-01-18

### Added

- **Assets Explorer**: New "Assets" tab in tool window for browsing game assets
  - Tree view with toggle between "By Type" and "By Folder" modes
  - Support for ZIP archives (Assets.zip in server/ directory)
  - Preview panels for images, JSON, YAML, and audio files (including OGG)
  - Search and filter by asset type
  - Context menu: open in editor, reveal in project, copy path, extract from ZIP
  - Drag & drop to import assets
  - Statistics bar showing asset counts and sizes

### Fixed

- **Build & Deploy configuration**: Now correctly deploys to `server/mods/` instead of `server/plugins/` ([#2](https://github.com/timiliris/hytaledDocs-intelliJ-plugin/issues/2))
- **deployToServer Gradle task**: Fixed task dependency issue causing build failures ([#3](https://github.com/timiliris/hytaledDocs-intelliJ-plugin/issues/3))
- **Maven Build & Deploy**: Added maven-resources-plugin to auto-copy JAR to server/mods during package phase

## [1.3.0] - 2026-01-18

### Added

- **Hytale API Autocomplete**: Intelligent code completion for Hytale server API (events, methods, classes)

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

[Unreleased]: https://github.com/HytaleDocs/hytale-intellij-plugin/compare/v1.3.5...HEAD
[1.3.5]: https://github.com/HytaleDocs/hytale-intellij-plugin/compare/v1.3.4...v1.3.5
[1.3.4]: https://github.com/HytaleDocs/hytale-intellij-plugin/compare/v1.3.1...v1.3.4
[1.3.1]: https://github.com/HytaleDocs/hytale-intellij-plugin/compare/v1.3.0...v1.3.1
[1.3.0]: https://github.com/HytaleDocs/hytale-intellij-plugin/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/HytaleDocs/hytale-intellij-plugin/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/HytaleDocs/hytale-intellij-plugin/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/HytaleDocs/hytale-intellij-plugin/commits/v1.0.0
