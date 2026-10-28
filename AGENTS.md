# AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## Project Overview

PaiAgent-one is an enterprise-grade AI workflow orchestration platform with visual flow editor. It uses a custom DAG (Directed Acyclic Graph) engine to execute workflows composed of LLM nodes (OpenAI, DeepSeek, Qwen) and tool nodes (TTS, etc.).

## Build & Development Commands

### Backend (Spring Boot)
```bash
cd backend
./mvnw spring-boot:run              # Start backend server (default port 8084)
./mvnw clean package                # Build JAR package
./mvnw test                         # Run all tests
./mvnw test -Dtest=ClassName        # Run single test class
./mvnw test -Dtest=ClassName#methodName  # Run single test method
```

Integration tests (`ExecutionConsistencyTest`, `ParallelSchedulingTest`, `EngineContractTest`, `SecretProtectionTest`, …) run against the real local MySQL (root/123456, db `paiagent`) and clean up after themselves. The build needs JDK 21 — on some machines `/usr/libexec/java_home -v 21` silently returns another JDK, so set `JAVA_HOME` explicitly.

### Frontend (React + Vite)
```bash
cd frontend
npm install                         # Install dependencies
npm run dev                         # Start dev server (port 5173)
npm run build                       # Build for production (TypeScript check + Vite build)
npm run lint                        # Run ESLint
npm run preview                     # Preview production build
npm test                            # Unit tests for the pure logic in src/utils (node:test, needs Node >= 22.18)
```

### MCP Server (`mcp-server/`, TypeScript, Node >= 22.18)
```bash
cd mcp-server
npm install
npm test                            # 57 tests (node:test, runs .ts directly); needs no backend, uses tests/fake-paiagent.ts
node scripts/export-tool-schemas.ts start_workflow   # the same tool definition as MCP / OpenAI function / Anthropic tool JSON
npm run typecheck && npm run build  # build to dist/ (run it with `node dist/index.js`)
PAIAGENT_USERNAME=admin PAIAGENT_PASSWORD=admin123 node scripts/demo-client.ts [workflowId "input"]   # real client transcript against a running backend
node scripts/eval.ts                # 13 tasks driven by a real agent (headless `claude`, must be logged in on the CLI; set HTTPS_PROXY if the network needs a proxy); results in eval/results.json
```

### Database Setup
```bash
mysql -u root -p < backend/src/main/resources/schema.sql
```

### Environment Configuration

**Backend:** Copy `backend/.env.example` to `backend/.env` and configure:
- `MYSQL_PASSWORD` - Required for database connection
- `JWT_SECRET` - Required for token signing (min 32 chars for production)
- `APP_AUTH_DEFAULT_USERNAME/PASSWORD` - Default admin account (default: admin/admin123)
- `SERVER_PORT` - Backend port (default: 8084)
- `PAIAGENT_ENCRYPTION_KEY` - Master key for encrypting API keys at rest (Base64 of 32 random bytes, or any passphrase). **Set it explicitly in production.** If empty, a key file is auto-created at `~/.paiagent/secret.key` (dev only; losing it makes stored keys unreadable)
- Optional tuning (see `application.yml`): `PAIAGENT_PARALLEL_ENABLED`, `PAIAGENT_EXEC_POOL_SIZE/QUEUE`, `PAIAGENT_NODE_POOL_SIZE/QUEUE`, `PAIAGENT_STALE_AFTER_MINUTES`, `PAIAGENT_LLM_MAX_CONCURRENT`, `PAIAGENT_LLM_PROXY_HOST` / `PAIAGENT_LLM_PROXY_PORT` (HTTP proxy for LLM calls; the JDK HTTP client ignores the system proxy; empty / 0 = direct)

**Frontend:** Copy `frontend/.env.example` to `frontend/.env.local` and configure:
- `VITE_API_BASE_URL` - Backend API URL (default: /api, proxied to localhost:8084)
- `VITE_API_PROXY_TARGET` - Override proxy target for local dev

## Architecture

### Backend Structure (`backend/src/main/java/com/paiagent/`)

**Core DAG Engine (`engine/`):**
- `WorkflowEngine.java`: Main orchestration engine that executes workflows end-to-end
- `EngineSelector.java`: Routes to DAG or LangGraph engine based on `engineType` field
- `dag/DAGParser.java`: Parses workflow config into DAG, performs topological sorting using Kahn's algorithm, and detects cycles using DFS
- `langgraph/`: LangGraph4j-based state graph engine (core **1.8.0-beta3**). Same observable behaviour as the DAG engine (condition routing, skipped branches, failure semantics, snapshots) — enforced by `EngineContractTest`. The graph is built per dependency layer: `fan → nodes (LangGraph native parallel branches) → join`, with a failure-aware conditional edge between layers; condition-branch skipping is decided by the nodes themselves because native parallel branches forbid conditional edges, multi-node branches and branches that converge on different nodes (verified by probing). Node actions must hand work to the node pool and return an unfinished future, otherwise LangGraph invokes them one after another. Do not re-add `langgraph4j-core-jdk8:1.1.5`: it shadowed `StateGraph` and silently pinned the project to a version without parallel branches (`LangGraphDependencyTest` guards this)
- `NodeRunner.java` / `ExecutionRecorder.java` / `support/ExecutionSupport.java`: node execution, persistence (snapshots, status CAS) and shared semantics used by both engines
- `ExecutionDispatcher.java`: bounded execution pool; overload is rejected fast (HTTP code 429) instead of queueing without limit
- `StaleExecutionReaper.java`: marks RUNNING executions with no heartbeat as FAILED so they can be resumed
- `service/VideoTaskCheckpointService.java` + `VideoGenerateNodeExecutor`: persists the provider `taskId` with a request fingerprint (`video_generation_task`) so resuming a timed-out video node reuses the old task instead of submitting (and paying for) a new one. Same parameters → fail fast with the structured error code `VIDEO_TASK_PENDING` and let the UI ask continue/regenerate; changed parameters → cancel the old task and resubmit. The choice travels in the resume request's `modifiedVariables`
- `skill/`: Skills system with YAML Frontmatter + Markdown declarations, three-level progressive loading
- `executor/impl/ReActAgentNodeExecutor.java` + `agent/NativeToolCallback.java`: ReAct loop. Default `toolCallMode=native` sends the tools as the provider's `tools` parameter with `proxyToolCalls=true` (Spring AI must NOT execute them: step limit, trace, progress events and error observations belong to this loop; `NativeToolCallback.call` throws on purpose). `toolCallMode=prompt` keeps the old "answer with a JSON decision" protocol for models without tool support, but an unparsable reply is a `format_error` step (2 in a row fail the node), never silently the final answer. Bad tool name / arguments / tool exceptions go back to the model as `{error, recoverable:true}` observations. Verified against a fake OpenAI-compatible server (`ScriptedOpenAiServer`) and, on 2026-10-28, against real DeepSeek (`deepseek-chat`) with one no-argument tool (`read_current_input`), once per engine: the model answered with a native `tool_call` (provider call id), then a final answer. Not verified against a real provider: `prompt` mode, several or parallel tools, bad-argument recovery, other providers
- `engine/cancel/` + `NodeRunner`: execution cancellation (`POST /api/workflows/{id}/executions/{eid}/cancel`, `ExecutionCanceller`). Status flow `RUNNING → CANCELLING → CANCELLED` via SQL-guarded transitions (`requestCancel`, `cancelOrphan`, `cancelIfCancelling` only match the expected status, so a finishing execution is never overwritten). `ExecutionCancellations` is an in-process registry (execution id → `CancelToken`) filled by both engines right after `recorder.start` and BEFORE the WORKFLOW_START event is published, cleared after `recorder.finish`; so cancel only works for executions running in this JVM (a RUNNING record that is not in the registry is an orphan from a restart and is cancelled directly). `CancelToken` interrupts exactly the threads currently inside a node (`enter()`/`exit()` under one lock: no interrupt can reach a pool thread after the node finished, and `exit()` clears a leftover flag). `NodeRunner` is the single place that refuses to start a node once cancelled and that decides CANCELLED vs FAILED: only a failure that was really caused by the interrupt (flag still set, or an interrupt exception in the cause chain) counts as cancelled — a node that swallows the interrupt and fails for its own reason stays FAILED. A real failure beats a cancellation when both happen in one level. Cancelled nodes get snapshot status CANCELLED and are re-run by `resume` like failed ones; a node that finishes normally after the cancel keeps its SUCCESS result, and if nothing is left to skip the execution still ends SUCCESS. Measured on the real backend with a 60 s LLM call: CANCELLED about 135 ms after the request on both engines (the RestClient read is interruptible); against a real DeepSeek long generation (cancel sent at 5 s) CANCELLED about 0.5 s after the request on both engines (status polled every 0.5 s, one run each; whether the provider keeps generating after the disconnect is not measured); a node blocked in a non-interruptible call keeps the status at CANCELLING until it returns. Already-submitted provider tasks (video etc.) are not revoked
- Health check: Spring Boot Actuator exposes only `/actuator/health` (+ `/liveness`, `/readiness`), no login needed, no details for anonymous callers (`management.*` in `application.yml`). `ExecutionPoolHealthIndicator` reports `OUT_OF_SERVICE` when the execution queue is full (the readiness group = `readinessState,db,executionPool`); Redis only shows in the overall status. `backend/Dockerfile` HEALTHCHECK uses the readiness probe
- Docker: `docker-compose.yml` (MySQL + Redis + MinIO + backend + frontend/nginx), `.env.docker.example` → `.env` (JWT_SECRET, PAIAGENT_ENCRYPTION_KEY, MYSQL_PASSWORD, APP_AUTH_DEFAULT_PASSWORD have no default and compose refuses to start without them). nginx turns off buffering for the SSE endpoint. Only frontend / backend / MinIO ports are published; MySQL and Redis stay inside the compose network
- `service/mcp/`: `McpSessionPool` keeps one long-lived stdio MCP process per (command, args, env digest) instead of spawning one per call. A reader thread routes responses by JSON-RPC id (concurrent calls share a process), `tools/list` is cached and checked before a call, timed-out sessions are closed, a request is retried on a fresh session only when it provably was not sent (never after it was written), idle sessions are reclaimed (`paiagent.mcp.idle-minutes`, `max-sessions`). `SearchInfinityMcpClient` is the only user; `GET /api/mcp-tools/{id}/tools` lists a server's tools. Tests spawn a real child JVM (`FakeMcpServer`) and compare pids
- `executor/NodeExecutor.java`: Interface for all node executors
- `executor/NodeExecutorFactory.java`: Factory pattern to get executors by node type
- `executor/impl/`: Concrete implementations (InputNodeExecutor, OutputNodeExecutor, OpenAINodeExecutor, TTSNodeExecutor, etc.)
- `llm/`: Spring AI integration with ChatClientFactory for dynamic client creation; `LlmCallPolicy` (connect/read timeouts, capped retry of transient errors only) and `GuardedChatModel` (per-provider concurrency limit)
- `model/`: Data models (WorkflowConfig, WorkflowNode, WorkflowEdge)

**Application Layers:**
- `controller/`: REST API endpoints
- `service/`: Business logic layer
- `mapper/`: MyBatis-Plus data access layer
- `entity/`: Database entities (Workflow, ExecutionRecord, NodeDefinition, User)
- `dto/`: Data transfer objects
- `config/`: Configuration classes (WebMvcConfig, MyBatisConfig, ExecutorConfig, SchemaMigrationRunner for idempotent startup migrations)
- `security/`: `SecretCodec` (AES-256-GCM + masking) and MyBatis TypeHandlers that encrypt API keys in `workflow.flow_data`, `llm_global_config.api_key` and `mcp_tool_config.env` on write and decrypt on read. Services/executors always see plaintext; API responses only contain masked values (`sk-********1234`) and a masked value sent back on save is restored to the stored secret
- `interceptor/`: AuthInterceptor for JWT token-based authentication
- `common/`: Common utilities and result wrappers

**Resources:**
- `resources/skills/`: Skill definitions (SKILL.md files with YAML Frontmatter)
- `resources/schema.sql`: Database initialization script

### MCP Server (`mcp-server/`)
Standalone stdio MCP server that exposes PaiAgent workflows to agents (Claude Code etc.) through the existing REST API; the backend is untouched. Official SDK `@modelcontextprotocol/sdk`, zod schemas. Tools: `list_workflows`, `start_workflow` (async, returns `execution_id` at once), `get_execution` (summary by default), `resume_execution`, `cancel_execution` (cooperative: returns CANCELLING until the nodes have really exited; idempotent). Key decisions (details in `mcp-server/README.md`): every failure is `{ok:false, code, message, hint, retryable}`; a submit that times out is `UNKNOWN_OUTCOME` (never auto-resent; its `request_id` is locked) while a refused connection is retryable `UPSTREAM_UNAVAILABLE`; a 401 triggers one re-login + resend, which is safe because the backend's auth interceptor rejects before any business code runs; `request_id` de-duplication lives in process memory only; `PAIAGENT_ALLOWED_WORKFLOWS` limits what the agent can see/run; logs go to stderr and never contain input text, passwords or tokens. The backend answers "not found" with business code 500, so `NOT_FOUND` is detected from the message text. Sources must stay erasable TypeScript (no enums / parameter properties) so `node` can run `src/*.ts` directly in tests. Eval (`scripts/eval.ts`, 13 tasks, haiku, fake backend) ran twice: 11/13 then 12/13 tasks passed every check. It is a small sample, not a statistic. The `eval.ts` checks must look at all assistant text of a run, not only the last message (the id is usually announced before the `get_execution` call). `start_workflow`'s description tells the model to skip `list_workflows` when the user gives an id: the extra call happened in 3 of 7 runs before and 1 of 8 after

### Frontend Structure (`frontend/src/`)

**Core Components:**
- `components/FlowCanvas.tsx`: ReactFlow-based visual workflow editor
- `components/NodePanel.tsx`: Draggable node palette (LLM/Tool categories)
- `components/DebugDrawer.tsx`: Execution debugging panel with real-time logs and results
- `components/AudioPlayer.tsx`: Audio playback component for TTS output
- `components/SkillSelector.tsx`: Skill selection component for applying skills in workflows
- `components/ExecutionTimeline.tsx`: Gantt chart of an execution built from snapshots (wall time vs sum of node times, peak concurrency)
- `components/ResumePanel.tsx`: resume a failed execution from the failed/selected node, optionally rewriting variables
- `components/EngineCompare.tsx`: runs the workflow on both engines and diffs node status/outputs
- Pure, unit-tested logic lives in `src/utils` (`sseParser`, `executionRecovery`, `executionTimeline`, `executionDiff`, `resumeRequest`); tests are in `frontend/tests`

**Pages:**
- `pages/LoginPage.tsx`: Authentication page
- `pages/MainPage.tsx`: Workflow list management
- `pages/EditorPage.tsx`: Main workflow editor with canvas, node panel, and debug drawer

**State Management (Zustand):**
- `store/authStore.ts`: User authentication state (token, user info)
- `store/workflowStore.ts`: Single source of truth for the canvas (nodes, edges, selected node id, per-node run status). ReactFlow runs in controlled mode and calls the store's `onNodesChange/onEdgesChange/onConnect`; the selected node is derived via `useSelectedNode()`, never copied

**API Layer:**
- `api/`: Axios-based API client for backend communication
- `utils/request.ts`: Axios instance with auth interceptors

### Database Schema

Tables: `workflow`, `node_definition`, `execution_record`, `user`

Key features:
- JSON columns for workflow config (`flow_data`), execution results (`node_results`)
- Logical deletion using `deleted` field (MyBatis-Plus config)
- Pre-seeded node definitions for OpenAI, DeepSeek, Qwen, ZhiPu, AIPing, and TTS

## Workflow Execution Flow

1. User designs workflow in ReactFlow canvas (frontend)
2. Frontend serializes nodes/edges to JSON and saves via API
3. Backend stores workflow config in `workflow.flow_data`
4. On execution:
   - `EngineSelector` routes to appropriate engine (DAG or LangGraph)
   - For DAG: `DAGParser` validates (cycle detection) and sorts nodes topologically
   - For LangGraph: `GraphBuilder` constructs StateGraph with conditional routing
   - DAG engine groups nodes into dependency layers and runs the independent nodes of a layer concurrently on a bounded pool (`paiagent.engine.parallel-enabled`); each node receives the outputs of its upstream nodes. The LangGraph engine does the same through native parallel branches per layer
   - On failure the remaining layers are not started; `POST /executions/{id}/resume` re-runs only the failed node(s) and reuses successful snapshots. Resume is guarded by a status CAS (a concurrent resume gets `Result.code = 409`)
   - Each node result is recorded in `ExecutionRecord.node_results`
5. Frontend displays execution results in `DebugDrawer` with logs and output data

## Key Technologies

- **Backend**: Spring Boot 3.4.1, Java 21, MyBatis-Plus 3.5.5, MySQL 8.0+, FastJSON2, Spring AI 1.0.0-M5, LangGraph4j 1.8.0-beta3, JJWT 0.12.7
- **Frontend**: React 18, TypeScript 5.6, Vite 6, ReactFlow (@xyflow/react), Ant Design 6, Tailwind CSS 4, Zustand 5
- **Authentication**: JWT-based auth (default: admin/admin123, configurable via env)
- **API Docs**: Swagger UI at http://localhost:8084/swagger-ui.html

## Development Notes

- Image / video providers: image nodes support `volcengine_agent_plan`, `step` and `qwen`; video nodes support `volcengine_agent_plan` and `qwen`. `qwen` means Tongyi Wanxiang through Alibaba Bailian (DashScope): `DashScopeMediaClient` submits an async task (`X-DashScope-Async: enable`), polls `GET /tasks/{id}` and downloads the result; cancel only works for PENDING tasks (provider rule). It uses the same Bailian API key and the same address (`https://dashscope.aliyuncs.com/api/v1`) as TTS, so one `qwen` global config with `ttsModel` / `imageModel` / `videoModel` covers all three. Video clients implement `VideoTaskClient`, so the task-level recovery (taskId checkpoint, continue/regenerate) works for every provider. DeepSeek has no image/video generation API; Zhipu (CogView / CogVideoX) is not implemented yet.
- `prompt_extend` is always sent as `false` for Wanxiang image and video requests. The provider rewrites prompts by default and, measured on this project, invents text that was not asked for (a made-up `ConcurrentModificationException` drawn into the picture) and inaccurate technical details (JDK7 segment locks for a JDK8 `ConcurrentHashMap`). Polish prompts in a visible LLM node instead. `DashScopeMediaClientTest` pins this.
- Text inside generated images is unreliable on every model tried (wan2.2 / 2.5 / 2.6, qwen-image-2.0): short Chinese titles are right only some of the time, and long identifiers such as `ConcurrentHashMap` were misspelled by all of them. So the image node can draw the text itself: an input param named `overlayText` (static text or a reference; first line = title, further lines = subtitle) or the "图上文字" field, plus `overlayPosition` (`top` / `center` / `bottom`). `ImageTextOverlay` renders it with a real font over a soft dark gradient (the model does not reliably leave the area empty), and `assertCanDraw` runs **before** the paid call so a machine without a CJK font fails without spending money (set `PAIAGENT_OVERLAY_FONT` to a font family name if the default candidates are missing). Prompts for such an image should say "no text in the picture".
- References in media nodes resolve by `nodeId.key` through `__nodeOutputs__` first and only then by the bare key; the merged upstream input is a `putAll` of every incoming edge, so two upstream LLM nodes that both expose `output` would otherwise overwrite each other. (LLM prompt templates still resolve by bare key.)
- On this account `qwen-image` / `qwen-image-plus` return "Model not exist"; `qwen-image-2.0`, `qwen-image-2.0-pro` and `wan2.6-t2i` exist but use the synchronous `multimodal-generation` endpoint, which `DashScopeMediaClient` does not call yet (and `wan2.6` returns 4 images unless `n` is set).
- The Bailian docs mention workspace-specific hosts (`https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com`); in practice the classic `dashscope.aliyuncs.com` addresses work with a normal key and the workspace host returned `Workspace endpoint is invalid` for the id we tried, so leave the address at the classic one unless you have a verified workspace host.
- Node look: the node library and the canvas share `frontend/src/utils/nodeVisuals.ts` (badge text + colour). Add new node types there, otherwise they fall back to a grey "FN".
- Output node templates: a reference to a node that produced no output (e.g. skipped by a condition) renders empty; it must not fall back to another node's `output`.
- Artifacts: TTS audio and generated images/videos are uploaded to MinIO (bucket `paiagent`; MinIO stores objects in its own internal format, so they cannot be browsed as plain files). `MinioService` therefore also saves every artifact into a visible folder — `paiagent.output.dir` / `PAIAGENT_OUTPUT_DIR`, default `<repo>/outputs/` (git-ignored; blank disables) — mirroring the object name (`audio/…`, `images/…`, `videos/…`), and `ExecutionResultExporter` writes `outputs/executions/exec-<id>/result.json` (final output, per-node status/output, and url → local file mapping, never API keys). The editor's "导出图片" button renders the canvas to a 2× PNG in the browser and `POST /api/workflows/{id}/export-image` stores it with the (masked) flow JSON in `outputs/workflows/`. Export refuses to run while ReactFlow has not measured the nodes (a background browser tab never does) instead of saving a blank PNG.
- TTS: `qwen3-tts-flash` uses the HTTP `MultiModalConversation` API with enum voices (Cherry…); the `qwen-audio-3.x-tts-flash` family uses a different API — WebSocket `ttsv2.SpeechSynthesizer` with string voice ids (`longanhuan_v3.1`, `Emily_v3.1`…, case-sensitive). The node picks the path by model name (`QwenAudioTtsSupport`). Beijing-region address per the Bailian docs: `wss://<WorkspaceId>.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference`. Verified live with `qwen-audio-3.1-tts-flash` on the classic host `wss://dashscope.aliyuncs.com/api-ws/v1/inference` (the workspace host was rejected for the id we tried).

- Backend API requires JWT token in `Authorization` header (Bearer scheme)
- Frontend stores token in Zustand store and localStorage
- ReactFlow node types must match backend `NodeExecutor` implementations
- Node executors follow a common interface: `execute(WorkflowNode node, Map<String, Object> input) -> Map<String, Object>`
- DAG engine uses Kahn's algorithm for topological sort and DFS for cycle detection
- LangGraph engine uses StateGraph with AsyncNodeAction for async execution
- Skills are loaded from `resources/skills/` with three-level progressive loading (summary -> detail -> reference)
- LLM nodes use Spring AI ChatClient created dynamically via ChatClientFactory
- SSE (Server-Sent Events) used for real-time execution progress streaming. The frontend reads it with `fetch` (Authorization header, no auto-reconnect) and, if the stream drops after `WORKFLOW_START`, recovers by polling `GET /executions/{id}`; the server keeps executing after a client disconnects
- API keys are never returned in clear text and are encrypted at rest (see `security/`); do not log or persist them elsewhere
