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
- Optional tuning (see `application.yml`): `PAIAGENT_PARALLEL_ENABLED`, `PAIAGENT_EXEC_POOL_SIZE/QUEUE`, `PAIAGENT_NODE_POOL_SIZE/QUEUE`, `PAIAGENT_STALE_AFTER_MINUTES`, `PAIAGENT_LLM_MAX_CONCURRENT`

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

- Artifacts: TTS audio and generated images/videos are uploaded to MinIO (bucket `paiagent`; MinIO stores objects in its own internal format, so they cannot be browsed as plain files). `MinioService` therefore also saves every artifact into a visible folder — `paiagent.output.dir` / `PAIAGENT_OUTPUT_DIR`, default `<repo>/outputs/` (git-ignored; blank disables) — mirroring the object name (`audio/…`, `images/…`, `videos/…`), and `ExecutionResultExporter` writes `outputs/executions/exec-<id>/result.json` (final output, per-node status/output, and url → local file mapping, never API keys). The editor's "导出图片" button renders the canvas to a 2× PNG in the browser and `POST /api/workflows/{id}/export-image` stores it with the (masked) flow JSON in `outputs/workflows/`.
- TTS: `qwen3-tts-flash` uses the HTTP `MultiModalConversation` API with enum voices (Cherry…); the `qwen-audio-3.x-tts-flash` family uses a different API — WebSocket `ttsv2.SpeechSynthesizer` with string voice ids (`longanhuan_v3.1`, `Emily_v3.1`…, case-sensitive). The node picks the path by model name (`QwenAudioTtsSupport`). Beijing-region address per the Bailian docs: `wss://<WorkspaceId>.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference`. The audio path has not been exercised against the real service in this repo (no API key)

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
