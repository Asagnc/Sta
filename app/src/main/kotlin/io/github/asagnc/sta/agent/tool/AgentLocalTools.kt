package io.github.asagnc.sta.agent.tool

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import io.github.asagnc.sta.agent.browser.AgentBrowserSession
import io.github.asagnc.sta.agent.device.DeviceControlUnavailableException
import io.github.asagnc.sta.agent.device.RootAccess
import io.github.asagnc.sta.agent.device.RootShellDeviceController
import io.github.asagnc.sta.agent.device.BoundedRootCommandExecutor
import io.github.asagnc.sta.agent.model.AgentCodeExecutionToolCatalog
import io.github.asagnc.sta.agent.model.AgentMemoryWritePayload
import io.github.asagnc.sta.agent.model.AgentModelClient
import io.github.asagnc.sta.agent.model.AgentRunStatsToolCatalog
import io.github.asagnc.sta.agent.model.AgentScreenObservationContract
import io.github.asagnc.sta.agent.model.AgentSensitiveToolPolicy
import io.github.asagnc.sta.agent.model.AgentPlanFormat
import io.github.asagnc.sta.agent.model.AgentSubAgentBudget
import io.github.asagnc.sta.agent.model.AgentSubAgentRoles
import io.github.asagnc.sta.agent.model.AgentSubAgentRunner
import io.github.asagnc.sta.agent.model.AgentSubAgentToolCatalog
import io.github.asagnc.sta.agent.model.AgentWorktreeManager
import io.github.asagnc.sta.agent.model.SUB_AGENT_INVOCATION_LIMIT
import io.github.asagnc.sta.agent.model.SubAgentMailbox
import io.github.asagnc.sta.agent.model.SubAgentPlan
import io.github.asagnc.sta.agent.model.SubAgentSample
import io.github.asagnc.sta.agent.model.SubAgentScope
import io.github.asagnc.sta.agent.model.SubAgentWorkspace
import io.github.asagnc.sta.agent.terminal.RootShellTerminalController.RawCommandResult
import io.github.asagnc.sta.agent.overlay.AgentHapticFeedback
import io.github.asagnc.sta.agent.overlay.GestureIndicator
import io.github.asagnc.sta.agent.runtime.AgentAppContext
import io.github.asagnc.sta.agent.skill.SkillCompatibilityChecker
import io.github.asagnc.sta.agent.skill.SkillContentAudit
import io.github.asagnc.sta.agent.skill.SkillIndexService
import io.github.asagnc.sta.agent.skill.SkillInstallErrorCode
import io.github.asagnc.sta.agent.skill.SkillInstallResult
import io.github.asagnc.sta.agent.skill.SkillLoader
import io.github.asagnc.sta.agent.skill.SkillPackageInstaller
import io.github.asagnc.sta.agent.skill.SkillParser
import io.github.asagnc.sta.agent.skill.SkillResourceReader
import io.github.asagnc.sta.agent.skill.SkillResourceReadResult
import io.github.asagnc.sta.agent.skill.GitHubSkillRepositoryParser
import io.github.asagnc.sta.agent.skill.GitHubSkillInspection
import io.github.asagnc.sta.agent.skill.GitHubSkillRepository
import io.github.asagnc.sta.agent.skill.GitHubSkillSourceException
import io.github.asagnc.sta.agent.skill.PublicGitHubSkillSource
import io.github.asagnc.sta.agent.terminal.DetachedTaskSupervisor
import io.github.asagnc.sta.agent.terminal.FileTextOperations
import io.github.asagnc.sta.agent.terminal.FileToolLimits
import io.github.asagnc.sta.agent.terminal.LinuxDistribution
import io.github.asagnc.sta.agent.terminal.LinuxEnvironmentPaths
import io.github.asagnc.sta.agent.terminal.LinuxSandboxView
import io.github.asagnc.sta.agent.terminal.terminalEnvironment
import io.github.asagnc.sta.agent.terminal.RootShellTerminalController
import io.github.asagnc.sta.agent.terminal.shellQuote
import io.github.asagnc.sta.agent.terminal.SharedFolderMounts
import io.github.asagnc.sta.config.Prefs
import io.github.asagnc.sta.core.AgentLogger
import io.github.asagnc.sta.core.HookSupport
import io.github.asagnc.sta.data.db.StaDatabase
import io.github.asagnc.sta.data.db.SubAgentRunEntity
import io.github.asagnc.sta.data.repository.AgentMemoryException
import io.github.asagnc.sta.data.repository.AgentMemoryRepository
import io.github.asagnc.sta.data.repository.AgentMemoryWriteRequest
import io.github.asagnc.sta.data.repository.AgentMemoryWriteResult
import io.github.asagnc.sta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.asagnc.sta.data.repository.runMemoryMutation
import io.github.asagnc.sta.data.world.WorldHealth
import io.github.asagnc.sta.data.world.WorldKnowledgeLogic
import io.github.asagnc.sta.data.world.WorldKnowledgeStore
import io.github.asagnc.sta.data.world.WorldTraceStore
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking

internal class AgentLocalTools(
    private val context: Context,
    private val logger: AgentLogger,
    private val browserRunId: String = "",
    private val browserToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS)
    },
    private val terminalToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS)
    },
    private val deviceDirectToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS)
    },
    private val deviceSensitiveReadToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS)
    },
    private val deviceSensitiveActionToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS)
    },
    private val memoryToolsEnabled: () -> Boolean = {
        runBlocking { AgentMemoryRepository.isEnabled() }
    },
    private val memoryWritable: Boolean = true,
    private val screenshotExcludedPackages: () -> Set<String> = { emptySet() },
    private val screenObservationProvider: (
        (AgentScreenObservationContract.Options) -> RootShellDeviceController.Observation
    )? = null,
    private val onTaskPlanUpdated: ((String) -> Unit)? = null,
    /** 方案提交回调：把快照交给运行时存档并发事件，界面据此显示方案卡片。 */
    private val onPlanUpdated: ((String) -> Unit)? = null,
    /** 当前方案快照：派子智能体时把方向摘要附进背景，避免它的调研偏离方案。 */
    private val planSnapshot: (() -> String?)? = null,
    /** 本次 run 的度量摘要，由 Runtime 侧的执行循环提供；缺失时工具回报自己不可用。 */
    private val runStatsSummary: (() -> String)? = null,
    /** 受限子智能体；未开启时工具回报自己未启用。 */
    private val subAgentRunner: AgentSubAgentRunner? = null,
    /**
     * worktree 命令执行器，由宿主注入。
     *
     * 缺省时回落到 [runLinuxCommandRaw]（用本类自己的 terminal 控制器与用户所选发行版），
     * 传 null 的场景是单测；两者都不可用时写入模式直接回报不可用，
     * 而不是偷偷退化成在主工作区里改——隔离失效比功能不可用危险得多。
     */
    private val worktreeShellExecutor: ((String) -> String)? = null,
    /**
     * 本次 run 的标识，用作信箱分区键。
     *
     * 缺省空串表示不启用信箱（单角色委派没有同伴可分享，开着只会多占工具位）。
     */
    private val mailboxRunId: String = "",
    /** 共享信箱；由 Runtime 侧注入。 */
    private val mailbox: SubAgentMailbox? = null,
    private val beforeToolExecution: (String) -> ToolExecutionDecision = {
        ToolExecutionDecision.Allow
    },
    private val skillIndexService: SkillIndexService? = null,
    private val skillLoader: SkillLoader? = null,
    private val skillResourceReader: SkillResourceReader? = null,
    private val githubSkillSource: PublicGitHubSkillSource? = null,
    private val skillPackageInstaller: SkillPackageInstaller? = null,
    runAvailableSkillIds: Set<String> = emptySet(),
    pendingSkillConflict: PendingSkillConflictCapability? = null,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
    /**
     * 本次运行的沙箱资源视图，缺省是整棵工作区可写（主智能体与用户终端）。
     *
     * 子智能体与主智能体共用同一个工具实例，所以视图必须在调用时给定
     * （见 [executeWithSandbox]），实例字段只作缺省值。
     */
    private val sandbox: LinuxSandboxView = LinuxSandboxView.WORKSPACE_WRITABLE,
) : AgentModelClient.ToolExecutor, AutoCloseable {

    /**
     * 本次调用的资源视图覆盖。
     *
     * 工具实例与主智能体共享，视图只能按调用传：存在字段上会让并发的子智能体
     * 互相看到对方的视图。工具执行是同步的且在调用自己的线程上，线程局部能准确
     * 对应到发起调用的一方。
     */
    private val sandboxOverride = ThreadLocal<LinuxSandboxView?>()

    /** 本次调用实际生效的视图。 */
    private val activeSandbox: LinuxSandboxView
        get() = sandboxOverride.get() ?: sandbox

    /**
     * 用指定资源视图执行一次调用。
     *
     * 受限子智能体借它把自己的视图带进同一次调用：需要执行命令的工具（`run_code`）
     * 于是只能在本视图允许的范围内读写，约束由挂载强制，不依赖工具名单是否周全。
     */
    internal fun executeWithSandbox(
        toolCall: AgentModelClient.ToolCall,
        sandbox: LinuxSandboxView,
    ): AgentModelClient.ToolResult {
        sandboxOverride.set(sandbox)
        return try {
            execute(toolCall)
        } finally {
            sandboxOverride.remove()
        }
    }

    private val closed = AtomicBoolean(false)
    private val deviceController = RootShellDeviceController(logger, screenshotExcludedPackages, rootAvailable)
    private val rootCommandExecutor = BoundedRootCommandExecutor(logger, rootAvailable = rootAvailable)
    private val structuredDeviceTools = AgentStructuredDeviceTools(
        context = context,
        logger = logger,
        root = rootCommandExecutor,
        rootAvailable = rootAvailable,
    )
    private val imageTools = AgentImageTools(context, rootCommandExecutor, rootAvailable)
    private val terminalController = RootShellTerminalController(
        logger = logger,
        rootAvailable = rootAvailable,
        linuxRootfsPath = LinuxEnvironmentPaths.rootfsDir(context, LinuxDistribution.DEBIAN).absolutePath,
        linuxRootfsPathProvider = { environment ->
            environment.linuxDistribution?.let { distribution ->
                LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
            }
        },
        detachedSupervisor = DetachedTaskSupervisor(
            logger = logger,
            recordsFile = DetachedTaskSupervisor.defaultRecordsFile(context),
            linuxRootfsPath = LinuxEnvironmentPaths.rootfsDir(context, LinuxDistribution.DEBIAN).absolutePath,
            linuxRootfsPathProvider = { environment ->
                environment.linuxDistribution?.let { distribution ->
                    LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
                }
            },
            linuxSharedMountsProvider = { SharedFolderMounts.current() },
        ),
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
        selectedLinuxEnvironmentProvider = {
            LinuxEnvironmentSettingsRepository.current(context).terminalEnvironment
        },
    )
    /**
     * 在所选 Linux 发行版里跑一条命令，返回原始退出码与输出。
     *
     * worktree 生命周期靠它执行；环境跟用户选的一致，别写死某个发行版——
     * 用户没装 Debian 时写死会让写入模式直接不可用。
     */
    internal fun runLinuxCommandRaw(
        command: String,
        timeoutSeconds: Int,
        sandbox: LinuxSandboxView = activeSandbox,
    ): RawCommandResult {
        val environment = LinuxEnvironmentSettingsRepository.current(context).terminalEnvironment
        val result = terminalController.execRaw(
            command = command,
            cwd = null,
            environment = environment,
            timeoutSeconds = timeoutSeconds,
            sandbox = sandbox,
        )
        return RawCommandResult(
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
        )
    }

    /**
     * 在沙箱里执行代码，只把 stdout 与 stderr 带回上下文。
     *
     * 与 `terminal` 的区别在意图：终端是「跑一条命令看结果」，这里是「在沙箱里完成一段处理，
     * 只把结论带回来」。批量读取、过滤、计算都在沙箱内结束，避免把大段原文灌进上下文，
     * 一次调用即可替代多轮「读文件 → 再读 → 再算」的往返。
     */
    private fun runCode(args: JSONObject): String {
        val code = args.optString("code")
        if (code.isBlank()) throw InvalidToolArgumentException("code 不能为空")
        val language = args.optString("language").ifBlank { "python" }
        val timeoutSeconds = args.optInt("timeout_seconds", DEFAULT_CODE_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_CODE_TIMEOUT_SECONDS)
        val command = when (language) {
            "shell" -> "cd $CODE_WORKSPACE_DIR && $code"
            "python" ->
                "cd $CODE_WORKSPACE_DIR && python3 - <<'$CODE_HEREDOC_TAG'\n$code\n$CODE_HEREDOC_TAG"
            else -> throw InvalidToolArgumentException("language 只能是 python 或 shell")
        }
        val result = runLinuxCommandRaw(command, timeoutSeconds, activeSandbox)
        if (language == "python" && result.exitCode == SHELL_COMMAND_NOT_FOUND) {
            return errorResult(
                code = "PYTHON_UNAVAILABLE",
                message = "沙箱里没有 python3；请改用 language=shell，或先在 Linux 工具环境安装 Python",
            )
        }
        return JSONObject()
            .put("ok", result.exitCode == 0)
            .put("exit_code", result.exitCode)
            .put("stdout", result.stdout.take(FileToolLimits.MAX_OUTPUT_CHARS))
            .put("stderr", result.stderr.take(FileToolLimits.MAX_ERROR_CHARS))
            .toString()
    }

    /** 沙箱默认工作目录；与 Linux 环境约定的工作目录一致。 */
    private val CODE_WORKSPACE_DIR = "/workspace"

    private val DEFAULT_CODE_TIMEOUT_SECONDS = 60
    private val MAX_CODE_TIMEOUT_SECONDS = 600

    /** heredoc 结束标记；用带项目前缀的固定串，避免与代码内容撞行。 */
    private val CODE_HEREDOC_TAG = "STA_RUN_CODE_EOF"

    /** shell 找不到命令时的退出码（python3 缺失即此值）。 */
    private val SHELL_COMMAND_NOT_FOUND = 127

    private val publishedObservation = AtomicReference(PublishedObservation())
    private val runAvailableSkillIds = runAvailableSkillIds
        .mapTo(mutableSetOf(), SkillParser::normalizeSkillLookup)
    private val mutatedSkillIds = ConcurrentHashMap.newKeySet<String>()
    private val skillTreeMutationUncertain = AtomicBoolean(false)
    private val pendingSkillConflict = AtomicReference(pendingSkillConflict)
    private val inspectedGitHubSnapshots =
        ConcurrentHashMap<String, GitHubInspectionSnapshot>()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        publishedObservation.set(PublishedObservation())
        AgentBrowserSession.interruptAgentAction(browserRunId)
        terminalController.interruptAll()
        rootCommandExecutor.close()
        githubSkillSource?.close()
        inspectedGitHubSnapshots.clear()
    }

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult =
        runCatching {
            val args = JSONObject(toolCall.argumentsJson.ifBlank { "{}" })
            if (AgentToolRequirements.find(toolCall.name) != null &&
                AgentToolRequirements.rootDenied(toolCall.name, args, rootAvailable())
            ) {
                return@runCatching textResult(errorResult("ROOT_REQUIRED", "此操作需要 Root 授权，本次未执行"))
            }
            deviceToolPermissionError(toolCall.name)?.let { return@runCatching it }
            credentialPathError(toolCall.name, args)?.let { return@runCatching it }
            memoryToolPermissionError(toolCall.name)?.let { return@runCatching it }
            when (val decision = beforeToolExecution(toolCall.name)) {
                ToolExecutionDecision.Allow -> Unit
                is ToolExecutionDecision.Reject -> {
                    if (decision.code.startsWith("ACCESSIBILITY_")) publishedObservation.set(PublishedObservation())
                    return@runCatching textResult(
                        errorResult(
                            code = decision.code,
                            message = decision.message,
                        ),
                    )
                }
            }
            when (toolCall.name) {
                "get_current_context" -> textResult(DeviceContextTool.current(context))
                "search_apps" -> textResult(searchApps(args))
                "launch_app" -> textResult(launchApp(args))
                "open_uri" -> textResult(openUri(args))
                "browser_use" -> browserUse(args, toolCall.id)
                "observe_screen" -> observeScreen(args)
                "run_sequence" -> textResult(runSequence(args))
                "save_flow" -> textResult(saveFlow(args))
                "use_flow" -> textResult(useFlow(args))
                "tap" -> textResult(tap(args))
                "tap_area" -> textResult(tapArea(args))
                "tap_element" -> textResult(tapElement(args))
                "long_press" -> textResult(longPress(args))
                "long_press_element" -> textResult(longPressElement(args))
                "swipe" -> textResult(swipe(args))
                "drag" -> textResult(drag(args))
                "scroll" -> textResult(deviceController.scroll(args.optString("direction")))
                "scroll_element" -> textResult(scrollElement(args))
                "input_text" -> textResult(inputText(args))
                "replace_text" -> textResult(replaceText(args))
                "clear_text" -> textResult(clearText(args))
                "set_clipboard" -> textResult(setClipboard(args))
                "get_clipboard" -> textResult(getClipboard())
                "paste_text" -> textResult(pasteText(args))
                "press_key" -> textResult(deviceController.pressKey(args.optString("button")))
                "wait" -> textResult(deviceController.waitMs(args.optInt("duration_ms", 1_000)))
                "wait_for_text" -> textResult(waitForText(args))
                "wait_for_package" -> textResult(waitForPackage(args))
                "open_system_panel" -> textResult(deviceController.openSystemPanel(args.optString("panel")))
                in DEVICE_TOOL_NAMES ->
                    structuredDeviceTools.execute(toolCall.name, args)
                        ?: textResult(errorResult("UNKNOWN_TOOL", "未知设备工具"))
                "read_image" -> fileVisionTool { imageTools.readImage(args) }
                "terminal" -> textResult(terminalTool { terminal(args) })
                "run_command" -> textResult(terminalTool { runCommand(args) })
                "read_file" -> textResult(terminalTool { readFile(args) })
                "read_files" -> textResult(terminalTool { readFiles(args) })
                "write_file" -> textResult(terminalTool { writeFile(args) })
                "edit_file" -> textResult(terminalTool { editFile(args) })
                "edit_files" -> textResult(terminalTool { editFiles(args) })
                "search_code" -> textResult(terminalTool { searchCode(args) })
                AgentCodeExecutionToolCatalog.RUN_CODE -> textResult(terminalTool { runCode(args) })
                "list_directory" -> textResult(terminalTool { listDirectory(args) })
                "find_files" -> textResult(terminalTool { findFiles(args) })
                "task_plan" -> textResult(taskPlan(args))
                "submit_plan" -> textResult(submitPlan(args))
                "world_recall" -> textResult(worldRecall(args))
                "world_trace" -> textResult(worldTrace(args))
                AgentRunStatsToolCatalog.NAME -> textResult(
                    runStatsSummary?.invoke()
                        ?: errorResult("RUN_STATS_UNAVAILABLE", "本次 run 没有可用的度量数据"),
                )
                "memory_get" -> textResult(memoryGet(args))
                "memory_write" -> textResult(memoryWrite(args))
                "skills_list" -> textResult(skillsList(args))
                "skills_read" -> textResult(skillsRead(args))
                "skills_read_resource" -> textResult(skillsReadResource(args))
                "skills_list_curated" -> textResult(skillsListCurated())
                "skills_inspect_github" -> textResult(skillsInspectGitHub(args))
                "skills_install_from_github" -> textResult(skillsInstallFromGitHub(args))
                "skills_run" -> textResult(terminalTool { skillsRun(args) })
                AgentSubAgentToolCatalog.DELEGATE -> {
                    val used = subAgentInvocations.incrementAndGet()
                    if (used > SUB_AGENT_INVOCATION_LIMIT) {
                        textResult(
                            errorResult(
                                code = "SUB_AGENT_LIMIT_REACHED",
                                message = "本次运行最多委派 $SUB_AGENT_INVOCATION_LIMIT 次子智能体，已经用完；" +
                                    "请自己继续处理，或先汇总已有结论",
                            ),
                        )
                    } else {
                        textResult(delegate(args, toolCall.id))
                    }
                }
                else -> textResult(
                    errorResult(
                        code = "UNKNOWN_TOOL",
                        message = "未知工具：${toolCall.name}"
                    )
                )
            }
        }.getOrElse { throwable ->
            textResult(
                errorResult(
                    code = when (throwable) {
                        is InvalidToolArgumentException -> "INVALID_ARGUMENT"
                        is DeviceControlUnavailableException -> "ACCESSIBILITY_UNAVAILABLE"
                        else -> "TOOL_ERROR"
                    },
                    message = throwable.message ?: throwable.javaClass.simpleName
                )
            )
        }.let { result ->
            val sensitive = result.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)
            val content = CredentialRedactor.redact(result.content)
            when {
                content == result.content && sensitive == result.sensitive -> result
                else -> result.copy(content = content, sensitive = sensitive)
            }
        }.also { result -> recordItemActivity(toolCall, result) }

    private fun deviceToolPermissionError(
        toolName: String,
    ): AgentModelClient.ToolResult? {
        val error = when {
            toolName in DEVICE_DIRECT_TOOL_NAMES && !deviceDirectToolsEnabled() ->
                "DEVICE_DIRECT_TOOLS_DISABLED" to "请先启用设备直达工具"
            toolName in DEVICE_SENSITIVE_READ_TOOL_NAMES && !deviceSensitiveReadToolsEnabled() ->
                "DEVICE_SENSITIVE_READ_TOOLS_DISABLED" to "请先允许读取敏感设备信息"
            toolName in DEVICE_SENSITIVE_ACTION_TOOL_NAMES && !deviceSensitiveActionToolsEnabled() ->
                "DEVICE_SENSITIVE_ACTION_TOOLS_DISABLED" to "请先允许敏感设备操作"
            else -> null
        } ?: return null
        return AgentModelClient.ToolResult(
            content = errorResult(error.first, error.second),
            sensitive = toolName in DEVICE_SENSITIVE_READ_TOOL_NAMES ||
                toolName in DEVICE_SENSITIVE_ACTION_TOOL_NAMES,
        )
    }

    /**
     * 文件类工具的凭据路径拦截。
     *
     * provider 密钥以明文存在 App 私有数据库里，而文件工具在 Root 下可读，因此这里在调用前
     * 直接拒绝，不回显路径内容。终端命令是本层的已知缺口，真正的隔离需要把密钥移出可读文件。
     */
    private fun credentialPathError(toolName: String, args: JSONObject): AgentModelClient.ToolResult? {
        if (toolName !in CREDENTIAL_PATH_TOOL_NAMES) return null
        val path = args.optString("path").ifBlank { args.optString("cwd") }
        if (path.isBlank()) return null
        val dataDir = runCatching { context.applicationContext.dataDir.absolutePath }.getOrNull() ?: return null
        if (!CredentialBoundary.deniesLoosely(dataDir, path)) return null
        return textResult(errorResult("CREDENTIAL_PATH_BLOCKED", CredentialBoundary.message()))
    }

    private fun terminalTool(block: () -> String): String {
        if (!terminalToolsEnabled()) {
            return errorResult("TERMINAL_TOOLS_DISABLED", "请先启用终端/文件工具")
        }
        return block()
    }

    private fun fileVisionTool(block: () -> AgentModelClient.ToolResult): AgentModelClient.ToolResult {
        if (!terminalToolsEnabled()) {
            return textResult(errorResult("TERMINAL_TOOLS_DISABLED", "请先启用终端/文件工具"))
        }
        return block()
    }

    private fun memoryToolPermissionError(toolName: String): AgentModelClient.ToolResult? {
        if (toolName == "memory_write" && !memoryWritable) {
            return AgentModelClient.ToolResult(
                content = errorResult("REAL_MEMORY_READ_ONLY", "角色会话的现实记忆只读；剧情请使用角色记忆工具"),
                sensitive = true,
            )
        }
        if (toolName !in MEMORY_TOOL_NAMES || memoryToolsEnabled()) return null
        return AgentModelClient.ToolResult(
            content = errorResult("MEMORY_DISABLED", "记忆已在设置中关闭"),
            sensitive = true,
        )
    }

    /** 当前 run 的任务清单；只在本次 run 内有效，事件把它同步给界面。 */
    private var currentTaskPlan: String = ""

    /** 本次运行已经委派过多少次子智能体；护栏值见 SUB_AGENT_INVOCATION_LIMIT。 */
    private val subAgentInvocations = java.util.concurrent.atomic.AtomicInteger(0)

    /** 计划项的执行痕迹，由本层自动采集，不占模型的输出预算。 */
    private class ItemActivity {
        var startedAt: Long = 0L
        var finishedAt: Long = 0L
        var calls: Int = 0
        var failure: String? = null
    }

    private val itemActivities = mutableMapOf<String, ItemActivity>()
    private var activeItemId: String? = null

    /**
     * 维护任务清单：每次提交的是完整快照，校验不通过时不改动已保存的清单。
     */
    /** 记录当前计划项的执行痕迹：工具调用次数、最近一次失败原因。 */
    private fun recordItemActivity(
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ) {
        val itemId = activeItemId ?: return
        if (toolCall.name == "task_plan") return
        val activity = itemActivities.getOrPut(itemId) { ItemActivity() }
        activity.calls += 1
        val payload = runCatching { JSONObject(result.content) }.getOrNull() ?: return
        if (payload.optBoolean("ok", true)) return
        val code = payload.optString("code").ifBlank { payload.optString("error") }
        val message = payload.optString("message").ifBlank { payload.optString("stderr") }
        activity.failure = listOf(code, message)
            .filter { it.isNotBlank() }
            .joinToString("：")
            .take(160)
            .ifBlank { "工具执行失败" }
    }

    /** 把采集到的痕迹写进清单 JSON，界面据此显示每步的代价与失败原因。 */
    private fun decorateActivity(target: JSONObject, activity: ItemActivity?, status: String) {
        val stats = activity ?: return
        if (stats.calls > 0) target.put("tool_calls", stats.calls)
        val end = if (status == "in_progress") System.currentTimeMillis() else stats.finishedAt
        if (stats.startedAt > 0L && end > stats.startedAt) {
            target.put("elapsed_ms", end - stats.startedAt)
        }
        if (status != "completed") {
            stats.failure?.let { target.put("failure", it) }
        }
    }

    private fun taskPlan(args: JSONObject): String {
        val raw = args.optJSONArray("todos")
            ?: return errorResult("INVALID_ARGUMENT", "todos 必须是数组")
        // 两阶段：先把整份清单校验完，再动任何状态。否则校验失败时 activeItemId 已经被改写，
        // 后续普通工具的调用痕迹会记到清单里不存在的项上，且同一 id 再次以 in_progress 出现时
        // 会因为 activeItemId 相等而跳过 startedAt 重置，快照里的 elapsed_ms 用上次的旧时间戳。
        val parsed = mutableListOf<Triple<String, String, String>>()
        val seen = mutableSetOf<String>()
        var inProgress = 0
        for (index in 0 until raw.length()) {
            val item = raw.optJSONObject(index)
                ?: return errorResult("INVALID_ARGUMENT", "第 ${index + 1} 项不是对象")
            val id = item.optString("id").trim()
            val content = item.optString("content").trim()
            val status = item.optString("status").trim().ifBlank { "pending" }
            if (id.isEmpty()) return errorResult("INVALID_ARGUMENT", "第 ${index + 1} 项缺少 id")
            if (!seen.add(id)) return errorResult("INVALID_ARGUMENT", "id 重复：$id")
            if (content.isEmpty()) return errorResult("INVALID_ARGUMENT", "第 ${index + 1} 项缺少 content")
            if (status !in TASK_PLAN_STATUSES) {
                return errorResult("INVALID_ARGUMENT", "status 只能是 pending、in_progress 或 completed，收到：$status")
            }
            if (status == "in_progress") inProgress++
            parsed += Triple(id, content, status)
        }
        if (inProgress > 1) {
            return errorResult("INVALID_ARGUMENT", "同一时间只能有一项 in_progress，当前有 $inProgress 项")
        }
        val normalized = JSONArray()
        for ((id, content, status) in parsed) {
            val activity = itemActivities.getOrPut(id) { ItemActivity() }
            if (status == "in_progress" && activeItemId != id) {
                activeItemId = id
                activity.startedAt = System.currentTimeMillis()
                activity.finishedAt = 0L
                activity.calls = 0
                activity.failure = null
            }
            if (status == "completed" && activity.finishedAt == 0L) {
                activity.finishedAt = System.currentTimeMillis()
                if (activeItemId == id) activeItemId = null
            }
            normalized.put(
                JSONObject()
                    .put("id", id)
                    .put("content", content)
                    .put("status", status)
                    .also { decorateActivity(it, activity, status) },
            )
        }
        currentTaskPlan = normalized.toString()
        onTaskPlanUpdated?.invoke(currentTaskPlan)
        return JSONObject()
            .put("ok", true)
            .put("tool", "task_plan")
            .put("items", normalized.length())
            .put("plan", renderTaskPlan(normalized))
            .toString()
    }

    /**
     * 检索观测库里的历史结论。
     *
     * 这是「世界」的读取端：子智能体的结论会落进观测库，后续 run 需要时按关键词取回。
     * 做成工具而不是自动全量注入，依据是 Anthropic 的 context engineering 结论：
     * 记忆应当 just-in-time 按需取（维护轻量引用、运行时加载），而不是预先塞满上下文——
     * 全量注入会挤占注意力预算，而模型真正需要的往往只是其中一小部分。
     *
     * 返回的每条都带「多久之前」与新鲜度标注：依赖文件已变更的结论会被明写出来，
     * 避免历史结论被当作当下事实使用。
     */
    private fun worldRecall(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isBlank()) {
            return errorResult("INVALID_ARGUMENT", "query 不能为空：请给出要检索的关键词。")
        }
        val limit = args.optInt("limit", WorldKnowledgeStore.DEFAULT_SEARCH_LIMIT)
            .coerceIn(1, MAX_WORLD_RECALL_LIMIT)
        val now = System.currentTimeMillis()
        val entries = WorldKnowledgeStore.search(
            context = requireContext(),
            query = query,
            limit = limit,
            readContent = ::readWorkspaceFile,
            nowMs = now,
            // 带上当前工作区坐标：同空间的结论会被排在前面（依据见 WorldScore）。
            // 用 DEFAULT_FILE_WORKSPACE 而不是另设常量：它与文件工具的工作区是同一个
            // 概念，两处若各写一份，将来改工作区会漏改一处，检索排序就会与文件工具脱节。
            currentScope = DEFAULT_FILE_WORKSPACE,
        )
        if (entries.isEmpty()) {
            return withWorldHealth(
                JSONObject()
                    .put("ok", true)
                    .put("tool", "world_recall")
                    .put("count", 0)
                    .put("message", "观测库里没有与「$query」相关的历史结论。"),
            )
        }
        // 取用即投票：本次检索命中的条目记一次 helpful。痕迹的价值由此自己长出来，
        // 不需要任何外部判定——被取用的浮上来，没人取用的自然沉下去。
        WorldKnowledgeStore.markHelpful(requireContext(), entries.map { it.id })
        return withWorldHealth(
            JSONObject()
                .put("ok", true)
                .put("tool", "world_recall")
                .put("count", entries.size)
                .put("results", WorldKnowledgeLogic.formatRecall(entries, now, DEFAULT_FILE_WORKSPACE)),
        )
    }

    /**
     * 给观测层的返回补一行健康警告，再转成字符串。
     *
     * 本层读写失败一律降级为「没有数据」，那看起来是一次成功查询，实际可能是「世界坏了」。
     * 按 Google SRE 的四个黄金信号，这属于必须盯住的隐式错误
     * （"an HTTP 200 success response, but coupled with the wrong content"）：
     * 查询成功了，但它背后少了一部分数据。把警告放进返回里，模型才知道结果可能不完整。
     */
    private fun withWorldHealth(result: JSONObject): String {
        WorldHealth.warningLine()?.let { warning -> result.put("warning", warning) }
        return result.toString()
    }

    /** 读工作区文件，用于校验历史结论的依赖是否已变更；读不到返回 null。 */
    private fun readWorkspaceFile(path: String): String? =
        runCatching { java.io.File(path).takeIf { it.isFile }?.readText() }.getOrNull()

    /**
     * 查历史委派树。
     *
     * 与 world_recall 的分工：world_recall 查「结论」，这里查「现场」——某次委派到底看了什么、
     * 子智能体之间怎么分层。摘要之外的内容（完整消息流）按需取：给了 node 参数就返回那一个
     * 节点的正文，不给就只返回树的结构。
     *
     * 不给 trace 参数时列最近几次委派，方便先找到要看哪一棵。
     */
    private fun worldTrace(args: JSONObject): String {
        val context = requireContext()
        val nodeId = args.optString("node").trim()
        if (nodeId.isNotEmpty()) {
            val nodes = WorldTraceStore.recent(context, MAX_TRACE_LIST)
            val target = nodes.firstOrNull { it.id == nodeId }
            val content = target?.let { WorldTraceStore.contentOf(context, it) }
            if (content.isNullOrBlank()) {
                return withWorldHealth(
                    JSONObject()
                        .put("ok", true)
                        .put("tool", "world_trace")
                        .put("message", "没有找到节点「$nodeId」的正文。"),
                )
            }
            return withWorldHealth(
                JSONObject()
                    .put("ok", true)
                    .put("tool", "world_trace")
                    .put("node", nodeId)
                    .put("content", content.take(MAX_TRACE_CONTENT_CHARS)),
            )
        }

        val traceId = args.optString("trace").trim()
        val nodes = if (traceId.isNotEmpty()) {
            // 指定了 trace 就是精确定位：要的是那一棵树本身，不该重排。
            WorldTraceStore.tree(context, traceId)
        } else {
            // 不给参数时列最近几次委派，按空间距离重排——「上次在这个项目里派过谁」
            // 比「上次随便哪次派过谁」更相关。
            WorldTraceStore.recentForScope(context, DEFAULT_FILE_WORKSPACE, MAX_TRACE_LIST)
        }
        if (nodes.isEmpty()) {
            return withWorldHealth(
                JSONObject()
                    .put("ok", true)
                    .put("tool", "world_trace")
                    .put("count", 0)
                    .put("message", "观测库里没有委派记录。"),
            )
        }
        return withWorldHealth(
            JSONObject()
                .put("ok", true)
                .put("tool", "world_trace")
                .put("count", nodes.size)
                .put("tree", WorldTraceStore.renderTree(nodes, System.currentTimeMillis()))
                .put("hint", "需要某个节点的完整过程时，用 node 参数传它的 id。"),
        )
    }

    /**
     * 提交方案：只记录，不阻塞、也不结束本轮。
     *
     * 全部校验通过才落状态——方案卡片的「按此执行」会直接用 steps 初始化任务清单，允许半成品
     * 通过等于把脏数据塞进清单。方案本身只是一份给用户看的记录：提交后写类工具照常可用，
     * 用户要么点「按此执行」（用 steps 建清单），要么直接给新指令。
     */
    private fun submitPlan(args: JSONObject): String {
        val title = args.optString("title").trim()
        if (title.isEmpty()) return errorResult("INVALID_ARGUMENT", "title 不能为空")
        val digest = args.optString("digest").trim()
        if (digest.isEmpty()) {
            return errorResult(
                "INVALID_ARGUMENT",
                "digest 不能为空：它是后续每轮注入给模型的方向摘要（目标、约束、关键决策）；" +
                    "正文可以很长，digest 必须精简。",
            )
        }
        val content = args.optString("content").trim()
        if (content.isEmpty()) return errorResult("INVALID_ARGUMENT", "content 不能为空")

        val rawSteps = args.optJSONArray("steps")
            ?: return errorResult("INVALID_ARGUMENT", "steps 必须是数组")
        if (rawSteps.length() == 0) {
            return errorResult(
                "INVALID_ARGUMENT",
                "steps 至少要有一步：用户点「按此执行」时会用它们初始化任务清单",
            )
        }
        val steps = JSONArray()
        val seen = mutableSetOf<String>()
        for (index in 0 until rawSteps.length()) {
            val item = rawSteps.optJSONObject(index)
                ?: return errorResult("INVALID_ARGUMENT", "第 ${index + 1} 步不是对象")
            val id = item.optString("id").trim()
            val stepContent = item.optString("content").trim()
            if (id.isEmpty()) return errorResult("INVALID_ARGUMENT", "第 ${index + 1} 步缺少 id")
            if (!seen.add(id)) return errorResult("INVALID_ARGUMENT", "步骤 id 重复：$id")
            if (stepContent.isEmpty()) {
                return errorResult("INVALID_ARGUMENT", "第 ${index + 1} 步缺少 content")
            }
            steps.put(JSONObject().put("id", id).put("content", stepContent))
        }

        val plan = JSONObject()
            .put("title", title)
            .put("digest", digest)
            .put("content", content)
            .put("steps", steps)
            .put("status", "pending")
        args.optJSONArray("alternatives")?.let { raw ->
            val alternatives = JSONArray()
            for (index in 0 until raw.length()) {
                val item = raw.optJSONObject(index) ?: continue
                val alternativeTitle = item.optString("title").trim()
                if (alternativeTitle.isEmpty()) continue
                alternatives.put(
                    JSONObject()
                        .put("title", alternativeTitle)
                        .put("summary", item.optString("summary").trim())
                        .put("tradeoff", item.optString("tradeoff").trim()),
                )
            }
            if (alternatives.length() > 0) plan.put("alternatives", alternatives)
        }

        val snapshot = plan.toString()
        onPlanUpdated?.invoke(snapshot)
        return JSONObject()
            .put("ok", true)
            .put("tool", "submit_plan")
            .put("title", title)
            .put("steps", steps.length())
            .put(
                "message",
                "方案已记录并显示给用户。用户可能点「按此执行」（用 steps 建清单），" +
                    "也可能直接给新指令；不要当成已批准，也不要把提交方案当成干活的终点。",
            )
            .toString()
    }

    private fun renderTaskPlan(todos: JSONArray): String =
        (0 until todos.length()).joinToString("\n") { index ->
            val item = todos.getJSONObject(index)
            val marker = when (item.getString("status")) {
                "completed" -> "[x]"
                "in_progress" -> "[>]"
                else -> "[ ]"
            }
            "$marker ${item.getString("id")} - ${item.getString("content")}"
        }

    private fun memoryGet(args: JSONObject): String = try {
        val result = AgentMemoryRepository.read(
            query = args.optString("query").takeIf(String::isNotBlank),
            startLine = args.optInt("start_line", 1),
            maxChars = args.optInt("max_chars", 12_000),
        )
        JSONObject()
            .put("ok", true)
            .put("revision", result.snapshot.revision)
            .put("bytes", result.snapshot.byteSize)
            .put("line_count", result.snapshot.lineCount)
            .put("start_line", result.startLine ?: JSONObject.NULL)
            .put("end_line", result.endLine ?: JSONObject.NULL)
            .put("matched_lines", result.matchedLines)
            .put("has_more", result.hasMore)
            .put("content", result.content)
            .toString()
    } catch (failure: AgentMemoryException) {
        errorResult(failure.code, failure.message ?: "记忆读取失败")
    }

    private fun memoryWrite(args: JSONObject): String {
        val request = when (val parsed = AgentMemoryWriteRequest.parse(args)) {
            is AgentMemoryWriteRequest.Parsed.Invalid -> return errorResult("INVALID_MEMORY_ARGUMENTS", parsed.message)
            is AgentMemoryWriteRequest.Parsed.Mutation -> parsed
        }
        val outcome = try {
            runMemoryMutation(request.mutation) { mutation -> AgentMemoryRepository.mutate(mutation) }
        } catch (failure: AgentMemoryException) {
            return errorResult(failure.code, failure.message ?: "记忆写入失败")
        }
        return when (outcome.result) {
            is AgentMemoryWriteResult.Success ->
                AgentMemoryWritePayload.success(outcome, request.mode, request.revisionIgnored).toString()
            is AgentMemoryWriteResult.Conflict -> AgentMemoryWritePayload.conflict(outcome).toString()
        }
    }

    private fun browserUse(args: JSONObject, toolCallId: String): AgentModelClient.ToolResult {
        if (!browserToolsEnabled()) {
            return textResult(errorResult("BROWSER_TOOLS_DISABLED", "请先启用网页浏览工具"))
        }
        val result = AgentBrowserSession.execute(
            context = context,
            args = args,
            runId = browserRunId,
            toolCallId = toolCallId,
        )
        return AgentModelClient.ToolResult(
            content = result.content,
            images = result.images.map { image ->
                AgentModelClient.ModelImage(
                    reference = image.dataUrl,
                    mimeType = image.mimeType,
                    bytes = image.bytes,
                    width = image.width,
                    height = image.height,
                    source = "agent_browser",
                )
            },
        )
    }

    private fun observeScreen(args: JSONObject): AgentModelClient.ToolResult {
        publishedObservation.set(PublishedObservation())
        val startedAt = SystemClock.elapsedRealtime()
        val options = AgentScreenObservationContract.resolve(args)
        val observation = screenObservationProvider?.invoke(options)
            ?: deviceController.observe(
                includeScreenshot = options.includeScreenshot,
                includeUiTree = options.includeUiTree,
                maxNodes = options.maxNodes,
            )
        publishedObservation.set(
            PublishedObservation(
                elements = observation.elementObservation,
                coordinateSpace = observation.coordinateSpace,
            ),
        )
        logger.debug {
            "Agent local tool action=observe_screen outcome=completed " +
                "observation=${observation.elementObservation?.id} " +
                "nodes=${observation.elementObservation?.nodes?.size ?: 0} " +
                "image=${observation.image?.bytes ?: 0} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} " +
                "coordinate=${observation.coordinateSpace?.summary()}"
        }
        return AgentModelClient.ToolResult(
            content = observation.content,
            images = listOfNotNull(observation.image)
        )
    }

    private fun runSequence(args: JSONObject): String {
        val steps = args.optJSONArray("steps") ?: return errorResult("INVALID_ARGUMENT", "steps 不能为空")
        if (steps.length() == 0 || steps.length() > MAX_SEQUENCE_STEPS) {
            return errorResult("INVALID_ARGUMENT", "steps 数量需在 1..$MAX_SEQUENCE_STEPS 之间")
        }
        val executed = JSONArray()
        for (index in 0 until steps.length()) {
            val step = steps.optJSONObject(index)
                ?: return errorResult("INVALID_ARGUMENT", "steps[$index] 必须是对象")
            val action = step.optString("action").ifBlank {
                return errorResult("INVALID_ARGUMENT", "steps[$index] 缺少 action")
            }
            val stepArgs = JSONObject()
            step.keys().forEach { key -> stepArgs.put(key, step.get(key)) }
            stepArgs.remove("action")
            val outcome = runCatching { dispatchSequenceStep(action, stepArgs) }
                .getOrElse { throwable ->
                    errorResult("SEQUENCE_STEP_FAILED", "第 $index 步执行异常：${throwable.javaClass.simpleName}")
                }
                .let { first -> settleOrRetrySequenceStep(action, stepArgs, first) }
            val ok = !outcome.startsWith("{\"ok\":false")
            executed.put(
                JSONObject()
                    .put("index", index)
                    .put("action", action)
                    .put("ok", ok)
                    .put("result", outcome.take(SEQUENCE_RESULT_CHARS))
            )
            if (!ok) {
                return JSONObject()
                    .put("ok", false)
                    .put("code", "SEQUENCE_STOPPED")
                    .put("failed_step", index)
                    .put("failed_action", action)
                    .put("steps", executed)
                    .put("current_screen", currentScreenSummary())
                    .toString()
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("steps", executed)
            .put("current_screen", currentScreenSummary())
            .toString()
    }

    private fun sequenceTapText(args: JSONObject): String {
        val text = args.optString("text")
        if (text.isBlank()) return errorResult("INVALID_ARGUMENT", "tap_text 需要 text 参数")
        val observation = runCatching {
            deviceController.observe(includeScreenshot = false, includeUiTree = true, maxNodes = 120)
        }.getOrElse { return errorResult("OBSERVATION_UNAVAILABLE", "无法获取当前屏幕") }
        val element = observation.elementObservation
            ?: return errorResult("OBSERVATION_UNAVAILABLE", "无法获取当前屏幕节点")
        val match = element.nodes.firstOrNull { it.text == text }
            ?: element.nodes.firstOrNull { it.desc == text }
            ?: element.nodes.firstOrNull { it.text.isNotBlank() && it.text.contains(text) }
            ?: return errorResult("NODE_NOT_FOUND", "屏幕中没有找到文本「$text」")
        publishedObservation.set(
            PublishedObservation(elements = element, coordinateSpace = observation.coordinateSpace)
        )
        return tapElement(
            JSONObject()
                .put("index", match.index)
                .put("observation_id", element.id)
        )
    }

    private fun flowsDir(): java.io.File =
        java.io.File(context.filesDir, "agent_flows").apply { mkdirs() }

    private fun saveFlow(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isBlank() || name.length > MAX_FLOW_NAME_CHARS ||
            !name.all { it.isLetterOrDigit() || it == '_' || it == '-' }
        ) {
            return errorResult("INVALID_ARGUMENT", "name 必填，仅允许字母数字 _-，不超过 $MAX_FLOW_NAME_CHARS 字符")
        }
        val steps = args.optJSONArray("steps") ?: return errorResult("INVALID_ARGUMENT", "steps 不能为空")
        if (steps.length() == 0 || steps.length() > MAX_SEQUENCE_STEPS) {
            return errorResult("INVALID_ARGUMENT", "steps 数量需在 1..$MAX_SEQUENCE_STEPS 之间")
        }
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: return errorResult("INVALID_ARGUMENT", "steps[$i] 必须是对象")
            val action = step.optString("action")
            if (action !in FLOW_SAFE_ACTIONS) {
                return errorResult(
                    "INVALID_ARGUMENT",
                    "流程只允许语义化动作：tap_text/input/replace/clear/press/wait/wait_text/wait_package/swipe/scroll",
                )
            }
            if (step.has("index") || step.has("observation_id")) {
                return errorResult(
                    "INVALID_ARGUMENT",
                    "steps[$i] 不能包含 index/observation_id，流程里请用 tap_text 按文本点击",
                )
            }
        }
        val flow = JSONObject()
            .put("name", name)
            .put("description", args.optString("description"))
            .put("created_at", System.currentTimeMillis())
            .put("steps", steps)
        runCatching { java.io.File(flowsDir(), "$name.json").writeText(flow.toString()) }
            .onFailure { throwable ->
                return errorResult("FLOW_SAVE_FAILED", "保存流程失败：${throwable.javaClass.simpleName}")
            }
        return "已保存流程 $name（${steps.length()} 步）"
    }

    private fun useFlow(args: JSONObject): String {
        val name = args.optString("name").trim()
        // 与 save_flow 用同一套白名单：只判空会让 "../../x" 这类名字拼出 flowsDir 之外的路径，
        // 把任意可读的 json 当成 steps 执行。
        if (name.isBlank() || name.length > MAX_FLOW_NAME_CHARS ||
            !name.all { it.isLetterOrDigit() || it == '_' || it == '-' }
        ) {
            return errorResult("INVALID_ARGUMENT", "name 必填，仅允许字母数字 _-，不超过 $MAX_FLOW_NAME_CHARS 字符")
        }
        val file = java.io.File(flowsDir(), "$name.json")
        if (!file.exists()) return errorResult("FLOW_NOT_FOUND", "未找到流程 $name，可先用 save_flow 保存")
        val flow = runCatching { JSONObject(file.readText()) }
            .getOrElse { return errorResult("FLOW_LOAD_FAILED", "流程文件损坏") }
        val steps = flow.optJSONArray("steps") ?: return errorResult("FLOW_LOAD_FAILED", "流程缺少 steps")
        return runSequence(JSONObject().put("steps", steps))
    }

    /**
     * 序列内相邻步骤间隔很短，这里补两件事：点击类动作后等界面稳定再走下一步；输入框在被点击后
     * 要等控件报出光标位置，紧接着写入会因没有可靠光标被拒，此时稍等后重试一次同样的写入。
     */
    private fun settleOrRetrySequenceStep(
        action: String,
        args: JSONObject,
        first: String,
    ): String {
        if (first.isOkJson()) {
            when (action) {
                "tap", "tap_area", "tap_element", "tap_text", "long_press", "press" ->
                    runCatching { Thread.sleep(SEQUENCE_SETTLE_AFTER_TAP_MS) }
                "swipe", "drag", "scroll" ->
                    runCatching { Thread.sleep(SEQUENCE_SETTLE_AFTER_SCROLL_MS) }
            }
            return first
        }
        if (action != "input" || !first.contains(SEQUENCE_NO_SELECTION_CODE)) return first
        runCatching { Thread.sleep(SEQUENCE_INPUT_RETRY_DELAY_MS) }
        return runCatching { dispatchSequenceStep(action, args) }.getOrElse { first }
    }

    private fun dispatchSequenceStep(action: String, args: JSONObject): String = when (action) {
        "tap" -> tap(args)
        "tap_area" -> tapArea(args)
        "tap_element" -> tapElement(args)
        "tap_text" -> sequenceTapText(args)
        "long_press" -> longPress(args)
        "swipe" -> swipe(args)
        "drag" -> drag(args)
        "scroll" -> deviceController.scroll(args.optString("direction"))
        "input" -> inputText(args)
        "replace" -> replaceText(args)
        "clear" -> clearText(args)
        "press" -> deviceController.pressKey(args.optString("button"))
        "wait" -> deviceController.waitMs(args.optInt("duration_ms", 1_000))
        "wait_text" -> waitForText(args)
        "wait_package" -> waitForPackage(args)
        else -> errorResult("INVALID_ARGUMENT", "steps 中不支持的 action=$action")
    }

    private fun currentScreenSummary(): String = runCatching {
        val observation = deviceController.observe(
            includeScreenshot = false,
            includeUiTree = true,
            maxNodes = 60,
        )
        val element = observation.elementObservation
        val nodes = element?.nodes.orEmpty()
        JSONObject()
            .put("package", element?.packageName.orEmpty())
            .put("node_count", nodes.size)
            .put(
                "interactive",
                JSONArray().also { array ->
                    nodes.filter { it.clickable || it.editable || it.scrollable }
                        .take(40)
                        .forEach { node ->
                            array.put(
                                JSONObject()
                                    .put("index", node.index)
                                    .put("text", node.text)
                                    .put("desc", node.desc)
                                    .put("clickable", node.clickable)
                                    .put("editable", node.editable)
                                    .put("scrollable", node.scrollable)
                            )
                        }
                }
            )
            .toString()
    }.getOrElse { "" }

    private fun tap(args: JSONObject): String {
        val point = convertPoint(
            x = args.optInt("x"),
            y = args.optInt("y"),
            coordinateSpace = args.optString("coordinate_space")
        )
        AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
        showTap(point.x, point.y)
        return deviceController.tap(point.x, point.y)
    }

    private fun tapArea(args: JSONObject): String {
        val x1 = args.optInt("x1")
        val y1 = args.optInt("y1")
        val x2 = args.optInt("x2")
        val y2 = args.optInt("y2")
        val coordinateSpace = args.optString("coordinate_space")
        val first = convertPoint(x1, y1, coordinateSpace)
        val second = convertPoint(x2, y2, coordinateSpace)
        val point = ScreenPoint(
            x = ((first.x.toLong() + second.x.toLong()) / 2L).toInt(),
            y = ((first.y.toLong() + second.y.toLong()) / 2L).toInt(),
        )
        AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
        showTap(point.x, point.y)
        return deviceController.tap(point.x, point.y)
    }

    private fun tapElement(args: JSONObject): String {
        val index = args.optInt("index", -1)
        val observation = requireElementObservation(args) ?: return observationError(args)
        val node = observation.nodes.firstOrNull { it.index == index }
        if (node == null) {
            return errorResult("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index")
        }
        var result = deviceController.tapElement(observation, index)
        if (result.isStaleContent()) {
            retryStaleElementAction(node) { refreshed, retryIndex ->
                deviceController.tapElement(refreshed, retryIndex)
            }?.let { retried -> result = retried }
        }
        if (result.isOkJson()) {
            AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
            showTap(node.centerX, node.centerY)
        }
        return result
    }

    private fun longPressElement(args: JSONObject): String {
        val index = args.optInt("index", -1)
        val observation = requireElementObservation(args) ?: return observationError(args)
        val node = observation.nodes.firstOrNull { it.index == index }
        val durationMs = args.optInt("duration_ms", 800)
        if (node == null) {
            return errorResult("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index")
        }
        var result = deviceController.longPressElement(observation, index, durationMs)
        if (result.isStaleContent()) {
            retryStaleElementAction(node) { refreshed, retryIndex ->
                deviceController.longPressElement(refreshed, retryIndex, durationMs)
            }?.let { retried -> result = retried }
        }
        if (result.isOkJson()) {
            AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.LONG_PRESS)
            showLongPress(node.centerX, node.centerY, durationMs)
        }
        return result
    }

    private fun longPress(args: JSONObject): String {
        val point = convertPoint(
            x = args.optInt("x"),
            y = args.optInt("y"),
            coordinateSpace = args.optString("coordinate_space")
        )
        val durationMs = args.optInt("duration_ms", 800)
        AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.LONG_PRESS)
        showLongPress(point.x, point.y, durationMs)
        return deviceController.longPress(point.x, point.y, durationMs)
    }

    private fun swipe(args: JSONObject): String {
        val start = convertPoint(
            x = args.optInt("x1"),
            y = args.optInt("y1"),
            coordinateSpace = args.optString("coordinate_space")
        )
        val end = convertPoint(
            x = args.optInt("x2"),
            y = args.optInt("y2"),
            coordinateSpace = args.optString("coordinate_space")
        )
        val durationMs = args.optInt("duration_ms", 500)
        AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.SWIPE)
        showSwipe(start.x, start.y, end.x, end.y, durationMs)
        return deviceController.swipe(
            start.x,
            start.y,
            end.x,
            end.y,
            durationMs
        )
    }

    private fun drag(args: JSONObject): String {
        val start = convertPoint(
            x = args.optInt("x1"),
            y = args.optInt("y1"),
            coordinateSpace = args.optString("coordinate_space")
        )
        val end = convertPoint(
            x = args.optInt("x2"),
            y = args.optInt("y2"),
            coordinateSpace = args.optString("coordinate_space")
        )
        val holdMs = args.optInt("hold_ms", 500)
        val durationMs = args.optInt("duration_ms", 600)
        AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.SWIPE)
        showSwipe(start.x, start.y, end.x, end.y, holdMs + durationMs)
        return deviceController.drag(
            start.x,
            start.y,
            end.x,
            end.y,
            holdMs,
            durationMs
        )
    }

    private fun scrollElement(args: JSONObject): String {
        val observation = requireElementObservation(args) ?: return observationError(args)
        val index = args.optInt("index", -1)
        val direction = args.optString("direction")
        val result = deviceController.scrollElement(
            observation = observation,
            index = index,
            direction = direction
        )
        if (result.isStaleContent()) {
            val node = observation.nodes.firstOrNull { it.index == index }
            if (node != null) {
                retryStaleElementAction(node) { refreshed, retryIndex ->
                    deviceController.scrollElement(
                        observation = refreshed,
                        index = retryIndex,
                        direction = direction
                    )
                }?.let { return it }
            }
        }
        return result
    }

    private fun inputText(args: JSONObject): String {
        val text = args.optString("text")
        if (text.length > 1_000) {
            return errorResult("TEXT_TOO_LONG", "input_text 最多支持 1000 个字符")
        }
        return when (args.optString("mode", "append").lowercase(Locale.ROOT)) {
            "replace" -> replaceText(args)
            "paste" -> pasteText(args)
            else -> deviceController.inputText(text)
        }
    }

    private fun replaceText(args: JSONObject): String {
        val index = args.optNullableInt("index")
        val observation = if (index != null) {
            requireElementObservation(args) ?: return observationError(args)
        } else {
            null
        }
        return deviceController.replaceText(
            text = args.optString("text"),
            index = index,
            observation = observation,
        )
    }

    private fun clearText(args: JSONObject): String {
        val index = args.optNullableInt("index")
        val observation = if (index != null) {
            requireElementObservation(args) ?: return observationError(args)
        } else {
            null
        }
        return deviceController.clearText(index = index, observation = observation)
    }

    private fun setClipboard(args: JSONObject): String =
        deviceController.clipboardSet(requireContext(), args.optString("text"))

    private fun getClipboard(): String =
        deviceController.clipboardGet(requireContext())

    private fun pasteText(args: JSONObject): String =
        deviceController.pasteText(args.optString("text"))

    private fun waitForText(args: JSONObject): String =
        deviceController.waitForText(
            text = args.optString("text"),
            timeoutMs = args.optInt("timeout_ms", 10_000),
            includeDesc = args.optBoolean("include_desc", true),
            matchMode = args.optString("match", "contains")
        )

    private fun waitForPackage(args: JSONObject): String =
        deviceController.waitForPackage(
            packageName = args.optString("package_name"),
            timeoutMs = args.optInt("timeout_ms", 10_000)
        )

    private fun convertPoint(x: Int, y: Int, coordinateSpace: String): ScreenPoint {
        val space = publishedObservation.get().coordinateSpace
        val requestedSpace = coordinateSpace.trim().lowercase(Locale.ROOT)
        if (requestedSpace == "screen" || (requestedSpace.isBlank() && space == null)) {
            val (width, height) = space?.let { it.screenWidth to it.screenHeight }
                ?: deviceController.screenDimensions()
            if (x !in 0 until width || y !in 0 until height) {
                throw InvalidToolArgumentException(
                    "屏幕坐标超出范围：($x,$y) not in ${width}x$height",
                )
            }
            return ScreenPoint(x, y)
        }
        if (space == null) {
            throw InvalidToolArgumentException(
                "当前没有可用的截图坐标系；请先 observe_screen，或明确设置 coordinate_space=screen",
            )
        }
        val point = runCatching { space.fromScreenshot(x, y) }
            .getOrElse { throwable ->
                throw InvalidToolArgumentException(
                    throwable.message ?: "截图坐标超出范围",
                )
            }
        return ScreenPoint(point.x, point.y)
    }

    private fun searchApps(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isBlank()) {
            return errorResult("INVALID_ARGUMENT", "query 不能为空")
        }
        val includeSystem = args.optBoolean("include_system", false)
        val limit = args.optInt("limit", 10).coerceIn(1, 20)
        val apps = findAppsByName(query, includeSystem).take(limit)
        return JSONObject()
            .put("ok", true)
            .put("tool", "search_apps")
            .put("query", query)
            .put("apps", apps.toJsonArray())
            .toString()
    }

    private fun launchApp(args: JSONObject): String {
        val packageName = args.optString("package_name").trim().ifBlank { null }
        val appName = args.optString("app_name").trim().ifBlank { null }

        val app = if (packageName != null) {
            findAppByPackage(packageName) ?: AppInfo(packageName = packageName, appName = appName ?: packageName)
        } else {
            if (appName == null) {
                return errorResult("INVALID_ARGUMENT", "package_name 和 app_name 至少提供一个")
            }
            val matches = findAppsByName(appName, includeSystem = false)
            val exactMatches = matches.filter { it.appName.equals(appName, ignoreCase = true) }
            when {
                exactMatches.size == 1 -> exactMatches.single()
                matches.size == 1 -> matches.single()
                matches.isEmpty() -> return errorResult(
                    code = "APP_NOT_FOUND",
                    message = "未找到应用：$appName"
                )
                else -> return JSONObject()
                    .put("ok", false)
                    .put("code", "AMBIGUOUS_APP")
                    .put("message", "匹配到多个应用，请指定 package_name")
                    .put("candidates", matches.take(10).toJsonArray())
                    .toString()
            }
        }

        val context = requireContext()
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent == null) {
            return errorResult(
                code = "APP_NOT_LAUNCHABLE",
                message = "应用不可启动或未安装：${app.packageName}"
            )
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(launchIntent)
        logger.info("Agent local tool action=launch_app outcome=started")
        return JSONObject()
            .put("ok", true)
            .put("tool", "launch_app")
            .put("app_name", app.appName)
            .put("package_name", app.packageName)
            .toString()
    }

    private fun openUri(args: JSONObject): String {
        val uriText = args.optString("uri").trim()
        if (uriText.isBlank()) {
            return errorResult("INVALID_ARGUMENT", "uri 不能为空")
        }
        val uri = Uri.parse(uriText)
        if (uri.scheme.isNullOrBlank()) {
            return errorResult("INVALID_ARGUMENT", "uri 缺少 scheme")
        }
        val context = requireContext()
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!HookSupport.resolvesActivity(context, intent)) {
            return errorResult("NO_ACTIVITY", "没有应用可以处理该 URI")
        }
        context.startActivity(intent)
        logger.info("Agent local tool action=open_uri outcome=started")
        return JSONObject()
            .put("ok", true)
            .put("tool", "open_uri")
            .put("scheme", uri.scheme?.lowercase(Locale.ROOT))
            .also { result ->
                if (uri.scheme.equals("https", true)) {
                    result.put("display_uri", uriText)
                }
            }
            .toString()
    }

    private fun runCommand(args: JSONObject): String {
        val command = args.optString("command")
        return SelfKillHint.append(
            command,
            terminalController.runCommand(
                command = command,
                cwd = args.optString("cwd").ifBlank { null },
                timeoutSeconds = args.optInt("timeout_seconds", 30)
            ),
        )
    }

    private fun terminal(args: JSONObject): String {
        val command = args.optString("command")
        return SelfKillHint.append(
            command,
            terminalController.terminalAction(
                action = args.optString("action", "open_and_exec"),
                command = command,
                cwd = args.optString("cwd").ifBlank { null },
                timeoutMs = args.optInt("timeout_ms", 30_000),
                identity = args.optString("identity"),
                mergeStderr = args.optBoolean("merge_stderr", false),
                sessionId = args.optString("session_id").ifBlank { null },
                jobId = args.optString("job_id").ifBlank { null },
                async = args.optBoolean("async", false),
                offsetChars = args.optInt("offset_chars", 0),
                maxChars = args.optInt("max_chars", 8_000),
                closeIfDone = args.optBoolean("close_if_done", false),
                environment = args.optString("environment", "android"),
                taskId = args.optString("task_id").ifBlank { null },
            ),
        )
    }

    private fun readFile(args: JSONObject): String {
        val startLine = args.optInt("start_line", 0)
        val endLine = args.optInt("end_line", 0)
        if (startLine > 0 || endLine > 0) {
            return terminalController.readFileLines(
                path = args.optString("path"),
                startLine = startLine.coerceAtLeast(1),
                endLine = endLine.takeIf { it > 0 },
                maxChars = args.optInt("max_chars", 16_000).coerceIn(200, 32_000)
            )
        }
        return terminalController.readFile(
            path = args.optString("path"),
            offsetBytes = args.optInt("offset_bytes", 0),
            maxBytes = args.optInt("max_bytes", 65_536)
        )
    }

    private fun readFiles(args: JSONObject): String {
        val raw = args.optJSONArray("paths") ?: return errorResult("INVALID_ARGUMENT", "paths 必须是数组")
        val paths = (0 until raw.length()).mapNotNull { index ->
            raw.optString(index).takeIf { it.isNotBlank() }
        }
        if (paths.isEmpty()) return errorResult("INVALID_ARGUMENT", "paths 不能为空")
        return terminalController.readFiles(
            paths = paths,
            maxChars = args.optInt("max_chars", FileToolLimits.MAX_OUTPUT_CHARS),
        )
    }

    private fun editFile(args: JSONObject): String =
        terminalController.editFile(
            path = args.optString("path"),
            oldText = args.optString("old_text"),
            newText = args.optString("new_text"),
            replaceAll = args.optBoolean("replace_all", false)
        )

    private fun editFiles(args: JSONObject): String {
        val raw = args.optJSONArray("edits") ?: return errorResult("INVALID_ARGUMENT", "edits 必须是数组")
        if (raw.length() == 0) return errorResult("INVALID_ARGUMENT", "edits 不能为空")
        val requests = (0 until raw.length()).mapNotNull { index ->
            val entry = raw.optJSONObject(index) ?: return@mapNotNull null
            val path = entry.optString("path")
            if (path.isBlank()) return@mapNotNull null
            FileTextOperations.EditRequest(
                path = path,
                oldText = entry.optString("old_text"),
                newText = entry.optString("new_text"),
                replaceAll = entry.optBoolean("replace_all", false),
            )
        }
        if (requests.isEmpty()) return errorResult("INVALID_ARGUMENT", "edits 里没有有效的 path")
        return terminalController.editFiles(requests)
    }

    private fun searchCode(args: JSONObject): String {
        val requested = args.optString("pattern")
        // BusyBox 的 grep -E 不认 PCRE 的 \s/\d/\w：先翻成 POSIX 类，省掉一轮 "bad regex"。
        val pattern = SearchPattern.toPosix(requested)
        val result = appendBusyBoxRegexHint(
            terminalController.searchCode(
                path = args.optString("path"),
                pattern = pattern,
                glob = args.optString("glob").ifBlank { null },
                maxResults = args.optInt("max_results", FileTextOperations.DEFAULT_SEARCH_RESULTS),
                contextLines = args.optInt("context_lines", 0),
                maxChars = args.optInt("max_chars", 8_000).coerceIn(200, 32_000),
                filesOnly = args.optBoolean("files_only", false),
                ignoreCase = args.optBoolean("ignore_case", false),
                offset = args.optInt("offset", 0),
                noIgnore = args.optBoolean("no_ignore", false),
                hidden = args.optBoolean("hidden", false)
            ),
        )
        if (pattern == requested) return result
        return runCatching {
            JSONObject(result)
                .put("pattern_used", pattern)
                .put("pattern_rewritten", true)
                .toString()
        }.getOrDefault(result)
    }

    /**
     * 按文件名 glob 找文件。路径归一化、存在性提示与 ripgrep 通道都交给 terminal controller，
     * 这样 /workspace、~、相对路径的行为和 search_code 完全一致（此前直接拼 shell find，
     * /workspace/... 会被当成不存在的目录）。
     *
     * glob 与 path 里出现 Shell 元字符时直接拒绝，不把模型给的字符串拼进命令行。
     */
    private fun findFiles(args: JSONObject): String {
        val glob = args.optString("glob").trim()
        if (glob.isBlank()) return errorResult("MISSING_PARAM", "缺少 glob")
        if (glob.any { it in FORBIDDEN_SHELL_CHARS }) {
            return errorResult("INVALID_ARGUMENT", "glob 不能包含引号、分号、管道等 Shell 字符")
        }
        val path = args.optString("path").trim().ifBlank { DEFAULT_FILE_WORKSPACE }
        if (path.any { it in FORBIDDEN_SHELL_CHARS }) {
            return errorResult("INVALID_ARGUMENT", "path 不能包含引号、分号、管道等 Shell 字符")
        }
        val limit = args.optInt("limit", DEFAULT_FIND_LIMIT).coerceIn(1, MAX_FIND_LIMIT)
        return terminalController.findFiles(
            path = path,
            glob = glob,
            limit = limit,
            noIgnore = args.optBoolean("no_ignore", false),
            hidden = args.optBoolean("hidden", false),
        )
    }

    /** BusyBox 的 grep -E 不支持 PCRE 语法；报错时直接给出可用写法，省掉一轮试错。 */
    private fun appendBusyBoxRegexHint(content: String): String {
        if (!content.contains("\"ok\":false")) return content
        if (BUSYBOX_REGEX_ERROR_MARKERS.none { marker -> content.contains(marker) }) return content
        val parsed = runCatching { JSONObject(content) }.getOrNull() ?: return content
        val message = parsed.optString("message")
        if (message.contains("BusyBox")) return content
        return parsed.put(
            "message",
            "$message（BusyBox grep -E 不支持 \\d、\\w 等 PCRE 语法，请改用 [0-9]、[[:alnum:]]）",
        ).toString()
    }

    private fun writeFile(args: JSONObject): String =
        terminalController.writeFile(
            path = args.optString("path"),
            content = args.optString("content"),
            append = args.optBoolean("append", false)
        )

    private fun listDirectory(args: JSONObject): String =
        terminalController.listDirectory(
            path = args.optString("path"),
            showHidden = args.optBoolean("show_hidden", false),
            limit = args.optInt("limit", 80)
        )

    private fun findAppByPackage(packageName: String): AppInfo? =
        installedLauncherApps().firstOrNull { it.packageName == packageName }

    private fun findAppsByName(query: String, includeSystem: Boolean): List<AppInfo> {
        val normalizedQuery = query.normalized()
        return installedLauncherApps()
            .asSequence()
            .filter { includeSystem || !it.isSystemApp }
            .mapNotNull { app ->
                val score = app.matchScore(query, normalizedQuery)
                if (score == Int.MAX_VALUE) null else score to app
            }
            .sortedWith(compareBy<Pair<Int, AppInfo>> { it.first }.thenBy { it.second.appName })
            .map { it.second }
            .toList()
    }

    private fun AppInfo.matchScore(rawQuery: String, normalizedQuery: String): Int {
        val normalizedName = appName.normalized()
        val normalizedPackage = packageName.normalized()
        return when {
            packageName.equals(rawQuery, ignoreCase = true) -> 0
            appName.equals(rawQuery, ignoreCase = true) -> 1
            normalizedName == normalizedQuery -> 2
            normalizedPackage.contains(normalizedQuery) -> 3
            normalizedName.contains(normalizedQuery) -> 4
            else -> Int.MAX_VALUE
        }
    }

    private fun installedLauncherApps(): List<AppInfo> {
        val context = requireContext()
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(0L)
        )
        val apps = linkedMapOf<String, AppInfo>()
        resolveInfos.forEach { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@forEach
            val applicationInfo = activityInfo.applicationInfo ?: return@forEach
            val packageName = applicationInfo.packageName ?: return@forEach
            val appName = resolveInfo.loadLabel(packageManager).toString().trim()
                .takeIf { it.isNotBlank() }
                ?: packageName
            apps.putIfAbsent(
                packageName,
                AppInfo(
                    packageName = packageName,
                    appName = appName,
                    isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                )
            )
        }
        return apps.values.toList()
    }

    private fun requireContext(): Context =
        AgentAppContext.resolve()
            ?: error("无法获取 Android 进程 Context")

    private fun List<AppInfo>.toJsonArray(): JSONArray =
        JSONArray().also { array ->
            forEach { app ->
                array.put(
                    JSONObject()
                        .put("app_name", app.appName)
                        .put("package_name", app.packageName)
                        .put("is_system_app", app.isSystemApp)
                )
            }
        }

    private fun String.normalized(): String =
        trim().lowercase(Locale.ROOT)

    private fun String.isStaleContent(): Boolean =
        !isOkJson() && jsonErrorCode() == STALE_CONTENT_CODE

    private fun String.jsonErrorCode(): String? = runCatching {
        JSONObject(this).optString("code").ifBlank { null }
    }.getOrNull()

    /**
     * 节点动作因窗口内容变化被拒时，用原节点的 viewId / 文本 / 描述在新快照里找回同一个
     * 控件并重试一次。被拒表示动作没有执行，重试不会造成重复操作；找不到等价节点或重试仍
     * 失败时不返回结果，交给调用方把原始错误报给模型。
     */
    private fun retryStaleElementAction(
        staleNode: RootShellDeviceController.UiNode,
        action: (observation: RootShellDeviceController.ElementObservation, index: Int) -> String,
    ): String? {
        if (staleNode.viewId.isBlank() && staleNode.text.isBlank() && staleNode.desc.isBlank()) return null
        val refreshed = runCatching {
            deviceController.observe(includeScreenshot = false, includeUiTree = true, maxNodes = 120)
                .elementObservation
        }.getOrNull() ?: return null
        val candidate = refreshed.nodes.firstOrNull { it.matchesSemanticsOf(staleNode) } ?: return null
        val retried = runCatching { action(refreshed, candidate.index) }.getOrNull() ?: return null
        return retried.takeIf { it.isOkJson() }
    }

    private fun RootShellDeviceController.UiNode.matchesSemanticsOf(
        other: RootShellDeviceController.UiNode,
    ): Boolean {
        if (viewId.isNotBlank() && viewId == other.viewId) return true
        if (text.isNotBlank() && text == other.text && className == other.className) return true
        if (desc.isNotBlank() && desc == other.desc) return true
        return false
    }

    private fun String.isOkJson(): Boolean =
        runCatching { JSONObject(this).optBoolean("ok", false) }.getOrDefault(false)

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private fun requireElementObservation(
        args: JSONObject,
    ): RootShellDeviceController.ElementObservation? {
        val current = publishedObservation.get().elements ?: return null
        val requestedId = args.optString("observation_id").trim()
        return current.takeIf {
            ObservationReferencePolicy.validate(current.id, requestedId) ==
                ObservationReferencePolicy.Status.MATCH
        }
    }

    private fun observationError(args: JSONObject): String {
        val current = publishedObservation.get().elements
        val requestedId = args.optString("observation_id").trim()
        return when (ObservationReferencePolicy.validate(current?.id, requestedId)) {
            ObservationReferencePolicy.Status.NO_OBSERVATION ->
                errorResult("NO_OBSERVATION", "请先调用 observe_screen 获取 UI 节点")
            ObservationReferencePolicy.Status.ID_REQUIRED -> errorResult(
                "OBSERVATION_ID_REQUIRED",
                "节点动作必须携带同一次 observe_screen 返回的 observation_id",
            )
            ObservationReferencePolicy.Status.STALE -> errorResult(
                "STALE_OBSERVATION",
                "observation_id=$requestedId 已过期；当前为 ${current?.id}，请重新观察屏幕",
            )
            ObservationReferencePolicy.Status.MATCH -> errorResult(
                "OBSERVATION_ERROR",
                "观察快照状态异常，请重新观察屏幕",
            )
        }
    }

    // ==================== Skills tools ====================

    private fun skillsList(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val query = args.optString("query").trim().lowercase()
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        val entries = indexService.listInstalledSkills()
            .filter { entry -> SkillCompatibilityChecker.evaluate(entry).available }
            .filter { entry -> isVisibleInCurrentRun(entry.id) }
            .filter { entry ->
                if (query.isBlank()) true
                else listOf(entry.id, entry.name, entry.description, entry.skillFilePath, entry.rootPath)
                    .any { it.lowercase().contains(query) }
            }
            .take(limit)
        val items = JSONArray()
        entries.forEach { entry ->
            val capabilities = JSONArray()
            if (entry.hasScripts) capabilities.put("scripts")
            if (entry.hasReferences) capabilities.put("references")
            if (entry.hasAssets) capabilities.put("assets")
            if (entry.hasEvals) capabilities.put("evals")
            items.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("description", entry.description)
                    .put("enabled", entry.enabled)
                    .put("source", entry.source)
                    .put("rootPath", entry.rootPath)
                    .put("skillFilePath", entry.skillFilePath)
                    .put("capabilities", capabilities)
                    .put("entrypoints", JSONArray(entry.entrypoints))
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("count", entries.size)
            .put("items", items)
            .toString()
    }

    private fun skillsRead(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val loader = skillLoader
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能加载器未初始化")
        val skillId = args.optString("skillId").trim()
        if (skillId.isBlank()) return errorResult("MISSING_PARAM", "缺少 skillId")
        val maxChars = args.optInt("maxChars", 16_000).coerceIn(512, 64_000)
        val entry = indexService.findInstalledSkill(skillId)
            ?: return errorResult("NOT_FOUND", "未找到 skill：$skillId")
        if (!isVisibleInCurrentRun(entry.id)) return nextTurnRequired(entry.id)
        val compat = SkillCompatibilityChecker.evaluate(entry)
        if (!compat.available) return errorResult("INCOMPATIBLE", compat.reason ?: "当前环境不可用")
        val resolved = loader.load(entry, "agent 主动读取 skill")
            ?: return errorResult("READ_FAILED", "读取 SKILL.md 失败：${entry.skillFilePath}")
        val body = if (resolved.bodyMarkdown.length <= maxChars) {
            resolved.bodyMarkdown
        } else {
            resolved.bodyMarkdown.take(maxChars) + "\n..."
        }
        val references = JSONArray()
        resolved.loadedReferences.forEach { references.put(it) }
        val frontmatter = JSONObject()
        resolved.frontmatter.forEach { (k, v) -> frontmatter.put(k, v) }
        return JSONObject()
            .put("ok", true)
            .put("id", entry.id)
            .put("name", entry.name)
            .put("description", entry.description)
            .put("rootPath", entry.rootPath)
            .put("skillFilePath", entry.skillFilePath)
            .put("scriptsDir", resolved.scriptsDir ?: JSONObject.NULL)
            .put("assetsDir", resolved.assetsDir ?: JSONObject.NULL)
            .put("references", references)
            .put("frontmatter", frontmatter)
            .put("bodyMarkdown", body)
            .put("split_hint", splitHint(resolved.bodyMarkdown.length) ?: JSONObject.NULL)
            .toString()
    }

    /** 正文过长时提示按"互斥内容拆文件"的原则拆分，避免每次触发都要整篇读入。 */
    private fun splitHint(bodyChars: Int): String? = bodyChars
        .takeIf { it >= SKILL_BODY_SPLIT_HINT_CHARS }
        ?.let { chars ->
            "SKILL.md 正文约 $chars 字符；建议把互不共用的内容拆到 references/ 下，" +
                "由 skills_read_resource 按需读取"
        }

    private fun skillsReadResource(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val reader = skillResourceReader
            ?: return errorResult("SKILLS_UNAVAILABLE", "Skill 资源读取器未初始化")
        val skillId = args.getString("skillId").trim()
        val relativePath = args.getString("relativePath").trim()
        val maxChars = args.optInt("maxChars", 16_000).coerceIn(512, 64_000)
        val entry = indexService.findInstalledSkill(skillId)
            ?: return errorResult("NOT_FOUND", "未找到已启用 Skill：$skillId")
        if (!isVisibleInCurrentRun(entry.id)) return nextTurnRequired(entry.id)
        val compatibility = SkillCompatibilityChecker.evaluate(entry)
        if (!compatibility.available) {
            return errorResult(
                "INCOMPATIBLE",
                compatibility.reason ?: "当前环境不可用",
            )
        }
        return when (val result = reader.readText(entry, relativePath)) {
            is SkillResourceReadResult.Success -> {
                val truncated = result.text.length > maxChars
                val visibleText = if (truncated) {
                    result.text.take(maxChars).let { prefix ->
                        if (prefix.lastOrNull()?.isHighSurrogate() == true) {
                            prefix.dropLast(1)
                        } else {
                            prefix
                        }
                    }
                } else {
                    result.text
                }
                JSONObject()
                    .put("ok", true)
                    .put("skillId", entry.id)
                    .put("relativePath", result.relativePath)
                    .put("text", visibleText)
                    .put("truncated", truncated)
                    .put("totalChars", result.text.length)
                    .toString()
            }
            is SkillResourceReadResult.Failure -> errorResult(
                code = result.error.code.name,
                message = result.error.message,
            )
        }
    }

    private fun skillsListCurated(): String {
        val source = githubSkillSource
            ?: return errorResult("SKILL_INSTALLER_UNAVAILABLE", "GitHub Skill 服务未初始化")
        return skillSourceResult {
            val inspection = source.listCurated()
            rememberInspection(
                repository = GitHubSkillRepositoryParser.parse(inspection.repository),
                inspection = inspection,
                rememberDefault = true,
            )
            inspectionResult(inspection)
        }
    }

    private fun skillsInspectGitHub(args: JSONObject): String {
        val source = githubSkillSource
            ?: return errorResult("SKILL_INSTALLER_UNAVAILABLE", "GitHub Skill 服务未初始化")
        return skillSourceResult {
            val repository = GitHubSkillRepositoryParser.resolve(
                repository = args.getString("repository"),
                explicitRef = args.optString("ref").takeIf { args.has("ref") },
                explicitPath = args.optString("path").takeIf { args.has("path") },
            )
            val inspection = source.inspect(repository)
            rememberInspection(
                repository = repository,
                inspection = inspection,
                rememberDefault = repository.ref == null,
            )
            inspectionResult(inspection)
        }
    }

    private fun skillsInstallFromGitHub(args: JSONObject): String {
        val replaceExisting = args.optBoolean("replaceExisting", false)
        return skillSourceResult {
            val requestedRepository = GitHubSkillRepositoryParser.resolve(
                repository = args.getString("repository"),
                explicitRef = args.optString("ref").takeIf { args.has("ref") },
                explicitPath = null,
            )
            val pathsJson = args.getJSONArray("paths")
            val selectedPaths = (0 until pathsJson.length()).map { index ->
                GitHubSkillRepositoryParser.normalizeRelativePath(pathsJson.getString(index))
            }
            if (replaceExisting && selectedPaths.size != 1) {
                return@skillSourceResult errorResult(
                    "SKILL_REPLACE_SCOPE_TOO_BROAD",
                    "一次只能替换一个 Skill 路径；请逐个重试",
                )
            }
            val expectedReplacementId = args.optString("expectedReplacementId").trim()
            val repository = if (replaceExisting) {
                validateReplacementReplay(
                    requestedRepository = requestedRepository,
                    selectedPaths = selectedPaths,
                    expectedReplacementId = expectedReplacementId,
                )?.let { return@skillSourceResult it }
                requestedRepository.copy(ref = pendingSkillConflict.get()!!.commitSha)
            } else {
                val snapshot = inspectedGitHubSnapshots[
                    inspectionKey(requestedRepository.slug, requestedRepository.ref)
                ] ?: return@skillSourceResult errorResult(
                    "SKILL_INSPECTION_REQUIRED",
                    "安装前必须在本轮先检查同一仓库与 ref 的 Skill 候选",
                )
                val invalidSelection = selectedPaths.firstOrNull {
                    it !in snapshot.candidatesByPath
                }
                if (invalidSelection != null) {
                    return@skillSourceResult errorResult(
                        "INVALID_SKILL_SELECTION",
                        "所选路径不在本轮检查返回的候选中：$invalidSelection",
                    )
                }
                val snapshotPrefix = snapshot.prefix
                if (
                    snapshotPrefix != null &&
                    selectedPaths.any {
                        it != snapshotPrefix && !it.startsWith("$snapshotPrefix/")
                    }
                ) {
                    return@skillSourceResult errorResult(
                        "INVALID_SKILL_SELECTION",
                        "所选路径不在本轮检查的目录范围内",
                    )
                }
                requestedRepository.copy(ref = snapshot.commitSha)
            }
            val prefix = requestedRepository.path?.takeUnless { it == "." }
            if (
                prefix != null &&
                selectedPaths.any { it != prefix && !it.startsWith("$prefix/") }
            ) {
                return@skillSourceResult errorResult(
                    "INVALID_SKILL_SELECTION",
                    "所选路径不在 GitHub URL 指定目录内",
                )
            }
            val source = githubSkillSource
                ?: return@skillSourceResult errorResult(
                    "SKILL_INSTALLER_UNAVAILABLE",
                    "GitHub Skill 服务未初始化",
                )
            val installer = skillPackageInstaller
                ?: return@skillSourceResult errorResult(
                    "SKILL_INSTALLER_UNAVAILABLE",
                    "Skill 安装器未初始化",
                )
            source.downloadArchive(repository).use { archive ->
                if (closed.get()) {
                    return@skillSourceResult errorResult(
                        "SKILL_INSTALL_CANCELLED",
                        "Skill 安装已取消，未提交文件",
                    )
                }
                val audit = SkillContentAudit.auditArchive(archive.file, selectedPaths)
                val flagged = audit.filterValues { summary -> summary.requiresConfirmation }
                if (flagged.isNotEmpty() && !args.optBoolean("acknowledge_audit", false)) {
                    return@skillSourceResult JSONObject()
                        .put("ok", false)
                        .put("code", "SKILL_AUDIT_CONFIRMATION_REQUIRED")
                        .put(
                            "message",
                            "下载内容包含可执行脚本、二进制或脚本里的网络命令；确认这是预期的 Skill 内容后，" +
                                "带 acknowledge_audit=true 重试同一次安装",
                        )
                        .put("repository", archive.repository)
                        .put("ref", archive.ref)
                        .put("commitSha", archive.commitSha)
                        .put("selectedPaths", JSONArray(selectedPaths))
                        .put(
                            "audit",
                            JSONObject().also { result ->
                                flagged.forEach { (path, summary) -> result.put(path, summary.toJson()) }
                            },
                        )
                        .toString()
                }
                val result = installer.installRepositoryZip(
                    openStream = { archive.file.inputStream() },
                    selectedPaths = selectedPaths,
                    replaceUserSkills = replaceExisting,
                    expectedReplacementIds = if (replaceExisting) {
                        setOf(expectedReplacementId)
                    } else {
                        emptySet()
                    },
                    isCancelled = closed::get,
                )
                installResult(
                    result = result,
                    repository = archive.repository,
                    ref = archive.ref,
                    commitSha = archive.commitSha,
                    selectedPaths = selectedPaths,
                )
            }
        }
    }

    private fun inspectionResult(
        inspection: io.github.asagnc.sta.agent.skill.GitHubSkillInspection,
    ): String {
        val installedIds = skillIndexService
            ?.listSkillsForManagement()
            .orEmpty()
            .filter { it.installed }
            .mapTo(mutableSetOf()) { SkillParser.normalizeSkillLookup(it.id) }
        val items = JSONArray()
        inspection.candidates.forEach { candidate ->
            val audit = SkillContentAudit.summarize(
                root = candidate.path,
                files = candidate.files.map { file -> SkillContentAudit.FileEntry(file.path, file.sizeBytes) },
            )
            items.put(
                JSONObject()
                    .put("name", candidate.name)
                    .put("path", candidate.path)
                    .put(
                        "installed",
                        SkillParser.normalizeSkillLookup(candidate.name) in installedIds,
                    )
                    .put("audit", audit.toJson()),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("repository", inspection.repository)
            .put("ref", inspection.ref)
            .put("commitSha", inspection.commitSha)
            .put("prefix", inspection.prefix ?: JSONObject.NULL)
            .put("count", inspection.candidates.size)
            .put(
                "install_note",
                "含脚本或二进制的 Skill 在安装时会被要求带 acknowledge_audit=true 二次确认",
            )
            .put("items", items)
            .toString()
    }

    private fun rememberInspection(
        repository: GitHubSkillRepository,
        inspection: GitHubSkillInspection,
        rememberDefault: Boolean,
    ) {
        val snapshot = GitHubInspectionSnapshot(
            commitSha = inspection.commitSha,
            prefix = inspection.prefix,
            candidatesByPath = inspection.candidates.associate { it.path to it.name },
        )
        inspectedGitHubSnapshots[inspectionKey(repository.slug, repository.ref)] = snapshot
        inspectedGitHubSnapshots[inspectionKey(repository.slug, inspection.ref)] = snapshot
        inspectedGitHubSnapshots[inspectionKey(repository.slug, inspection.commitSha)] = snapshot
        if (rememberDefault) {
            inspectedGitHubSnapshots[inspectionKey(repository.slug, null)] = snapshot
        }
    }

    private fun inspectionKey(repository: String, ref: String?): String =
        "${repository.lowercase(Locale.ROOT)}@${ref.orEmpty()}"

    private fun validateReplacementReplay(
        requestedRepository: GitHubSkillRepository,
        selectedPaths: List<String>,
        expectedReplacementId: String,
    ): String? {
        val pending = pendingSkillConflict.get() ?: return errorResult(
            "SKILL_REPLACE_CAPABILITY_REQUIRED",
            "没有可供精确重放的 Skill 冲突",
        )
        if (
            !requestedRepository.slug.equals(pending.repository, ignoreCase = true) ||
            requestedRepository.ref != pending.commitSha ||
            selectedPaths.singleOrNull() != pending.selectedPath ||
            expectedReplacementId != pending.expectedReplacementId
        ) {
            return errorResult(
                "SKILL_REPLACE_CAPABILITY_MISMATCH",
                "覆盖参数必须精确重放冲突结果中的仓库、commitSha、路径与 Skill ID",
            )
        }
        return null
    }

    /**
     * 执行 Skill 在 frontmatter 中声明的命令。
     *
     * 命令文本与 SKILL.md 正文都不进入上下文：脚本内容由 Shell 读取，模型只看到输出，
     * 这也是让"已固化流程"不再消耗上下文的关键。requires 支持两类值：root 与 linux 是环境要求，
     * 其余值一律按「环境里必须存在的命令」处理——执行前用 command -v 探测一次，免得脚本跑到一半
     * 才发现缺工具。所以未知值不是被忽略，也不是一律拒绝。
     */
    private fun skillsRun(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val skillId = args.optString("skillId").trim()
        if (skillId.isBlank()) return errorResult("MISSING_PARAM", "缺少 skillId")
        val entry = indexService.findInstalledSkill(skillId)
            ?: return errorResult("NOT_FOUND", "未找到 skill：$skillId")
        if (!isVisibleInCurrentRun(entry.id)) return nextTurnRequired(entry.id)
        val compatibility = SkillCompatibilityChecker.evaluate(entry)
        if (!compatibility.available) {
            return errorResult("INCOMPATIBLE", compatibility.reason ?: "当前环境不可用")
        }
        val frontmatter = SkillParser.parseSkillFile(File(entry.skillFilePath))?.frontmatter
            ?: return errorResult("READ_FAILED", "读取 SKILL.md 失败：${entry.skillFilePath}")
        val declared = frontmatter["command"]?.trim().orEmpty()
        if (declared.isBlank()) {
            return errorResult(
                "SKILL_COMMAND_NOT_DECLARED",
                "该 Skill 未声明 command；请用 skills_read 读取正文后按步骤执行",
            )
        }
        val requirements = frontmatter["requires"].orEmpty()
            .split(',', ' ', '\n', '\t')
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
        // root 与 linux 是特殊要求；其余按“环境里必须存在的命令”处理，执行前探测一次，
        // 免得脚本跑到一半才发现缺工具。
        val commandRequirements = requirements.filterNot { it in SUPPORTED_SKILL_REQUIREMENTS }
        if (commandRequirements.isNotEmpty()) {
            val probe = commandRequirements.joinToString("; ") { name ->
                "command -v ${shellQuote(name)} >/dev/null 2>&1 || echo ${shellQuote(name)}"
            }
            val probeOutput = terminalController.runCommand(probe, cwd = null, timeoutSeconds = 15)
            val missing = runCatching {
                JSONObject(probeOutput).optString("stdout")
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
            }.getOrDefault(emptyList())
            if (missing.isNotEmpty()) {
                return errorResult(
                    "SKILL_REQUIREMENT_MISSING",
                    "该 Skill 依赖的命令不在当前环境：${missing.joinToString("、")}；请先安装，或改用不依赖它的做法",
                )
            }
        }
        if ("root" in requirements && !rootAvailable()) {
            return errorResult("ROOT_REQUIRED", "该 Skill 声明 requires: root，当前没有 Root 授权，本次未执行")
        }
        val declaredInputs = frontmatter["inputs"].orEmpty()
            .split(',', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val providedInputs = args.optJSONObject("inputs")
        if (declaredInputs.isNotEmpty()) {
            val missingInputs = declaredInputs.filter { providedInputs?.optString(it).isNullOrBlank() }
            if (missingInputs.isNotEmpty()) {
                return errorResult(
                    "SKILL_INPUTS_MISSING",
                    "该 Skill 声明了 inputs：${declaredInputs.joinToString("、")}；" +
                        "缺少 ${missingInputs.joinToString("、")}。请用 inputs 对象按名字给出，命令里的 {{名字}} 会被替换",
                )
            }
        }
        // inputs 是「值」，一律做 shell 转义后再替换进命令，避免值里的引号或分号改变命令结构；
        // arguments 是调用方显式追加的原始参数串，按原样拼在命令之后（与 run_command 同属
        // RootRequirement.PARTIAL，不转义——转义会把 "--flag value" 挤成一个参数，破坏其用途）。
        val resolvedDeclared = if (declaredInputs.isEmpty()) {
            declared
        } else {
            declaredInputs.fold(declared) { acc, name ->
                val value = providedInputs?.optString(name).orEmpty()
                if (value.isBlank()) acc else acc.replace("{{$name}}", shellQuote(value))
            }
        }
        val extra = args.optString("arguments").trim()
        if (extra.length > MAX_SKILL_COMMAND_ARGUMENTS) {
            return errorResult("INVALID_ARGUMENT", "arguments 过长，最多 $MAX_SKILL_COMMAND_ARGUMENTS 字符")
        }
        val command = if (extra.isBlank()) resolvedDeclared else "$resolvedDeclared $extra"
        val declaredTimeout = frontmatter["timeout_seconds"]?.trim()?.toIntOrNull()
        val timeout = args.optInt("timeout_seconds", declaredTimeout ?: DEFAULT_SKILL_COMMAND_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_SKILL_COMMAND_TIMEOUT_SECONDS)
        val linux = "linux" in requirements
        val output = if (linux) {
            terminalController.terminalAction(
                action = "open_and_exec",
                command = command,
                cwd = null,
                timeoutMs = timeout * 1_000,
                identity = "",
                mergeStderr = false,
                sessionId = null,
                jobId = null,
                async = false,
                offsetChars = 0,
                maxChars = 8_000,
                closeIfDone = false,
                environment = "linux",
                taskId = null,
            )
        } else {
            terminalController.runCommand(
                command = command,
                cwd = entry.rootPath,
                timeoutSeconds = timeout,
            )
        }
        val outputJson = runCatching { JSONObject(output) }.getOrNull()
        val stdout = outputJson?.optString("stdout").orEmpty()
        val stderr = outputJson?.optString("stderr").orEmpty()
        return JSONObject()
            .put("ok", outputJson?.optBoolean("ok") ?: true)
            .put("skill", entry.id)
            .put("command", command)
            .put("environment", if (linux) "linux" else "android")
            .put("cwd", if (linux) JSONObject.NULL else entry.rootPath)
            .put("timeout_seconds", timeout)
            .put("inputs", declaredInputs.joinToString("、"))
            .put("outputs", frontmatter["outputs"].orEmpty())
            .put("skill_body_loaded", false)
            .put("output", if (outputJson == null) output else stdout)
            .apply { if (stderr.isNotBlank()) put("stderr", stderr) }
            .toString()
    }

    /**
     * 把可以独立完成的工作交给受限子智能体，只取回摘要。
     *
     * 单入口设计：`roles` 省略或只给一个时是单角色子任务，给多个时并行派发、各自独立取证，
     * 由主 loop 汇总对照。子智能体的上下文与工具输出都不进入当前 run，所以这里的返回值
     * 就是它交给主 loop 的全部信息；主 loop 需要自己校验摘要，不能直接把它当结论。
     */
    private fun delegate(args: JSONObject, toolCallId: String): String {
        val runner = subAgentRunner ?: return errorResult("SUB_AGENT_DISABLED", "子智能体未开启")
        val task = args.optString("task").trim()
        if (task.isBlank()) return errorResult("MISSING_PARAM", "缺少 task")
        val roles = subAgentRoles(args)
        if (roles.size > MAX_SUB_AGENT_ROLES) {
            return errorResult(
                "INVALID_ARGUMENT",
                "角色最多 $MAX_SUB_AGENT_ROLES 个（与并行上限一致）；请合并同类角色后重试",
            )
        }
        // 预算按"档位 + 同档位历史消耗"算，而不是写死一个数：样本够就用 P75，
        // 不够就退回档位默认值，来源会一并回给模型。
        val scope = SubAgentScope.fromWire(args.optString("scope"))
        val plan = AgentSubAgentBudget.plan(scope, subAgentHistory(scope))
        // 多角色是并行的，预算按角色数线性叠加，所以这里必须有一道总量闸门：
        // 否则一次调用就能把整轮会话的预算吃光。
        val totalBudget = plan.tokenBudget * roles.size
        if (totalBudget > SUB_AGENT_TOTAL_TOKEN_CAP) {
            return errorResult(
                "SUB_AGENT_BUDGET_EXCEEDED",
                "本次派发总预算 ${totalBudget} 超过上限 $SUB_AGENT_TOTAL_TOKEN_CAP" +
                    "（每角色 ${plan.tokenBudget} × ${roles.size} 个角色）；请减少角色数，或把 scope 降到 quick 后重试",
            )
        }
        val context = withPlanDigest(args.optString("context").trim().take(MAX_SUB_AGENT_CONTEXT_CHARS))
        val writeMode = args.optString("mode").trim().equals(AgentSubAgentToolCatalog.MODE_WRITE, ignoreCase = true)
        if (!writeMode) {
            // 只有多角色并行才接信箱：单角色没有同伴可分享，开着只会多占一个工具位。
            val sharedMailboxRunId = if (roles.size > 1) mailboxRunId else ""
            // 单角色才给联网读：共享浏览器是进程级单例，多角色并行时后一个 navigate
            // 会顶掉前一个的页面，子智能体读到的就不是自己打开的那一页了。
            val allowWebRead = roles.size == 1
            val requests = roles.map { role ->
                AgentSubAgentRunner.Request(
                    role = role,
                    brief = task.take(MAX_SUB_AGENT_TASK_CHARS),
                    context = context,
                    plan = plan,
                    mailboxRunId = sharedMailboxRunId,
                    allowWebRead = allowWebRead,
                    toolCallId = toolCallId,
                )
            }
            val outcomes = if (requests.size == 1) {
                listOf(runner.run(requests.single()))
            } else {
                runner.runAll(requests)
            }
            recordSubAgentRuns(outcomes)
            return subAgentResult(outcomes)
        }
        // 写入模式只允许单角色：多个子智能体同时改同一份代码，合并冲突是必然的，
        // 与其事后解冲突，不如从一开始就串行。
        if (roles.size > 1) {
            return errorResult(
                "INVALID_ARGUMENT",
                "mode=write 时只能派一个角色（roles 最多 1 个）：多个子智能体同时改代码会在合并时冲突；" +
                    "需要多角色协作时请用 mode=read 取证，再单独派一个角色落地改动",
            )
        }
        val repoPath = args.optString("repo_path").trim().ifBlank { DEFAULT_SUB_AGENT_REPO }
        if (roles.size != 1) {
            return errorResult("INVALID_ARGUMENT", "mode=write 需要恰好一个角色")
        }
        return delegateWrite(runner, roles.single(), task, context, plan, repoPath, toolCallId)
    }

    /**
     * 把当前方案的方向摘要附到子智能体的背景里。
     *
     * 不附的话子智能体只知道自己那一小块任务，可能查一堆与方案无关的东西，而它的产出会回流到
     * 主 loop，等于把方向偏差又放大一遍。方案是主 loop 的方向契约，子智能体应当看得见。
     */
    private fun withPlanDigest(context: String): String {
        val plan = AgentPlanFormat.parse(planSnapshot?.invoke()) ?: return context
        val digest = buildString {
            append("当前方案「").append(plan.title).append("」：").append(plan.digest)
            val steps = plan.steps.take(SUB_AGENT_PLAN_STEPS).joinToString("；")
            if (steps.isNotBlank()) append(" 步骤：").append(steps)
        }.take(SUB_AGENT_PLAN_DIGEST_CHARS)
        if (context.isBlank()) return digest
        return "$digest\n$context".take(MAX_SUB_AGENT_CONTEXT_CHARS)
    }

    /**
     * 写入模式的完整生命周期：建 worktree → 派发 → 取 diff → 回收。
     *
     * 回收放在 `finally` 里：子智能体失败、超时、抛异常都不能把 worktree 留在磁盘上。
     * 合并由主智能体决定，这里只把 diff 统计交回去——自动合并会把"先隔离"的意义抹掉。
     */
    private fun delegateWrite(
        runner: AgentSubAgentRunner,
        role: String,
        task: String,
        context: String,
        plan: SubAgentPlan,
        repoPath: String,
        toolCallId: String,
    ): String {
        val shell: (String) -> String = worktreeShellExecutor ?: { command ->
            val result = runLinuxCommandRaw(command, WORKTREE_COMMAND_TIMEOUT_SECONDS)
            if (result.exitCode == 0) result.stdout
            else "[exit ${result.exitCode}] ${result.stderr.ifBlank { result.stdout }}"
        }
        // 上限保护：worktree 是一次完整检出，放任累积会惄惄吃掉用户存储。
        // 先按 git 的登记信息回收超额的旧 worktree，再建新的；回收失败不阻断本次派发。
        runCatching {
            val listed = shell(AgentWorktreeManager.listManagedWorktreesCommand(repoPath))
            val recycling = AgentWorktreeManager.worktreesToRecycle(repoPath, listed)
            if (recycling.isNotEmpty()) {
                AgentWorktreeManager.cleanupCommands(repoPath, recycling).forEach { command -> shell(command) }
            }
        }
        val token = AgentWorktreeManager.sanitizeToken("$role-${System.currentTimeMillis()}")
        val worktreePath = AgentWorktreeManager.worktreePath(repoPath, token)
        val workspace = SubAgentWorkspace(repoPath = repoPath, worktreePath = worktreePath)

        val created = AgentWorktreeManager.createCommands(repoPath, worktreePath, "HEAD")
            .map { command -> shell(command) }
        val createFailure = created.firstOrNull { output -> output.contains(CREATE_FAILURE_MARKER) }
        if (createFailure != null) {
            runCatching { AgentWorktreeManager.removeCommands(repoPath, worktreePath).forEach { command -> shell(command) } }
            return errorResult(
                "SUB_AGENT_WORKTREE_FAILED",
                "无法在 $repoPath 上创建隔离工作区：${createFailure.take(SUB_AGENT_SHELL_OUTPUT_CHARS)}",
            )
        }
        AgentWorktreeManager.bootstrapCommands(repoPath, worktreePath).forEach { command -> shell(command) }
        val outcome = runner.run(
            AgentSubAgentRunner.Request(
                role = role,
                brief = task.take(MAX_SUB_AGENT_TASK_CHARS),
                context = context,
                plan = plan,
                workspace = workspace,
                // 写入模式本来就是单角色（多角色会在合并时冲突），不存在抢页面，给只读联网。
                allowWebRead = true,
                toolCallId = toolCallId,
            ),
        )
        recordSubAgentRuns(listOf(outcome))
        return subAgentWriteResult(outcome, workspace)
    }

    /**
     * 清理本轮以及历史上遗留的托管 worktree。
     *
     * 在 run 结束时统一调用：保留到这一刻，主智能体才有机会回去看子智能体的改动细节。
     * 清理本身失败不影响 run 结果——它是收尾动作，不该把已经成功的运行变成失败。
     */
    internal fun cleanupWorktrees(repoRoot: String = DEFAULT_SUB_AGENT_REPO) {
        val shell: (String) -> String = worktreeShellExecutor ?: { command ->
            val result = runLinuxCommandRaw(command, WORKTREE_COMMAND_TIMEOUT_SECONDS)
            if (result.exitCode == 0) result.stdout
            else "[exit ${result.exitCode}] ${result.stderr.ifBlank { result.stdout }}"
        }
        runCatching {
            val listed = shell(AgentWorktreeManager.listManagedWorktreesCommand(repoRoot))
            val paths = AgentWorktreeManager.parseManagedWorktrees(repoRoot, listed)
            if (paths.isEmpty()) return
            AgentWorktreeManager.cleanupCommands(repoRoot, paths).forEach { command -> shell(command) }
        }
    }

    /** 写入模式的结果：摘要 + diff 统计 + 明确的合并提示（怎么合由主智能体决定）。 */
    private fun subAgentWriteResult(
        outcome: AgentSubAgentRunner.Outcome,
        workspace: SubAgentWorkspace,
    ): String {
        val payload = JSONObject(subAgentResult(listOf(outcome)))
            .put("mode", AgentSubAgentToolCatalog.MODE_WRITE)
            .put("worktree", workspace.worktreePath)
            .put("changed_files", outcome.changedFiles)
            .put("diff_stat", outcome.diffStat.ifBlank { JSONObject.NULL as Any })
            .put(
                "merge_hint",
                if (outcome.changedFiles > 0) {
                    "改动还在隔离工作区里，没有进主工作区。要合并就自己用 terminal 执行：" +
                        "git -C ${workspace.worktreePath} --no-pager diff HEAD | " +
                        "git -C ${workspace.repoPath} apply --3way" +
                        "。工作区会保留到本轮结束，期间你可以随时 `git -C ${workspace.worktreePath} diff` 看细节；" +
                        "本轮结束后自动回收。"
                } else {
                    "子智能体没有产生文件改动，无需合并。"
                },
            )
        return payload.toString()
    }

    /**
     * `roles` 省略、为空或全是空白时退回默认单角色；否则去重后保序返回。
     *
     * 多取一个名额（MAX + 1）是为了让"角色过多"走到明确报错，而不是被静默截断。
     */
    private fun subAgentRoles(args: JSONObject): List<String> {
        val array = args.optJSONArray("roles") ?: return listOf(DEFAULT_SUB_AGENT_ROLE)
        val roles = (0 until array.length())
            .mapNotNull { index -> array.optString(index).trim().takeIf { it.isNotBlank() } }
            .distinct()
            .take(MAX_SUB_AGENT_ROLES + 1)
        return roles.ifEmpty { listOf(DEFAULT_SUB_AGENT_ROLE) }
    }

    /** 摘要与失败原因一起交回模型，让主 loop 能判断要不要自己补做。 */
    private fun subAgentResult(outcomes: List<AgentSubAgentRunner.Outcome>): String {
        val results = JSONArray()
        outcomes.forEach { outcome ->
            results.put(
                JSONObject()
                    .put("role", outcome.role)
                    .put("ok", outcome.ok)
                    .put("rounds", outcome.rounds)
                    .put("summary", outcome.summary)
                    .put("error", outcome.errorCode.ifBlank { JSONObject.NULL as Any })
                    .put("message", outcome.errorMessage)
                    .put("scope", outcome.scope.wire)
                    .put("token_budget", outcome.tokenBudget)
                    .put("budget_source", if (outcome.budgetFromHistory) "history" else "default"),
            )
        }
        return JSONObject()
            .put("ok", outcomes.any { outcome -> outcome.ok })
            .put("results", results)
            .put(
                "note",
                "子智能体的工具输出没有进入当前上下文；请校验摘要后再下结论。" +
                    "budget_source=history 表示预算按同档位历史消耗估算，default 表示样本不足、用的档位默认值。",
            )
            .toString()
    }

    /**
     * 读同档位的历史消耗样本，用于估算本次委派的预算。
     *
     * 读库失败不该让委派失败：任何异常都退化成"没有样本"，由 [AgentSubAgentBudget] 回退默认值。
     */
    private fun subAgentHistory(scope: SubAgentScope): List<SubAgentSample> = runCatching {
        runBlocking {
            StaDatabase.get(context).subAgentRunDao()
                .recent(scope.wire, SUB_AGENT_HISTORY_SAMPLES)
                .map { row -> SubAgentSample(tokens = row.tokens, rounds = row.rounds, ok = row.ok) }
        }
    }.getOrElse { emptyList() }

    /** 把本次实际消耗写回样本表，并按条数裁剪——这张表只服务预算评估，不需要长期归档。 */
    private fun recordSubAgentRuns(outcomes: List<AgentSubAgentRunner.Outcome>) {
        runCatching {
            runBlocking {
                val dao = StaDatabase.get(context).subAgentRunDao()
                outcomes.forEach { outcome ->
                    dao.insert(
                        SubAgentRunEntity(
                            scope = outcome.scope.wire,
                            tokens = outcome.estimatedTokens,
                            rounds = outcome.rounds,
                            ok = outcome.ok,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
                dao.trim(SUB_AGENT_HISTORY_KEEP)
            }
        }
    }

    private fun isVisibleInCurrentRun(skillId: String): Boolean {
        val normalized = SkillParser.normalizeSkillLookup(skillId)
        return normalized in runAvailableSkillIds && normalized !in mutatedSkillIds
    }

    private fun nextTurnRequired(skillId: String): String = errorResult(
        "NEXT_TURN_REQUIRED",
        "Skill $skillId 在本轮已安装或变更，将从下一轮对话开始可用",
    )

    private fun installResult(
        result: SkillInstallResult,
        repository: String,
        ref: String,
        commitSha: String,
        selectedPaths: List<String>,
    ): String = when (result) {
        is SkillInstallResult.Success -> {
            pendingSkillConflict.set(null)
            // 安装成功即技能树状态重新确定：不复位这个标记，COMMIT_FAILED 之后本实例内的
            // skills_list / skills_read / skills_read_resource / skills_run 会永久返回 NEXT_TURN_REQUIRED。
            skillTreeMutationUncertain.set(false)
            val installed = JSONArray()
            result.installed.forEach { skill ->
                mutatedSkillIds += SkillParser.normalizeSkillLookup(skill.id)
                installed.put(
                    JSONObject()
                        .put("id", skill.id)
                        .put("name", skill.name),
                )
            }
            JSONObject()
                .put("ok", true)
                .put("repository", repository)
                .put("ref", ref)
                .put("commitSha", commitSha)
                .put("selectedPaths", JSONArray(selectedPaths))
                .put("installed", installed)
                .put("available", "next_turn")
                .put("scriptsExecuted", false)
                .put("message", "Skill 已安装并启用，将从下一轮对话开始可用；安装过程未执行脚本")
                .toString()
        }
        is SkillInstallResult.Conflict -> {
            val conflicts = JSONArray()
            result.conflicts.forEach { conflict ->
                conflicts.put(
                    JSONObject()
                        .put("id", conflict.id)
                        .put("name", conflict.name)
                        .put("replaceAllowed", conflict.replaceAllowed),
                )
            }
            pendingSkillConflict.set(
                result.conflicts.singleOrNull()
                    ?.takeIf { it.replaceAllowed && selectedPaths.size == 1 }
                    ?.let { conflict ->
                        PendingSkillConflictCapability(
                            repository = repository,
                            commitSha = commitSha,
                            selectedPath = selectedPaths.single(),
                            expectedReplacementId = conflict.id,
                            expectedReplacementName = conflict.name,
                        )
                    },
            )
            JSONObject()
                .put("ok", false)
                .put("code", "SKILL_CONFLICT")
                .put("message", "Skill 已存在；可替换的单个用户 Skill 可按返回参数直接重试，内置 Skill 不可覆盖")
                .put("repository", repository)
                .put("ref", ref)
                .put("commitSha", commitSha)
                .put("selectedPaths", JSONArray(selectedPaths))
                .put("conflicts", conflicts)
                .toString()
        }
        is SkillInstallResult.Failure -> {
            if (result.error.code == SkillInstallErrorCode.COMMIT_FAILED) {
                skillTreeMutationUncertain.set(true)
            }
            errorResult(
                code = result.error.code.name,
                message = result.error.message,
            )
        }
    }

    private inline fun skillSourceResult(block: () -> String): String = try {
        block()
    } catch (failure: GitHubSkillSourceException) {
        errorResult(failure.code, failure.message ?: "GitHub Skill 请求失败")
    }

    private fun errorResult(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private fun textResult(content: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(content)

    private data class ScreenPoint(val x: Int, val y: Int)

    private class InvalidToolArgumentException(message: String) : IllegalArgumentException(message)

    private data class PublishedObservation(
        val elements: RootShellDeviceController.ElementObservation? = null,
        val coordinateSpace: RootShellDeviceController.CoordinateSpace? = null,
    )

    private data class GitHubInspectionSnapshot(
        val commitSha: String,
        val prefix: String?,
        val candidatesByPath: Map<String, String>,
    )

    private fun showTap(x: Int, y: Int) {
        GestureIndicator.showTap(context, x, y)
    }

    private fun showLongPress(x: Int, y: Int, durationMs: Int) {
        GestureIndicator.showLongPress(context, x, y, durationMs)
    }

    private fun showSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        GestureIndicator.showSwipe(context, x1, y1, x2, y2, durationMs)
    }

    private data class AppInfo(
        val packageName: String,
        val appName: String,
        val isSystemApp: Boolean = false
    )

    internal companion object {
        /** 一次检索最多返回多少条：上限防止模型一次把整个库拉进上下文。 */
        const val MAX_WORLD_RECALL_LIMIT = 20

        /** 一次最多列出多少个委派节点。 */
        const val MAX_TRACE_LIST = 40

        /** 单次返回的委派正文上限，防止一次把整棵树的消息流拉进上下文。 */
        const val MAX_TRACE_CONTENT_CHARS = 8000

        val DEVICE_DIRECT_TOOL_NAMES = setOf(
            "set_alarm",
            "set_timer",
            "device_status",
            "network_info",
            "top_memory_apps",
            "top_storage_apps",
            "media_control",
            "set_volume",
        )
        internal val DEVICE_SENSITIVE_READ_TOOL_NAMES = setOf(
            "get_setting",
            "wifi_credentials",
            "recent_notifications",
            "search_notification_history",
            "recent_app_activity",
            "app_usage_summary",
            "get_current_location",
            "get_device_environment",
            "list_alarms",
            "list_active_timers",
            "search_clipboard_history",
            "get_health_summary",
            "read_sms_code",
            "get_logcat",
            "search_media",
            "search_audio",
            "search_recordings",
            "search_files",
            "search_calendar_events",
            "search_contacts",
            "search_call_history",
            "search_messages",
            "search_downloads",
            "search_coloros_notes",
            "search_coloros_recordings",
            "search_recording_summaries",
            "search_coloros_memories",
            "search_saved_places",
            "search_personal_orders",
            "search_qq_chat_images",
            "search_wechat_chat_images",
        )
        val DEVICE_SENSITIVE_ACTION_TOOL_NAMES = setOf(
            "set_setting",
            "set_device_state",
            "app_state_control",
        )
        val DEVICE_TOOL_NAMES =
            DEVICE_DIRECT_TOOL_NAMES + DEVICE_SENSITIVE_READ_TOOL_NAMES +
                DEVICE_SENSITIVE_ACTION_TOOL_NAMES
        val MEMORY_TOOL_NAMES = setOf("memory_get", "memory_write")
        val SUPPORTED_SKILL_REQUIREMENTS = setOf("root", "linux")
        /** 会按路径读取内容的工具；凭据路径在这些工具上直接拒绝。 */
        val CREDENTIAL_PATH_TOOL_NAMES = setOf(
            "read_file", "read_files", "read_image", "list_directory", "search_code",
            "edit_file", "edit_files", "write_file",
        )
    }
}

private const val MAX_SEQUENCE_STEPS = 12

/** 流程名白名单长度上限：save_flow 与 use_flow 共用，避免两边校验不一致。 */
private const val MAX_FLOW_NAME_CHARS = 80
private const val SEQUENCE_RESULT_CHARS = 200

private const val STALE_CONTENT_CODE = "STALE_CONTENT"

private val FLOW_SAFE_ACTIONS = setOf(
    "tap_text", "input", "replace", "clear", "press", "wait",
    "wait_text", "wait_package", "swipe", "scroll",
)

private const val SEQUENCE_SETTLE_AFTER_TAP_MS = 350L
private const val SEQUENCE_SETTLE_AFTER_SCROLL_MS = 650L
private const val SEQUENCE_INPUT_RETRY_DELAY_MS = 400L
private const val SEQUENCE_NO_SELECTION_CODE = "TEXT_SELECTION_UNAVAILABLE"

/** 任务清单允许的状态值。 */
private val TASK_PLAN_STATUSES = setOf("pending", "in_progress", "completed")

/** Skill 声明式命令的默认与上限超时，以及附加参数的长度上限。 */
private const val DEFAULT_SKILL_COMMAND_TIMEOUT_SECONDS = 300
private const val MAX_SKILL_COMMAND_TIMEOUT_SECONDS = 600
private const val MAX_SKILL_COMMAND_ARGUMENTS = 1_000

/** SKILL.md 正文超过该长度时提示拆分为按需读取的 references 文件。 */
private const val SKILL_BODY_SPLIT_HINT_CHARS = 12_000

/** find_files 的取值范围与拒绝的 Shell 元字符。 */
private const val DEFAULT_FILE_WORKSPACE = "/data/local/tmp/sta"
private const val DEFAULT_FIND_LIMIT = 80
private const val MAX_FIND_LIMIT = 200
private val FORBIDDEN_SHELL_CHARS = setOf('\'', '"', ';', '|', '&', '$', '`', '>', '<', '\n')

/** BusyBox grep -E 报正则错误时的特征文本。 */
private val BUSYBOX_REGEX_ERROR_MARKERS = listOf(
    "Invalid regular expression",
    "Unmatched",
    "invalid character range",
    "Trailing backslash",
)

/** 子智能体入口的取值范围，与 AgentSubAgentToolCatalog 的 schema 保持一致。 */
private const val MAX_SUB_AGENT_TASK_CHARS = 2_000
private const val MAX_SUB_AGENT_CONTEXT_CHARS = 4_000

/** 附给子智能体的方案摘要长度上限；太长会把子智能体自己的背景挤满。 */
private const val SUB_AGENT_PLAN_DIGEST_CHARS = 600

/** 附给子智能体的方案步骤条数上限。 */
private const val SUB_AGENT_PLAN_STEPS = 4

/** 多角色并行上限，与 AgentSubAgentRunner 的并行上限一致：再多不会更快，只会放大成本。 */
private const val MAX_SUB_AGENT_ROLES = 3

/**
 * 单次派发的总 token 预算闸门。
 *
 * 每个角色的预算按档位算（deep 档默认 55k），多角色是线性叠加；没有这道闸门时，
 * 一次 3 角色的 deep 派发就能吃掉整轮会话的预算。
 */
private const val SUB_AGENT_TOTAL_TOKEN_CAP = 120_000
private const val DEFAULT_SUB_AGENT_ROLE = AgentSubAgentRoles.DEFAULT

/** 写入模式缺省的目标仓库；实际使用时主智能体应按当前任务传 repo_path。 */
private const val DEFAULT_SUB_AGENT_REPO = "/workspace/Sta-src"

/** worktree 命令的失败标记：宿主执行器在非零退出时会在输出里带上它。 */
private const val CREATE_FAILURE_MARKER = "[exit "

/** 回给模型的命令输出上限：够看清失败原因，又不至于把整段日志搬进上下文。 */
private const val SUB_AGENT_SHELL_OUTPUT_CHARS = 600

/** worktree 命令超时：建 worktree 要检出全仓库，给足余量。 */
private const val WORKTREE_COMMAND_TIMEOUT_SECONDS = 120

/** 预算评估取最近多少条同档位样本；太多会让旧习惯拖住新任务。 */
private const val SUB_AGENT_HISTORY_SAMPLES = 12

/** 样本表保留上限，超出按时间裁掉最旧的。 */
private const val SUB_AGENT_HISTORY_KEEP = 400
