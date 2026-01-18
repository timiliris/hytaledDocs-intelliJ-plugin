package com.hytaledocs.intellij.completion.data

/**
 * Data classes for Hytale API information extracted from server-analyzer.
 * Used for intelligent code completion and documentation.
 */

// ==================== Event Data Classes ====================

data class EventsData(
    val analysisDate: String? = null,
    val domain: String? = null,
    val baseEventClass: EventClassInfo? = null,
    val coreInterfaces: List<EventClassInfo> = emptyList(),
    val eventPriority: EventPriorityInfo? = null,
    val ecsEventSystem: EcsEventSystemInfo? = null,
    val events: List<EventInfo> = emptyList(),
    val registration: EventRegistrationInfo? = null,
    val dispatch: EventDispatchInfo? = null,
    val eventHierarchy: Map<String, List<String>> = emptyMap()
)

data class EventClassInfo(
    val className: String,
    val fullName: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val parent: String? = null,
    val description: String? = null,
    val methods: List<MethodInfo> = emptyList(),
    val verified: Boolean = false,
    val implements: List<String> = emptyList()
)

data class EventInfo(
    val name: String,
    val fullName: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val parent: String? = null,
    val cancellable: Boolean = false,
    val cancellableEvidence: String? = null,
    val isAbstract: Boolean = false,
    val isAsync: Boolean = false,
    val fields: List<FieldInfo> = emptyList(),
    val methods: List<MethodInfo> = emptyList(),
    val innerClasses: List<InnerClassInfo> = emptyList(),
    val annotations: List<String> = emptyList(),
    val verified: Boolean = false,
    val description: String? = null,
    val constants: List<ConstantInfo> = emptyList(),
    val implements: List<String> = emptyList()
)

data class MethodInfo(
    val name: String,
    val signature: String? = null,
    val lineNumber: Int? = null,
    val description: String? = null,
    val deprecated: Boolean = false
)

data class FieldInfo(
    val name: String,
    val type: String,
    val lineNumber: Int? = null,
    val accessor: String? = null,
    val description: String? = null
)

data class InnerClassInfo(
    val name: String,
    val type: String? = null,
    val lineNumber: Int? = null,
    val parent: String? = null,
    val cancellable: Boolean = false,
    val cancellableEvidence: String? = null,
    val fields: List<FieldInfo> = emptyList(),
    val values: List<String> = emptyList()
)

data class ConstantInfo(
    val name: String,
    val value: Int? = null,
    val description: String? = null
)

data class EventPriorityInfo(
    val className: String,
    val fullName: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val values: List<PriorityValue> = emptyList(),
    val verified: Boolean = false
)

data class PriorityValue(
    val name: String,
    val value: Int,
    val description: String? = null
)

data class EcsEventSystemInfo(
    val baseClass: EventClassInfo? = null,
    val cancellableBase: EventClassInfo? = null,
    val cancellableInterface: EventClassInfo? = null
)

data class EventRegistrationInfo(
    val mechanism: String? = null,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val registrationMethods: List<RegistrationMethodInfo> = emptyList(),
    val verified: Boolean = false
)

data class RegistrationMethodInfo(
    val name: String,
    val description: String? = null,
    val lineNumber: Int? = null
)

data class EventDispatchInfo(
    val mechanism: String? = null,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val dispatchMethods: List<RegistrationMethodInfo> = emptyList(),
    val dispatchFlow: String? = null,
    val verified: Boolean = false
)

// ==================== Plugin Data Classes ====================

data class PluginsData(
    val analysisDate: String? = null,
    val domain: String? = null,
    val pluginBaseClass: PluginClassInfo? = null,
    val javaPluginClass: PluginClassInfo? = null,
    val lifecycleMethods: List<LifecycleMethodInfo> = emptyList(),
    val pluginStates: PluginStatesInfo? = null,
    val manifestFormat: ManifestFormatInfo? = null,
    val authorInfo: AuthorInfoFormat? = null,
    val pluginIdentifier: PluginIdentifierInfo? = null,
    val pluginManager: PluginManagerInfo? = null,
    val apiAccess: Map<String, ApiAccessInfo> = emptyMap(),
    val eventRegistryMethods: RegistryMethodsInfo? = null,
    val commandRegistryMethods: RegistryMethodsInfo? = null,
    val pluginClassLoader: PluginClassLoaderInfo? = null,
    val pluginLoadingProcess: PluginLoadingProcessInfo? = null,
    val builtinPluginExamples: List<BuiltinPluginExample> = emptyList(),
    val pluginTypes: PluginTypesInfo? = null,
    val constructorRequirement: ConstructorRequirementInfo? = null
)

data class PluginClassInfo(
    val className: String,
    val fullName: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val isAbstract: Boolean = false,
    val isInterface: Boolean = false,
    val extendsClass: String? = null,
    val verified: Boolean = false
)

data class LifecycleMethodInfo(
    val name: String,
    val signature: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val description: String? = null,
    val codeSnippet: String? = null,
    val verified: Boolean = false
)

data class PluginStatesInfo(
    val className: String,
    val fullName: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val states: List<PluginState> = emptyList(),
    val codeSnippet: String? = null,
    val verified: Boolean = false
)

data class PluginState(
    val name: String,
    val description: String? = null
)

data class ManifestFormatInfo(
    val className: String,
    val fullName: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val format: String? = null,
    val fields: List<ManifestField> = emptyList(),
    val codeSnippet: String? = null,
    val verified: Boolean = false
)

data class ManifestField(
    val name: String,
    val type: String,
    val required: Boolean = false,
    val description: String? = null
)

data class AuthorInfoFormat(
    val className: String,
    val fullName: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val fields: List<ManifestField> = emptyList(),
    val codeSnippet: String? = null,
    val verified: Boolean = false
)

data class PluginIdentifierInfo(
    val className: String,
    val fullName: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val format: String? = null,
    val fields: List<ManifestField> = emptyList(),
    val codeSnippet: String? = null,
    val verified: Boolean = false
)

data class PluginManagerInfo(
    val className: String,
    val fullName: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val modsPath: String? = null,
    val methods: List<PluginManagerMethod> = emptyList(),
    val codeSnippet: String? = null,
    val verified: Boolean = false
)

data class PluginManagerMethod(
    val name: String,
    val signature: String? = null,
    val lineNumber: Int? = null,
    val description: String? = null
)

data class ApiAccessInfo(
    val method: String? = null,
    val returnType: String? = null,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val usageExample: String? = null,
    val verified: Boolean = false
)

data class RegistryMethodsInfo(
    val className: String,
    val fullName: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val methods: List<RegistryMethod> = emptyList(),
    val codeSnippet: String? = null,
    val verified: Boolean = false
)

data class RegistryMethod(
    val name: String,
    val signature: String? = null,
    val lineNumber: Int? = null,
    val description: String? = null
)

data class PluginClassLoaderInfo(
    val className: String,
    val fullName: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val extendsClass: String? = null,
    val description: String? = null,
    val loaderNames: Map<String, String> = emptyMap(),
    val codeSnippet: String? = null,
    val verified: Boolean = false
)

data class PluginLoadingProcessInfo(
    val description: String? = null,
    val phases: List<LoadingPhase> = emptyList(),
    val sourceFile: String? = null,
    val verified: Boolean = false
)

data class LoadingPhase(
    val name: String,
    val description: String? = null
)

data class BuiltinPluginExample(
    val name: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val purpose: String? = null,
    val codeSnippet: String? = null,
    val verified: Boolean = false
)

data class PluginTypesInfo(
    val className: String,
    val fullName: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val types: List<PluginTypeValue> = emptyList(),
    val codeSnippet: String? = null,
    val verified: Boolean = false
)

data class PluginTypeValue(
    val name: String,
    val displayName: String? = null
)

data class ConstructorRequirementInfo(
    val description: String? = null,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val verified: Boolean = false
)

// ==================== Command Data Classes ====================

data class CommandsData(
    val analysisDate: String? = null,
    val domain: String? = null,
    val commandBaseClass: CommandClassInfo? = null,
    val commandInterfaces: Map<String, CommandInterfaceInfo> = emptyMap(),
    val annotations: List<String> = emptyList(),
    val annotationNote: String? = null,
    val argumentSystem: ArgumentSystemInfo? = null,
    val registration: CommandRegistrationInfo? = null,
    val commandExecution: CommandExecutionInfo? = null,
    val tabCompletion: TabCompletionInfo? = null,
    val tokenization: TokenizationInfo? = null,
    val commandHierarchy: List<CommandHierarchyItem> = emptyList(),
    val builtinCommands: List<BuiltinCommand> = emptyList(),
    val permissionSystem: PermissionSystemInfo? = null,
    val commandFeatures: Map<String, CommandFeatureInfo> = emptyMap()
)

data class CommandClassInfo(
    val className: String,
    val fullName: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val isAbstract: Boolean = false,
    val verified: Boolean = false,
    val description: String? = null
)

data class CommandInterfaceInfo(
    val className: String,
    val fullName: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val methods: List<MethodInfo> = emptyList(),
    val verified: Boolean = false
)

data class ArgumentSystemInfo(
    val baseClass: ArgumentBaseClassInfo? = null,
    val argumentTypeClass: ArgumentTypeClassInfo? = null,
    val argumentTypes: List<ArgumentTypeInfo> = emptyList(),
    val relativeTypes: List<RelativeTypeInfo> = emptyList(),
    val commonArgTypes: String? = null
)

data class ArgumentBaseClassInfo(
    val name: String,
    val fullName: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val verified: Boolean = false
)

data class ArgumentTypeClassInfo(
    val name: String,
    val fullName: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val methods: List<MethodInfo> = emptyList(),
    val verified: Boolean = false
)

data class ArgumentTypeInfo(
    val name: String,
    val fullName: String? = null,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val description: String? = null,
    val verified: Boolean = false
)

data class RelativeTypeInfo(
    val name: String,
    val sourceFile: String? = null,
    val description: String? = null,
    val verified: Boolean = false
)

data class CommandRegistrationInfo(
    val managerClass: CommandManagerInfo? = null,
    val registerMethods: List<RegisterMethodInfo> = emptyList(),
    val pluginRegistration: PluginRegistrationInfo? = null,
    val verified: Boolean = false
)

data class CommandManagerInfo(
    val name: String,
    val fullName: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val verified: Boolean = false
)

data class RegisterMethodInfo(
    val name: String,
    val signature: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val description: String? = null
)

data class PluginRegistrationInfo(
    val name: String,
    val fullName: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val description: String? = null
)

data class CommandExecutionInfo(
    val handleCommand: HandleCommandInfo? = null,
    val executeMethod: ExecuteMethodInfo? = null,
    val commandContext: CommandContextInfo? = null
)

data class HandleCommandInfo(
    val signature: String? = null,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val description: String? = null
)

data class ExecuteMethodInfo(
    val signature: String? = null,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val description: String? = null
)

data class CommandContextInfo(
    val name: String,
    val fullName: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val description: String? = null
)

data class TabCompletionInfo(
    val mechanism: String? = null,
    val suggestionProviderInterface: SuggestionProviderInterfaceInfo? = null,
    val suggestionResult: SuggestionResultInfo? = null,
    val verified: Boolean = false
)

data class SuggestionProviderInterfaceInfo(
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null
)

data class SuggestionResultInfo(
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val description: String? = null
)

data class TokenizationInfo(
    val tokenizerClass: TokenizerClassInfo? = null,
    val verified: Boolean = false
)

data class TokenizerClassInfo(
    val name: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val description: String? = null
)

data class CommandHierarchyItem(
    val name: String,
    val extends: String? = null,
    val description: String? = null,
    val sourceFile: String? = null,
    val lineNumber: Int? = null
)

data class BuiltinCommand(
    val name: String,
    val className: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val usage: String? = null,
    val permission: String? = null,
    val aliases: List<String> = emptyList(),
    val description: String? = null,
    val subcommands: List<String> = emptyList(),
    val codeSnippet: String? = null,
    val verified: Boolean = false
)

data class PermissionSystemInfo(
    val howPermissionsWork: String? = null,
    val permissionHolderInterface: PermissionHolderInterfaceInfo? = null,
    val hytalePermissions: HytalePermissionsInfo? = null,
    val permissionGroups: String? = null,
    val verified: Boolean = false
)

data class PermissionHolderInterfaceInfo(
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null
)

data class HytalePermissionsInfo(
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val codeSnippet: String? = null,
    val description: String? = null
)

data class CommandFeatureInfo(
    val description: String? = null,
    val example: String? = null,
    val sourceFile: String? = null,
    val verified: Boolean = false
)
