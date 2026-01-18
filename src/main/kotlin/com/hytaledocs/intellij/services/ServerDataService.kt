package com.hytaledocs.intellij.services

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.hytaledocs.intellij.completion.data.ApiAccessInfo
import com.hytaledocs.intellij.completion.data.ArgumentTypeInfo
import com.hytaledocs.intellij.completion.data.BuiltinCommand
import com.hytaledocs.intellij.completion.data.CommandClassInfo
import com.hytaledocs.intellij.completion.data.CommandHierarchyItem
import com.hytaledocs.intellij.completion.data.CommandsData
import com.hytaledocs.intellij.completion.data.EventClassInfo
import com.hytaledocs.intellij.completion.data.EventInfo
import com.hytaledocs.intellij.completion.data.EventsData
import com.hytaledocs.intellij.completion.data.LifecycleMethodInfo
import com.hytaledocs.intellij.completion.data.ManifestField
import com.hytaledocs.intellij.completion.data.PluginClassInfo
import com.hytaledocs.intellij.completion.data.PluginsData
import com.hytaledocs.intellij.completion.data.PriorityValue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
class ServerDataService {

    companion object {
        private val LOG = Logger.getInstance(ServerDataService::class.java)

        private const val LOCAL_DATA_DIR = ".hytale-intellij/server-data"
        private const val BUNDLED_DATA_PATH = "/data"

        private const val EVENTS_FILE = "events.json"
        private const val COMMANDS_FILE = "commands.json"
        private const val PLUGINS_FILE = "plugins.json"

        @JvmStatic
        fun getInstance(): ServerDataService {
            return ApplicationManager.getApplication().getService(ServerDataService::class.java)
        }
    }

    private val gson: Gson = GsonBuilder().create()

    private var eventsData: EventsData? = null
    private var commandsData: CommandsData? = null
    private var pluginsData: PluginsData? = null

    private val eventsByClassName = ConcurrentHashMap<String, EventInfo>()
    private val eventsByFullName = ConcurrentHashMap<String, EventInfo>()
    private val eventInterfacesByClassName = ConcurrentHashMap<String, EventClassInfo>()
    private val lifecycleMethodsByName = ConcurrentHashMap<String, LifecycleMethodInfo>()
    private val commandHierarchyByName = ConcurrentHashMap<String, CommandHierarchyItem>()
    private val builtinCommandsByName = ConcurrentHashMap<String, BuiltinCommand>()

    private var isLoaded = false

    private fun getLocalDataDir(): Path {
        val userHome = System.getProperty("user.home")
        return Path.of(userHome, LOCAL_DATA_DIR)
    }

    @Synchronized
    private fun ensureLoaded() {
        if (isLoaded) return

        LOG.info("Loading Hytale server data...")

        eventsData = loadData(EVENTS_FILE, EventsData::class.java)
        commandsData = loadData(COMMANDS_FILE, CommandsData::class.java)
        pluginsData = loadData(PLUGINS_FILE, PluginsData::class.java)

        buildIndexes()
        isLoaded = true

        LOG.info("Hytale server data loaded: ${eventsByClassName.size} events, " +
                "${lifecycleMethodsByName.size} lifecycle methods, " +
                "${builtinCommandsByName.size} commands")
    }

    private fun <T> loadData(fileName: String, clazz: Class<T>): T? {
        val localFile = getLocalDataDir().resolve(fileName)
        if (Files.exists(localFile)) {
            try {
                Files.newBufferedReader(localFile).use { reader ->
                    LOG.info("Loading $fileName from local override: $localFile")
                    return gson.fromJson(reader, clazz)
                }
            } catch (e: Exception) {
                LOG.warn("Failed to load local $fileName, falling back to bundled", e)
            }
        }

        val resourcePath = "$BUNDLED_DATA_PATH/$fileName"
        javaClass.getResourceAsStream(resourcePath)?.use { stream ->
            try {
                InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                    LOG.info("Loading $fileName from bundled resources")
                    return gson.fromJson(reader, clazz)
                }
            } catch (e: Exception) {
                LOG.warn("Failed to load bundled $fileName", e)
            }
        }

        LOG.warn("No data found for $fileName")
        return null
    }

    private fun buildIndexes() {
        eventsData?.events?.forEach { event ->
            eventsByClassName[event.name] = event
            eventsByFullName[event.fullName] = event
        }

        eventsData?.coreInterfaces?.forEach { iface ->
            eventInterfacesByClassName[iface.className] = iface
        }

        eventsData?.baseEventClass?.let {
            eventInterfacesByClassName[it.className] = it
        }

        eventsData?.ecsEventSystem?.let { ecs ->
            ecs.baseClass?.let { eventInterfacesByClassName[it.className] = it }
            ecs.cancellableBase?.let { eventInterfacesByClassName[it.className] = it }
            ecs.cancellableInterface?.let { eventInterfacesByClassName[it.className] = it }
        }

        pluginsData?.lifecycleMethods?.forEach { method ->
            lifecycleMethodsByName[method.name] = method
        }

        commandsData?.commandHierarchy?.forEach { item ->
            commandHierarchyByName[item.name] = item
        }

        commandsData?.builtinCommands?.forEach { cmd ->
            builtinCommandsByName[cmd.name] = cmd
        }
    }

    @Synchronized
    fun reload() {
        isLoaded = false
        eventsByClassName.clear()
        eventsByFullName.clear()
        eventInterfacesByClassName.clear()
        lifecycleMethodsByName.clear()
        commandHierarchyByName.clear()
        builtinCommandsByName.clear()
        ensureLoaded()
    }

    fun getAllEvents(): List<EventInfo> {
        ensureLoaded()
        return eventsData?.events ?: emptyList()
    }

    fun getEventByClassName(className: String): EventInfo? {
        ensureLoaded()
        return eventsByClassName[className]
    }

    fun getEventByFullName(fullName: String): EventInfo? {
        ensureLoaded()
        return eventsByFullName[fullName]
    }

    fun getCoreInterfaces(): List<EventClassInfo> {
        ensureLoaded()
        return eventsData?.coreInterfaces ?: emptyList()
    }

    fun getEventPriorities(): List<PriorityValue> {
        ensureLoaded()
        return eventsData?.eventPriority?.values ?: emptyList()
    }

    fun getEventHierarchy(): Map<String, List<String>> {
        ensureLoaded()
        return eventsData?.eventHierarchy ?: emptyMap()
    }

    fun isHytaleEvent(className: String): Boolean {
        ensureLoaded()
        return eventsByClassName.containsKey(className) || eventsByFullName.containsKey(className)
    }

    fun getEventsMatchingPrefix(prefix: String): List<EventInfo> {
        ensureLoaded()
        val lowerPrefix = prefix.lowercase()
        return eventsByClassName.values.filter {
            it.name.lowercase().startsWith(lowerPrefix)
        }
    }

    fun getLifecycleMethods(): List<LifecycleMethodInfo> {
        ensureLoaded()
        return pluginsData?.lifecycleMethods ?: emptyList()
    }

    fun getLifecycleMethod(name: String): LifecycleMethodInfo? {
        ensureLoaded()
        return lifecycleMethodsByName[name]
    }

    fun getPluginBaseClass(): PluginClassInfo? {
        ensureLoaded()
        return pluginsData?.pluginBaseClass
    }

    fun getJavaPluginClass(): PluginClassInfo? {
        ensureLoaded()
        return pluginsData?.javaPluginClass
    }

    fun getPluginApiMethods(): Map<String, ApiAccessInfo> {
        ensureLoaded()
        return pluginsData?.apiAccess ?: emptyMap()
    }

    fun getManifestFields(): List<ManifestField> {
        ensureLoaded()
        return pluginsData?.manifestFormat?.fields ?: emptyList()
    }

    fun getCommandHierarchy(): List<CommandHierarchyItem> {
        ensureLoaded()
        return commandsData?.commandHierarchy ?: emptyList()
    }

    fun getCommandBaseClass(): CommandClassInfo? {
        ensureLoaded()
        return commandsData?.commandBaseClass
    }

    fun getArgumentTypes(): List<ArgumentTypeInfo> {
        ensureLoaded()
        return commandsData?.argumentSystem?.argumentTypes ?: emptyList()
    }

    fun getBuiltinCommands(): List<BuiltinCommand> {
        ensureLoaded()
        return commandsData?.builtinCommands ?: emptyList()
    }

    fun getEventsData(): EventsData? {
        ensureLoaded()
        return eventsData
    }

    fun getPluginsData(): PluginsData? {
        ensureLoaded()
        return pluginsData
    }

    fun getCommandsData(): CommandsData? {
        ensureLoaded()
        return commandsData
    }

    fun hasData(): Boolean {
        ensureLoaded()
        return eventsData != null || pluginsData != null || commandsData != null
    }
}
