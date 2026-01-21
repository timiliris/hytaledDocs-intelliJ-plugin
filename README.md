# HytaleDocs IntelliJ Plugin

<p align="center">
  <img src="src/main/resources/icons/hytaledocs-plugin-16.svg" alt="HytaleDocs Logo" width="64" height="64">
</p>

<p align="center">
  <a href="https://discord.gg/yAjaFBH4Y8"><img src="https://img.shields.io/discord/1234567890?color=5865F2&logo=discord&logoColor=white&label=Discord" alt="Discord"></a>
  <a href="https://github.com/HytaleDocs/hytale-intellij-plugin/releases"><img src="https://img.shields.io/github/v/release/HytaleDocs/hytale-intellij-plugin?color=blue" alt="Release"></a>
  <a href="https://plugins.jetbrains.com/plugin/26469-hytale-docs-dev-tools"><img src="https://img.shields.io/jetbrains/plugin/v/26469?color=green" alt="JetBrains Plugin"></a>
</p>

<!-- Plugin description -->
A comprehensive toolkit for developing Hytale server plugins in IntelliJ IDEA.

**Features:**
- Project Wizard for new Hytale plugin projects
- Run Configuration with Build, Deploy & Debug support
- Assets Explorer with preview for images, audio, and JSON
- Live Templates: `hyevent`, `hycmd`, `hyecs`, `hyplugin`, `hymsg`, `hyconfig`, `hyscheduler`, `hycustom`
- Tool Window with Server, Console, Assets, Docs, AI, and Infos tabs
- Integrated documentation browser with offline mode
- Hytale UI file (.ui) language support with syntax highlighting
- API autocompletion for Hytale server classes

**Requirements:** IntelliJ IDEA 2025.3+, Java 25
<!-- Plugin description end -->

## Community

Join our Discord to get help, share your plugins, and discuss Hytale modding:

[![Discord](https://img.shields.io/badge/Discord-Join%20Server-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/yAjaFBH4Y8)

## Features

### Run Configuration

The plugin provides a dedicated Run Configuration for Hytale servers:

- **Build before run** - Automatically build your plugin with Gradle
- **Deploy plugin** - Copy JAR to server's mods folder
- **Debug support** - Attach IntelliJ debugger to the running server
- **Auto-detection** - Automatically detects plugin info from manifest.json

Simply click **Run** or **Debug** to build, deploy, and start your server!

### Project Wizard

Create new Hytale plugin projects with pre-configured structure:
- File > New > Project > Hytale Plugin
- Automatic Gradle setup with correct dependencies
- Plugin manifest generation

### Assets Explorer

Browse and preview Hytale assets directly in the IDE:
- **Images** - PNG, JPG preview with zoom
- **Audio** - OGG playback with controls
- **JSON** - Syntax highlighted preview
- **Sync** - Compare local assets with server

### Hytale UI Files

Full language support for `.ui` files:
- Syntax highlighting
- Code completion
- Brace matching
- Color preview in gutter

### Live Templates

Code snippets for rapid development:

| Shortcut | Description |
|----------|-------------|
| `hyevent` | Register event listener |
| `hycmd` | Create command collection |
| `hyecs` | Create ECS event system |
| `hyplugin` | Create plugin main class |
| `hymsg` | Send formatted message |
| `hyconfig` | Create config handler |
| `hyscheduler` | Create scheduled task |
| `hycustom` | Create custom UI element |

### Tool Window

Access everything from the **HytaleDocs** tool window (right sidebar):

| Tab | Description |
|-----|-------------|
| **Server** | Start/Stop server, view status, quick settings |
| **Console** | Server console with command input |
| **Assets** | Browse and preview game assets |
| **Docs** | Browse documentation in IDE |
| **AI** | MCP Server integration for Claude |
| **Infos** | Useful links and commands reference |

### Additional Features

- **File Templates**: New > Hytale > Plugin/Listener/Command/ECS System/UI File
- **Server Setup**: Tools > Hytale Server > Setup Server (Java 25 + Server files)
- **Hot Reload**: Build and reload plugin on running server (Linux/Mac)
- **API Completion**: Autocomplete for Hytale server API classes

## Installation

### From JetBrains Marketplace (Recommended)

1. In IntelliJ: Settings > Plugins > Marketplace
2. Search for "Hytale Development Tools"
3. Click Install
4. Restart IntelliJ

### From GitHub Releases

1. Download the latest `.zip` from [Releases](https://github.com/HytaleDocs/hytale-intellij-plugin/releases)
2. In IntelliJ: Settings > Plugins > ⚙️ > Install plugin from disk...
3. Select the downloaded ZIP file
4. Restart IntelliJ

### From Source

```bash
git clone https://github.com/HytaleDocs/hytale-intellij-plugin.git
cd hytale-intellij-plugin
./gradlew buildPlugin
```
The plugin ZIP will be in `build/distributions/`

## Requirements

- IntelliJ IDEA 2025.3+
- Java 25 (for Hytale server development)
- Gradle 8.11+

## Quick Start

1. **Install the plugin** (see Installation above)
2. **Create a new project**: File > New > Project > Hytale Plugin
3. **Setup server**: Tools > Hytale Server > Setup Server
4. **Run your plugin**: Click the Run button (builds, deploys, starts server)
5. **Debug**: Click Debug to attach the debugger

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

Contributions are welcome! Here's how to get started:

### Branching Workflow

- **`main`** - Stable releases only. Protected branch requiring PR review.
- **`dev`** - Development branch. All PRs should target this branch.

### How to Contribute

1. **Fork** the repository
2. **Clone** your fork: `git clone https://github.com/YOUR_USERNAME/hytale-intellij-plugin.git`
3. **Create a branch** from `dev`: `git checkout -b feature/your-feature dev`
4. **Make your changes** and commit with clear messages
5. **Push** to your fork: `git push origin feature/your-feature`
6. **Open a PR** targeting the `dev` branch

### Development Setup

```bash
# Clone the repo
git clone https://github.com/HytaleDocs/hytale-intellij-plugin.git
cd hytale-intellij-plugin

# Run the plugin in a sandboxed IDE
./gradlew runIde

# Build the plugin
./gradlew buildPlugin

# Verify compatibility
./gradlew verifyPlugin
```

### Labels

We use labels to categorize issues and PRs:

| Label | Description |
|-------|-------------|
| `feature` | New feature or enhancement |
| `bug` | Something isn't working |
| `good first issue` | Good for newcomers |
| `help wanted` | Extra attention is needed |
| `ui` | UI file support related |
| `server` | Server management related |
| `wizard` | Project wizard related |

Join our [Discord](https://discord.gg/yAjaFBH4Y8) to discuss features and get help.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

---

<p align="center">
  Made with ❤️ by the <a href="https://hytale-docs.com">HytaleDocs</a> community
  <br>
  <a href="https://discord.gg/yAjaFBH4Y8">Discord</a> •
  <a href="https://hytale-docs.com">Website</a> •
  <a href="https://github.com/HytaleDocs">GitHub</a>
</p>
