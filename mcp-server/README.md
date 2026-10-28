# PaiAgent MCP Server

把 PaiAgent 里已有的工作流，封装成 AI Agent（Claude Code、Claude Desktop 等任何 MCP 客户端）可以调用的**工具**。
它是一个独立的小服务：通过 stdio 与 MCP 客户端通信，通过 PaiAgent 现有的 REST 接口干活，不改动 PaiAgent 后端。

```
MCP 客户端（Agent） ──stdio──▶ paiagent-mcp（本服务） ──HTTP──▶ PaiAgent 后端 ──▶ 大模型 / 生图 / 生视频
   决定调用哪个工具          工具描述、参数校验、错误翻译、           工作流引擎
                              request_id 去重、白名单
```

## 工具

按**任务**拆分，而不是把后端接口一一照搬：

| 工具 | 作用 | 对应的 PaiAgent 接口 |
|---|---|---|
| `list_workflows` | 列出可运行的工作流（编号、名称、说明、输入框标签）。不返回流程数据 | `GET /api/workflows` |
| `start_workflow` | 提交一次执行，**立即返回** `execution_id`，不等结果。可带 `request_id` 防止重试造成重复执行 | `POST /api/workflows/{id}/execute/async` |
| `get_execution` | 查询状态与结果。默认只给摘要（状态、进度、失败节点、最终输出、产物链接），`detail="nodes"` 才返回各节点 | `GET /api/workflows/{id}/executions/{eid}` |
| `resume_execution` | 失败（或被取消）后续跑：只重跑没完成的节点，已成功的节点复用结果。视频任务待决策时带上用户的选择 | `POST /api/workflows/{id}/executions/{eid}/resume` |
| `cancel_execution` | 取消正在运行的执行：不再启动后面的节点，并中断正在运行的节点；已完成节点的结果保留，之后可以续跑。重复调用是安全的 | `POST /api/workflows/{id}/executions/{eid}/cancel` |

**取消是协作式的**：返回 `CANCELLING` 表示“已受理、节点正在退出”，不等于已经停了，要用 `get_execution` 轮询到 `CANCELLED`。已经发给大模型 / 生图 / 生视频服务商的请求，对方可能已经开始计费，取消不能撤回。

每个工具的描述里都写明了**什么时候用、什么时候不用、下一步做什么**，返回里带 `next_step`，模型不必猜。

## 错误：模型能据此决定下一步

所有失败都返回 `isError: true`，内容是同一个结构：

```json
{ "ok": false, "code": "BUSY", "message": "服务繁忙，请稍后重试", "hint": "…", "retryable": true, "retry_after_seconds": 10 }
```

| code | 触发条件 | retryable | 模型该怎么做 |
|---|---|---|---|
| `BUSY` | 后端 429（执行队列满） | 是 | 等 `retry_after_seconds` 再试，不要并发提交 |
| `CONFLICT` | 后端 409（续跑时：该执行仍在运行或已被续跑；取消时：该执行已经结束） | 否 | 续跑时改用 `get_execution`；取消时用 `get_execution` 看最终结果 |
| `NOT_FOUND` | 编号不存在 | 否 | 用 `list_workflows` 重新找 |
| `FORBIDDEN` | 不在 `PAIAGENT_ALLOWED_WORKFLOWS` 里 | 否 | 告诉用户 |
| `AUTH_FAILED` | 登录失败或令牌无效 | 否 | 告诉用户，重试没用 |
| `INVALID_INPUT` | 输入为空白等 | 否 | 向用户要有效输入 |
| `REQUEST_ID_REUSED` | 同一个 `request_id` 对应了不同输入 | 否 | 这是新任务，换新的 `request_id` |
| `UPSTREAM_UNAVAILABLE` | 连不上/超时/网关错误，**请求肯定没被处理** | 是 | 稍后重试 |
| `UNKNOWN_OUTCOME` | **提交类**请求超时，**可能已经开始执行** | 否 | 不要直接重发；先 `get_execution` 确认 |
| `UPSTREAM_ERROR` | 后端返回了其他业务错误 | 否 | 把 `message` 告诉用户 |

参数校验（类型、必填、长度）由 MCP SDK 在协议层完成，不合格的调用不会到达后端。

## 快速开始

需要 Node.js ≥ 22.18 和一个正在运行的 PaiAgent 后端（默认 `http://localhost:8084`）。

```bash
cd mcp-server
npm install
npm run build
```

配置全部来自环境变量：

| 变量 | 默认 | 说明 |
|---|---|---|
| `PAIAGENT_BASE_URL` | `http://localhost:8084` | 后端地址 |
| `PAIAGENT_USERNAME` / `PAIAGENT_PASSWORD` | — | 登录账号。令牌过期（401）后会自动重新登录。**推荐** |
| `PAIAGENT_TOKEN` | — | 也可以直接给令牌，过期后无法续期 |
| `PAIAGENT_ALLOWED_WORKFLOWS` | 不限制 | 逗号分隔的工作流编号。设置后只能看到、运行这些工作流（最小权限） |
| `PAIAGENT_MAX_INPUT_CHARS` | 2000 | `input_text` 上限 |
| `PAIAGENT_MAX_OUTPUT_CHARS` | 4000 | 返回给模型的最终输出上限，超出会截断并标注 |
| `PAIAGENT_REQUEST_TIMEOUT_MS` / `PAIAGENT_SUBMIT_TIMEOUT_MS` / `PAIAGENT_RESUME_WAIT_MS` | 15000 / 20000 / 8000 | 普通请求 / 提交请求 / 续跑最多等待的时间 |
| `PAIAGENT_IDEMPOTENCY_TTL_SECONDS` | 3600 | `request_id` 去重记录保留时间 |

### 接入 Claude Code

先确认 `npm run build` 过，再**在你要使用 Claude Code 的项目目录里**执行（默认的 local 范围只对"执行命令时所在的项目"生效；想让所有项目都能用，加 `-s user`）：

```bash
claude mcp add paiagent -e PAIAGENT_USERNAME=admin -e PAIAGENT_PASSWORD=你的密码 -e PAIAGENT_ALLOWED_WORKFLOWS=1,2 -- node /绝对路径/PaiAgent-one/mcp-server/dist/index.js
claude mcp list        # 会真的把服务拉起来握手；看到 ✓ Connected 才算接入成功
```

然后启动 `claude`，直接用自然语言提需求，例如：“列出 PaiAgent 里的工作流”“运行工作流 1，输入……，跑完告诉我结果”“把刚才那次取消掉”。在会话里用 `/mcp` 可以看到这个服务及它的 5 个工具；每次工具调用 Claude Code 都会显示工具名、参数和结果（这就是调用记录）。移除：`claude mcp remove paiagent`。

其他客户端的 JSON 配置同理：

```json
{
  "mcpServers": {
    "paiagent": {
      "command": "node",
      "args": ["/绝对路径/PaiAgent-one/mcp-server/dist/index.js"],
      "env": { "PAIAGENT_USERNAME": "admin", "PAIAGENT_PASSWORD": "…" }
    }
  }
}
```

### 同一份工具定义，输出成 OpenAI / Anthropic 的函数格式

工具只在 `src/server.ts` 里定义一次。MCP 客户端通过 `tools/list` 拿到的是 MCP 格式；不走 MCP、直接用 OpenAI / Anthropic 函数调用接口的程序，可以把同一份定义转成它们的格式（名称、描述、参数 JSON Schema 是同一份，外壳不同）：

```bash
node scripts/export-tool-schemas.ts start_workflow     # 并排打印三种格式
node scripts/export-tool-schemas.ts --all              # 全部工具导出成 OpenAI 的 tools 数组
```

### 不接客户端，直接看一遍调用记录

```bash
PAIAGENT_USERNAME=admin PAIAGENT_PASSWORD=admin123 node scripts/demo-client.ts            # 只列出工作流
PAIAGENT_USERNAME=admin PAIAGENT_PASSWORD=admin123 node scripts/demo-client.ts 1 "讲讲 HashMap 的扩容"
PAIAGENT_USERNAME=admin PAIAGENT_PASSWORD=admin123 node scripts/demo-client.ts 1 "……" --cancel   # 提交后 2 秒取消，再轮询到 CANCELLED
```

脚本把服务当子进程拉起来，按列出 → 提交 → 轮询走一遍，打印每次工具调用的名称、参数和结果；失败时再演示 `resume_execution`。

## 测试

```bash
npm test          # HTTP 客户端、request_id 去重、通过真实 MCP 协议调用每个工具、stdio 端到端、工具定义导出
npm run typecheck
```

测试用的 `tests/fake-paiagent.ts` 是一个行为可控的假后端（可以让它返回 429/409、令牌过期、超时、视频任务待决策等）。
做过变异检查：故意去掉“401 后重新登录”“结果未知时不重发”“不记录输入文本”“不返回 flowData”，对应的测试都会失败。

### 用真实 Agent 评测工具描述（需要先登录命令行里的 claude）

```bash
node scripts/eval.ts            # 13 个自然语言任务，默认用 haiku；--only T05,T06 只跑部分
```

它让 Claude Code 在无头模式下只凭工具描述完成任务，检查：选对工具、参数正确、失败时是否按 `hint` 行事（例如密钥无效时不盲目重试、视频待决策时不替用户选择）。
结果写入 `eval/results.json`。**注意**：无头模式需要命令行的 `claude` 已登录（桌面版的登录状态不共享）。登录：

```bash
claude auth login     # 会打开浏览器让你授权；也可以先运行 claude，再在里面输入 /login
claude auth status    # 看到 "loggedIn": true 就可以了
node scripts/eval.ts
```

登录报 `Login failed: read ECONNRESET`（直连被重置）或 `connect ECONNREFUSED 127.0.0.1:7890`（代理端口写错了）时，说明命令行程序没走代理或走错了端口：它不读 macOS 的系统代理，只认环境变量。在同一个终端先执行下面这一行（它读取当前系统代理的端口，代理软件换了端口也不用改），再登录和评测。命令请直接输入，不要连同文档里的反引号一起复制：反引号在 zsh 里是“先运行里面的命令，再把输出当命令执行”。

```bash
P=$(scutil --proxy | awk '/HTTPSPort/{print $3}'); export HTTPS_PROXY=http://127.0.0.1:$P HTTP_PROXY=http://127.0.0.1:$P
```

未登录时脚本会直接说明原因并退出（不会产生假的结果）。评测用 haiku，13 个任务的 token 开销很小，但仍会消耗你账号的额度。

**实测结果**（2026-10-28，haiku，对端是假后端，每次每个任务只跑一次）：

| 运行 | 全部检查通过的任务 | 检查项 | 工具调用次数 |
|---|---|---|---|
| 第 1 次（`eval/results-run1-before-checker-fix.json`） | 11 / 13 | 42 / 44 | 28 |
| 第 2 次（修了 T12 的检查之后，`eval/results.json`） | 12 / 13 | 43 / 44 | 29 |

发现和处理：

- **T12**（“提交后先告诉我 execution_id，再查状态”）第 1 次失败是**检查写错了**：模型在调用 `get_execution` 之前的那条消息里就说了 id，我的检查只看最后一条回答。现在改成在整段对话文字里找后端真实返回的那个 id。T12 另有 1 / 4 次重跑，模型只提交就结束、没去查状态，这个没改。
- **T03**（用户已经给了工作流编号）模型有时仍先调一次 `list_workflows`（只读，无副作用）：改之前 7 次里有 3 次。我在 `start_workflow` 的描述里补了一句“用户已经给出编号就直接调用，不需要先 list_workflows”，改之后重跑 8 次里有 1 次。样本很小，只能说明方向对，不能说已经消除。
- 同一个任务两次运行的工具调用并不完全一样（例如 T04、T11），说明单次结果会有出入。

这个评测测的是**工具层设计**（描述、参数、错误信息能否被模型用对），对端是假后端；只用了 haiku，没有换别的模型，也没有统计显著性。

## 设计取舍与已知限制

- **`request_id` 去重只在本进程内存里**：进程重启后失效，多个服务实例之间不共享。要做到真正的“恰好一次”，应当由 PaiAgent 后端按 `request_id` 做唯一键（目前没有）。
- **提交超时不会自动重发**：超时意味着“不知道有没有开始执行”，直接重发可能重复计费，所以返回 `UNKNOWN_OUTCOME`，并且同一个 `request_id` 在结果未知时不允许再次提交。
- **只靠文案识别“不存在”**：后端对“工作流/执行不存在”返回的业务码是 500，工具层只能按文案里的“不存在”归类为 `NOT_FOUND`。后端若改为返回 404 就不需要这个判断。
- **凭据是共享的一个账号**：PaiAgent 目前只有一个管理员账号，所以服务能做的事等于这个账号能做的事；`PAIAGENT_ALLOWED_WORKFLOWS` 只能限制到工作流粒度。
- **列表没有服务端分页**：`list_workflows` 在服务端过滤、截断（默认 20 条），工作流很多时每次仍会向后端取全量。
- **取消只对运行在 PaiAgent 后端本进程里的执行有效**（后端的取消登记表在进程内存里）；后端多实例部署时需要把取消请求路由到持有该执行的实例。
- 日志写 stderr（stdout 是协议通道），只记录工具名、编号、耗时和结果，不记录输入文本、令牌和密码。
