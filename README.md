# HytaleDocs IntelliJ Plugin

<!-- Plugin description -->
A comprehensive toolkit for developing Hytale server plugins in IntelliJ IDEA.

**Features:**
- Project Wizard for new Hytale plugin projects
- Live Templates: `hyevent`, `hycmd`, `hyecs`, `hyplugin`, `hymsg`, `hyconfig`, `hyscheduler`, `hycustom`
- Tool Window with Home, Docs, and AI tabs
- Integrated documentation browser with offline mode
- MCP Server integration for Claude Code AI assistance
- Server management (Start/Stop/Restart)
- File templates for Plugin, Listener, Command, ECS System

**Requirements:** IntelliJ IDEA 2025.3+, Java 25
<!-- Plugin description end -->

## Features

### Project Wizard
Create new Hytale plugin projects with pre-configured structure:
- File > New > Project > Hytale Plugin
- Automatic Gradle setup with correct dependencies
- Plugin manifest generation

### Live Templates
Code snippets for rapid development:
- `hyevent` - Register event listener
- `hycmd` - Create command collection
- `hyecs` - Create ECS event system
- `hyplugin` - Create plugin main class
- `hymsg` - Send formatted message
- `hyconfig` - Create config handler
- `hyscheduler` - Create scheduled task
- `hycustom` - Create custom UI element

### Tool Window
Access everything from the Hytale tool window:

**Home Tab**
- Server controls (Start/Stop/Restart)
- Quick actions and project setup
- Resource links

**Docs Tab**
- Browse documentation directly in IDE
- Offline mode with local cache
- Search across all docs

**AI Tab**
- MCP Server integration for Claude Code
- One-click MCP installation
- Quick prompts for common tasks
- Launch Claude Code from IDE

### Additional Features
- **File Templates**: New > Hytale > Plugin/Listener/Command/ECS System
- **Server JAR Download**: Tools > Download Hytale Server JAR
- **Run Configurations**: Launch Hytale servers directly

## Installation

### From GitHub Releases (Recommended)
1. Download the latest `.zip` from [Releases](https://github.com/timiliris/hytaledDocs-intelliJ-plugin/releases)
2. In IntelliJ: Settings > Plugins > ⚙️ > Install plugin from disk...
3. Select the downloaded ZIP file
4. Restart IntelliJ

### From Source
```bash
git clone https://github.com/timiliris/hytaledDocs-intelliJ-plugin.git
cd hytaledDocs-intelliJ-plugin
./gradlew buildPlugin
```
The plugin ZIP will be in `build/distributions/`

## Requirements

- IntelliJ IDEA 2025.3+
- Java 25 (for Hytale server development)
- Gradle 8.11+

## MCP Server Integration

The plugin integrates with [hytaledocs-mcp-server](https://www.npmjs.com/package/hytaledocs-mcp-server) to provide AI-assisted development:

1. Open the **AI** tab in the Hytale tool window
2. Click **Install MCP Server (npm)**
3. Click **Install for Claude Code**
4. Restart Claude Code

Claude will now have access to all Hytale documentation!

## Documentation

- [Hytale Docs](https://hytale-docs.com) - Community documentation
- [Plugin Development Guide](https://hytale-docs.com/docs/modding/plugins/project-setup)
- [Events Reference](https://hytale-docs.com/docs/modding/plugins/events/overview)
- [API Reference](https://hytale-docs.com/docs/api)

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

---

Made with love by the [HytaleDocs](https://hytale-docs.com) community.
