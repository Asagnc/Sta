package io.github.asagnc.sta.agent.runtime

import android.content.Context
import io.github.asagnc.sta.agent.accessibility.AgentAccessibilityKeeper
import io.github.asagnc.sta.agent.model.AgentConversationCodec
import io.github.asagnc.sta.agent.model.AgentConversationToolCatalog
import io.github.asagnc.sta.agent.tool.ConversationHistoryTool
import io.github.asagnc.sta.data.db.StaDatabase
import io.github.asagnc.sta.agent.model.AgentModelClient
import io.github.asagnc.sta.agent.model.AgentModelExecutionException
import io.github.asagnc.sta.agent.model.AgentRunStats
import io.github.asagnc.sta.agent.model.AgentSubAgentRunner
import io.github.asagnc.sta.agent.model.SubAgentMailbox
import io.github.asagnc.sta.agent.model.SubAgentMailboxPolicy
import io.github.asagnc.sta.agent.model.AgentSubAgentToolScope
import io.github.asagnc.sta.agent.model.AgentToolCatalog
import io.github.asagnc.sta.agent.model.AgentWorkspaceManifest
import io.github.asagnc.sta.agent.model.ProviderClientFactory
import io.github.asagnc.sta.agent.model.AgentModelFailure
import io.github.asagnc.sta.agent.model.AgentHttpClient
import io.github.asagnc.sta.agent.memory.AgentMemoryContext
import io.github.asagnc.sta.agent.memory.AgentMemoryContextBuilder
import io.github.asagnc.sta.agent.roleplay.CharacterMemoryTools
import io.github.asagnc.sta.agent.roleplay.RoleplayRunContext
import io.github.asagnc.sta.agent.mcp.McpRunSnapshot
import io.github.asagnc.sta.agent.mcp.McpToolExecutor
import io.github.asagnc.sta.agent.mcp.RoutingToolExecutor
import io.github.asagnc.sta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.asagnc.sta.agent.skill.SkillCompatibilityChecker
import io.github.asagnc.sta.agent.skill.SkillContext
import io.github.asagnc.sta.agent.skill.SkillRuntime
import io.github.asagnc.sta.agent.skill.PublicGitHubSkillSource
import io.github.asagnc.sta.agent.terminal.LinuxSandboxView
import io.github.asagnc.sta.agent.tool.AgentLocalTools
import io.github.asagnc.sta.agent.tool.AgentToolRequirements
import io.github.asagnc.sta.agent.tool.AgentToolCapabilities
import io.github.asagnc.sta.agent.tool.PendingSkillConflictCapabilityParser
import io.github.asagnc.sta.agent.tool.ToolExecutionDecision
import io.github.asagnc.sta.core.AndroidAgentLogger
import io.github.asagnc.sta.core.safeLogType
import io.github.asagnc.sta.data.repository.AgentMemoryRepository
import io.github.asagnc.sta.data.world.WorldKnowledgeStore
import io.github.asagnc.sta.data.world.WorldTraceStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 Runtime run 的阻塞执行器。
 *
 * 它只拥有模型、工具和终态提交，不持有 Service、Messenger、Compose 或 WindowManager 状态。
 * 所有外部副作用都通过窄回调交回宿主。
 */
internal class AgentRuntimeRunExecutor(
    context: Context,
    private val currentPermissions: () -> AgentRuntimePolicy.Permissions,
    private val snapshotRequest: (AgentRuntimeWire.RunRequest) -> AgentRuntimeWire.RunRequest,
    private val onAcceptedEvent: (AgentEvent) -> Unit,
    private val persistArtifacts: (
        AgentRuntimeWire.RunRequest,
        AgentRuntimeWire.RunResult,
        List<AgentEvent>,
    ) -> Unit,
) {
    data class Outcome(
        val result: AgentRuntimeWire.RunResult,
        val completedRequest: AgentRuntimeWire.RunRequest? = null,
        val response: AgentModelClient.ModelResponse.Text? = null,
        val shouldUpdateHost: Boolean,
    )

    private val appContext = context.applicationContext

    private companion object {
        /** 委派树的发起者标记：主循环派出的节点以此记 agent 字段。 */
        const val AGENT_SUB = "subagent"

        /** 主工作区根目录（Android 侧形态），用于归一子智能体证据里的相对路径。 */
        const val WORKSPACE_ROOT = "/data/local/tmp/sta"
    }

    fun execute(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
    ): Outcome {
        val runController = session.controller
        // 空间坐标从无到有后，历史观测的 scope 是空串；每次 run 启动时做一次幂等维护
        // （回填坐标 + 回收形态不可复用的存量条目，第二次影响 0 行）。放在这里而不是
        // 启动页，是因为 run 启动点已经保证了 appContext 可用，且观测库本来就在这条
        // 路径上被使用；而清除只挂在写入路径上的话，只读的 run 永远不会触发它。
        WorldKnowledgeStore.maintain(appContext, WORKSPACE_ROOT)
        val archivedEvents = mutableListOf<AgentEvent>()
        var toolExecutor: AutoCloseable? = null
        var localTools: AgentLocalTools? = null
        val mailbox = SubAgentMailbox(StaDatabase.get(appContext))
        var toolsBinding: AgentRunController.ResourceBinding? = null
        var response: AgentModelClient.ModelResponse.Text? = null
        var cancelled = false
        var checkpointRecorder: AgentRunCheckpointRecorder? = null
        val timing = AgentRunTiming(AndroidAgentLogger)

        val result = try {
            checkpointRecorder = AgentRunCheckpointRecorder.create(appContext, request)
            val skillIndexService = SkillRuntime.createIndexService(appContext)
            val skillLoader = SkillRuntime.createLoader(appContext)
            val skillResourceReader = SkillRuntime.createResourceReader(appContext)
            val skillPackageInstaller = SkillRuntime.createPackageInstaller(appContext)
            val githubSkillSource = PublicGitHubSkillSource(
                cacheRoot = appContext.cacheDir,
                baseClient = AgentHttpClient.client,
            )
            val skillContext = SkillContext(
                installedSkills = skillIndexService.listInstalledSkills()
                    .filter { SkillCompatibilityChecker.evaluate(it).available },
            )
            val memoryEnabled = runBlocking { AgentMemoryRepository.isEnabled() }
            val uiPayload = request.handoff
                ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
                ?.let { AgentUiHandoffPayload.from(it.payload) }
            val conversationId = uiPayload?.conversationId
                ?.takeIf { it.isNotBlank() }
            val roleplayContext = conversationId?.let { id ->
                runBlocking { RoleplayRunContext.resolve(appContext, id, request.config.contextWindow, memoryEnabled) }
            }
            if (request.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
                require(roleplayContext != null) { "只有角色会话可以改写角色回复" }
                val target = request.rewriteTargetMessageId?.takeIf { it.isNotBlank() && it.length <= 256 }
                    ?: throw IllegalArgumentException("缺少有效的角色回复目标")
                require(runBlocking {
                    StaDatabase.get(appContext).conversationDao().hasAssistantMessage(conversationId, target)
                }) { "角色回复目标不存在或不属于当前会话" }
            }
            val characterMemoryTools = roleplayContext?.let { roleplay ->
                CharacterMemoryTools(appContext, roleplay.characterId) {
                    runBlocking { AgentMemoryRepository.isEnabled() }
                }
            }
            val memoryContext = if (memoryEnabled) {
                runCatching {
                    AgentMemoryContextBuilder.build(
                        snapshot = AgentMemoryRepository.snapshot(),
                        contextWindow = request.config.contextWindow,
                        // 作用域匹配用的任务文本：最近几条消息的正文与工具参数（见 taskHint）。
                        hint = AgentMemoryContextBuilder.taskHint(request.history),
                    )
                }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_context_failed") {
                        "Agent memory context unavailable: type=${throwable.safeLogType()}"
                    }
                    AgentMemoryContextBuilder.empty(request.config.contextWindow)
                }
            } else {
                AgentMemoryContext.DISABLED
            }
            // 记忆里若有「压缩指令」章节，让它随 config 一路传到压缩器（AgentContextCompactor）。
            val effectiveConfig = if (memoryContext.compactInstructions.isBlank()) {
                request.config
            } else {
                request.config.copy(compactInstructions = memoryContext.compactInstructions)
            }
            val pendingSkillConflict = PendingSkillConflictCapabilityParser.parse(request.history)
            val mcpSnapshot = runBlocking {
                runCatching { McpRunSnapshot.load() }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_mcp_snapshot_failed") {
                        "MCP tool snapshot unavailable: type=${throwable.safeLogType()}"
                    }
                    McpRunSnapshot.EMPTY
                }
            }
            val mcpTools = JSONArray().also(mcpSnapshot::appendModelTools)
            val runStats = AgentRunStats()
            // 子智能体只拿到文件检索类工具，且不允许递归派生；它复用主 run 的工具执行器，
            // 所以这里用可空引用延迟绑定，避免与 AgentLocalTools 构造顺序互相依赖。
            var toolExecutorRef: RoutingToolExecutor? = null
            val subAgentRunner = AgentSubAgentRunner(
                config = effectiveConfig,
                provider = ProviderClientFactory.getClient(request.config),
                runController = runController,
                onEvent = { event ->
                    acceptEvent(
                        session,
                        event,
                        archivedEvents,
                        checkpointRecorder,
                    )
                },
                parentTools = AgentToolCatalog.build(
                    terminalTools = request.config.terminalTools,
                    browserTools = false,
                    deviceDirectTools = false,
                    deviceSensitiveReadTools = false,
                    deviceSensitiveActionTools = false,
                    memoryTools = false,
                    capabilities = AgentToolCapabilities.capture(appContext),
                ),
                traceSink = { record ->
                    WorldTraceStore.record(
                        appContext,
                        WorldTraceStore.Node(
                            id = record.id,
                            parentId = record.parentId,
                            traceId = request.runId,
                            runId = request.runId,
                            sessionId = request.effectiveModelSessionId,
                            agent = AGENT_SUB,
                            role = record.role,
                            startedAt = record.startedAt,
                            endedAt = record.endedAt,
                            status = record.status,
                            summary = record.summary,
                            content = record.content,
                            // 工作区根用于把相对路径证据归一成可读路径，进而算出依赖指纹。
                            workspaceRoot = WORKSPACE_ROOT,
                        ),
                    )
                },
                mailbox = mailbox,
                mailboxPost = { author, runId, summary, body ->
                    val posted = mailbox.post(runId, author, SubAgentMailboxPolicy.KIND_NOTE, summary, body)
                    when (posted) {
                        is SubAgentMailbox.Post.Stored -> JSONObject()
                            .put("ok", true)
                            .put("message", "已投递给同伴（id=${posted.id}）")
                            .toString()

                        SubAgentMailbox.Post.Duplicate -> JSONObject()
                            .put("ok", false)
                            .put("code", "MAILBOX_DUPLICATE")
                            .put("message", "这条发现已经有人投过了，直接看信箱里的内容即可")
                            .toString()

                        SubAgentMailbox.Post.Throttled -> JSONObject()
                            .put("ok", false)
                            .put("code", "MAILBOX_THROTTLED")
                            .put("message", "本轮留言已达上限（${SubAgentMailboxPolicy.MAX_NOTES_PER_RUN} 条），" +
                                "请只在收尾时把结论写进摘要")
                            .toString()

                        SubAgentMailbox.Post.Empty -> JSONObject()
                            .put("ok", false)
                            .put("code", "MAILBOX_EMPTY")
                            .put("message", "summary 与 body 不能同时为空")
                            .toString()
                    }
                },
                toolExecutorFor = { allowed, workspace, sandbox ->
                    AgentModelClient.ToolExecutor { call ->
                        val delegate = toolExecutorRef
                        when {
                            delegate == null || call.name !in allowed -> AgentModelClient.ToolResult(
                                JSONObject()
                                    .put("ok", false)
                                    .put("code", "SUB_AGENT_TOOL_FORBIDDEN")
                                    .put("message", "子智能体不能使用 ${call.name}")
                                    .toString(),
                            )

                            workspace == null -> delegate.executeWithSandbox(call, sandbox)

                            else -> {
                                // 带写权限时必须把参数收进它的 worktree：模型天然会写相对路径，
                                // 相对路径在宿主侧默认落到主工作区，光靠 system prompt 约束不住。
                                when (
                                    val scoped =
                                        AgentSubAgentToolScope.scope(call.name, call.argumentsJson, workspace)
                                ) {
                                    is AgentSubAgentToolScope.Scoped.Ok ->
                                        delegate.executeWithSandbox(
                                            call.copy(argumentsJson = scoped.argumentsJson),
                                            sandbox,
                                        )

                                    is AgentSubAgentToolScope.Scoped.Rejected -> AgentModelClient.ToolResult(
                                        JSONObject()
                                            .put("ok", false)
                                            .put("code", scoped.code)
                                            .put("message", scoped.message)
                                            .toString(),
                                    )
                                }
                            }
                        }
                    }
                },
            )
            // 计划只存在对话状态里，跨轮就丢了：本轮开始时先把上次的清单读进来，随请求注入，
            // 之后由 task_plan 工具的更新回调保持最新。
            var latestTaskPlan: String? = conversationId?.let { id ->
                runBlocking { StaDatabase.get(appContext).conversationDao().taskPlanJson(id) }
            }
            // 方案同理：存在会话行上，本轮开始时读进来注入，之后由 submit_plan 的回调保持最新。
            var latestPlan: String? = conversationId?.let { id ->
                runBlocking { StaDatabase.get(appContext).conversationDao().planJson(id) }
            }
            val executor = AgentLocalTools(
                context = appContext,
                logger = AndroidAgentLogger,
                browserRunId = request.runId,
                browserToolsEnabled = {
                    request.config.browserTools && currentPermissions().browserTools
                },
                terminalToolsEnabled = {
                    request.config.terminalTools && currentPermissions().terminalTools
                },
                deviceDirectToolsEnabled = {
                    request.config.deviceDirectTools && currentPermissions().deviceDirectTools
                },
                deviceSensitiveReadToolsEnabled = {
                    request.config.deviceSensitiveReadTools &&
                        currentPermissions().deviceSensitiveReadTools
                },
                deviceSensitiveActionToolsEnabled = {
                    request.config.deviceSensitiveActionTools &&
                        currentPermissions().deviceSensitiveActionTools
                },
                memoryToolsEnabled = {
                    runBlocking { AgentMemoryRepository.isEnabled() }
                },
                memoryWritable = roleplayContext == null,
                screenshotExcludedPackages = { emptySet() },
                beforeToolExecution = { toolName ->
                    // 只有需要无障碍的工具才做门禁。[AgentAccessibilityKeeper] 返回非空结果，
                    // 是否可用看 available，不需要再判空（此处曾有 `!= null &&`，恒真）。
                    if (!AgentToolRequirements.requiresAccessibility(toolName)) {
                        ToolExecutionDecision.Allow
                    } else {
                        val accessibility =
                            AgentAccessibilityKeeper.ensureEnabledForGuiOperation(appContext)
                        if (accessibility.available) {
                            ToolExecutionDecision.Allow
                        } else {
                            ToolExecutionDecision.Reject(
                                code = accessibility.code,
                                message = accessibility.message,
                            )
                        }
                    }
                },
                skillIndexService = skillIndexService,
                skillLoader = skillLoader,
                skillResourceReader = skillResourceReader,
                githubSkillSource = githubSkillSource,
                skillPackageInstaller = skillPackageInstaller,
                runAvailableSkillIds = skillContext.installedSkills.mapTo(mutableSetOf()) { it.id },
                pendingSkillConflict = pendingSkillConflict,
                runStatsSummary = { runStats.summaryText() },
                subAgentRunner = subAgentRunner,
                mailboxRunId = request.runId,
                mailbox = mailbox,
                onTaskPlanUpdated = { planJson ->
                    latestTaskPlan = planJson
                    acceptEvent(
                        session,
                        AgentEvent.TaskPlanUpdated(planJson),
                        archivedEvents,
                        checkpointRecorder,
                    )
                },
                planSnapshot = { latestPlan },
                onPlanUpdated = { planJson ->
                    latestPlan = planJson
                    acceptEvent(
                        session,
                        AgentEvent.PlanUpdated(planJson),
                        archivedEvents,
                        checkpointRecorder,
                    )
                },
            )
            val routingExecutor = RoutingToolExecutor(
                local = executor,
                mcp = McpToolExecutor(mcpSnapshot),
            )
            toolExecutor = routingExecutor
            toolExecutorRef = routingExecutor
            localTools = executor
            toolsBinding = runController.register(routingExecutor::close)
            timing.preparationFinished(skillContext.installedSkills.size)
            val historyTool = conversationId?.let { id ->
                ConversationHistoryTool {
                    val checkpoint = runBlocking { StaDatabase.get(appContext).conversationDao().contextCheckpoint(id) }
                    val journal = AgentConversationCodec.decodeTranscript(checkpoint?.journalJson)
                        .ifEmpty { AgentConversationCodec.decodeTranscript(checkpoint?.historyJson) }
                    journal + session.transcript
                }
            }
            val runTools = JSONArray(mcpTools.toString()).also { tools ->
                if (historyTool != null) tools.put(AgentConversationToolCatalog.schema())
                tools.put(AgentConversationToolCatalog.compactSchema())
                if (characterMemoryTools != null && memoryEnabled) CharacterMemoryTools.appendSchemas(tools)
            }
            val runToolExecutor = AgentModelClient.ToolExecutor { call ->
                if (call.name == AgentConversationToolCatalog.READ_HISTORY && historyTool != null) {
                    historyTool.execute(call)
                } else if (call.name == AgentConversationToolCatalog.COMPACT_CONTEXT) {
                    // 压缩由 AgentLoop 在本批工具结果并入历史后执行；这里只回执并把指令带出去。
                    AgentModelClient.ToolResult(JSONObject()
                        .put("ok", true)
                        .put("code", "COMPACT_REQUESTED")
                        .put("instructions", AgentConversationToolCatalog.instructionsOf(call.argumentsJson))
                        .put("message", "压缩请求已受理：本批工具结果并入历史后立即压缩上下文。")
                        .toString())
                } else if (call.name in CharacterMemoryTools.NAMES && characterMemoryTools != null) {
                    characterMemoryTools.execute(call)
                } else routingExecutor.execute(call)
            }
            val completedResponse = AgentModelClient.complete(
                config = effectiveConfig,
                sessionId = request.effectiveModelSessionId,
                operationId = request.runId,
                initialUserMessageId = uiPayload?.promptMessageId(request.runId) ?: "user-${request.runId}",
                initialSupplementIndex = uiPayload?.lastSupplementIndex ?: 0,
                roleplayContext = roleplayContext,
                taskPlanSnapshot = { latestTaskPlan },
                planSnapshot = { latestPlan },
                // 开局扫一次工作区清单：只算一次，供系统提示的缓存前缀用（见 AgentWorkspaceManifest）。
                workspaceManifestLookup = {
                    AgentWorkspaceManifest.render(AgentWorkspaceManifest.scan(File(WORKSPACE_ROOT)))
                },
                rewriteReply = request.operation == AgentRuntimeWire.OP_REWRITE_REPLY,
                compactOnly = request.operation == AgentRuntimeWire.OP_COMPACT,
                compactUntilMessageId = request.compactUntilMessageId,
                onContextSnapshot = { snapshot ->
                    val committed = snapshot.copy(operationId = request.runId)
                    AgentRunCheckpointStore.saveContext(appContext, request.runId, committed)
                    session.updateContext(committed)
                },
                onTranscript = { transcript ->
                    AgentRunCheckpointStore.saveTranscript(appContext, request.runId, transcript)
                    session.updateTranscript(transcript)
                },
                capabilitiesProvider = { AgentToolCapabilities.capture(appContext) },
                prompt = request.prompt,
                toolExecutor = runToolExecutor,
                runStats = runStats,
                images = request.images,
                history = request.history,
                runController = runController,
                skillContext = skillContext,
                memoryContext = memoryContext,
                additionalTools = runTools,
                worldContext = appContext,
                onEvent = { event ->
                    timing.accept(event)
                    acceptEvent(
                        session,
                        event,
                        archivedEvents,
                        checkpointRecorder,
                    )
                },
            )
            response = completedResponse
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = true,
                content = completedResponse.content,
                reasoningContent = completedResponse.reasoningContent,
                transcript = completedResponse.transcript,
                contextSnapshot = completedResponse.contextSnapshot?.copy(operationId = request.runId),
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } catch (throwable: Throwable) {
            cancelled = runController.isCancelled || throwable is AgentRunCancelledException
            val modelFailure = throwable as? AgentModelExecutionException
            val message = if (cancelled) {
                AgentRuntimeWire.STOPPED_ERROR_MESSAGE
            } else {
                throwable.message ?: throwable.javaClass.simpleName
            }
            if (cancelled) {
                AndroidAgentLogger.info("Agent runtime stopped")
            } else {
                val requestFailure = modelFailure?.cause as? AgentModelFailure
                AndroidAgentLogger.error(
                    "Agent runtime failed: type=${throwable.safeLogType()}, " +
                        "model_code=${requestFailure?.code.orEmpty()}, " +
                        "cause_type=${requestFailure?.cause?.safeLogType().orEmpty()}"
                )
                val event = AgentEvent.RunFailed(message)
                runCatching {
                    acceptEvent(
                        session,
                        event,
                        archivedEvents,
                        checkpointRecorder,
                    )
                }.onFailure { checkpointFailure ->
                    AndroidAgentLogger.error(
                        "Agent runtime failure checkpoint failed: " +
                            "type=${checkpointFailure.safeLogType()}"
                    )
                    session.emit(event)
                }
            }
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = message,
                reasoningContent = modelFailure?.reasoningContent.orEmpty(),
                transcript = modelFailure?.transcript.orEmpty(),
                contextSnapshot = modelFailure?.contextSnapshot?.copy(operationId = request.runId) ?: session.contextSnapshot,
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } finally {
            // 子智能体的隔离工作区保留到这一刻才回收：保留期间主智能体才能回去看改动细节。
            // 清理失败不影响 run 结果——它是收尾动作，不该把成功的运行变成失败。
            runCatching { localTools?.cleanupWorktrees() }
            // 信箱按 run 隔离，run 结束即清空：留着只会占空间，且下一轮 run 有自己的分区。
            runCatching { runBlocking { mailbox.clear(request.runId) } }
            runCatching { toolsBinding?.close() }
            runCatching { toolExecutor?.close() }
        }

        if (cancelled && session.isTerminal) {
            runCatching {
                persistArtifacts(snapshotRequest(request), result, archivedEvents)
            }.onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime cancelled result persistence failed: " +
                        "type=${throwable.safeLogType()}"
                )
            }
            return Outcome(
                result = result,
                shouldUpdateHost = true,
            )
        }

        val completedRequest = runCatching { snapshotRequest(request) }
            .getOrElse { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime request snapshot failed: type=${throwable.safeLogType()}"
                )
                request
            }
        val committed = session.complete(result) {
            runCatching { checkpointRecorder?.seal() }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime checkpoint seal failed: type=${throwable.safeLogType()}"
                    )
                }
            runCatching { persistArtifacts(completedRequest, result, archivedEvents) }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime artifact persistence failed: type=${throwable.safeLogType()}"
                    )
                }
        }
        return Outcome(
            result = result,
            completedRequest = completedRequest.takeIf { committed },
            response = response.takeIf { committed },
            shouldUpdateHost = committed,
        )
    }

    private fun acceptEvent(
        session: AgentRuntimeSession,
        event: AgentEvent,
        archivedEvents: MutableList<AgentEvent>,
        checkpointRecorder: AgentRunCheckpointRecorder?,
    ) {
        checkpointRecorder?.accept(event)
        if (!session.emit(event)) return
        archivedEvents += event
        if (event is AgentEvent.ModelRetryScheduled) {
            AndroidAgentLogger.warn("Agent runtime event: ${event.toLogLine()}")
        } else if (event !is AgentEvent.AssistantBlockDelta) {
            AndroidAgentLogger.debug { "Agent runtime event: ${event.toLogLine()}" }
        }
        runCatching { onAcceptedEvent(event) }
            .onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_event_projection_failed") {
                    "Agent runtime event projection failed: type=${throwable.safeLogType()}"
                }
            }
    }
}
