import { createServer } from 'node:http';
import type { IncomingMessage, Server, ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';
import type { ExecutionDto } from '../src/client.ts';

/** 一个工作流在假后端里“会怎么跑”：先 RUNNING 若干次轮询，然后给出固定的最终结果。 */
export interface Scenario {
  runningPolls: number;
  final: ExecutionDto;
}

export interface Mode {
  /** 提交时返回业务码 429 */
  busy?: boolean;
  /** 前 n 次提交返回 429，之后恢复正常 */
  busyFirst?: number;
  /** 提交时永远不响应（模拟超时） */
  hangStart?: boolean;
  /** 续跑时返回业务码 409 */
  resumeConflict?: boolean;
  /** 续跑要多久才返回（模拟视频节点的长任务） */
  resumeDelayMs?: number;
  /** 取消时执行已经结束：返回业务码 409 */
  cancelTooLate?: boolean;
  /** 所有接口都返回非 JSON 的 502 */
  gatewayDown?: boolean;
}

const USER = 'admin';
const PASSWORD = 'secret';

export const SUCCESS_EXECUTION: ExecutionDto = {
  executionId: 0,
  status: 'SUCCESS',
  duration: 1234,
  outputData: '答案：ConcurrentHashMap 的 size() 靠 CounterCell 累加。封面 https://files.example.com/images/cover.png 视频 https://files.example.com/videos/v.mp4',
  nodeResults: [
    { nodeId: 'input-1', nodeName: '用户问题', status: 'SUCCESS', duration: 2, output: '{"input":"..."}' },
    { nodeId: 'llm-1', nodeName: 'Java讲解', status: 'SUCCESS', duration: 1200, output: '答案…' },
  ],
};

export const VIDEO_PENDING_EXECUTION: ExecutionDto = {
  executionId: 0,
  status: 'FAILED',
  errorMessage: '节点 video-1 执行失败: 检测到上次提交的视频任务尚未取回结果',
  nodeResults: [
    { nodeId: 'llm-1', nodeName: 'Java讲解', status: 'SUCCESS', duration: 900, output: '答案…' },
    {
      nodeId: 'video-1',
      nodeName: '生成视频',
      status: 'FAILED',
      error: '检测到上次提交的视频任务尚未取回结果（taskId: t-1）',
      errorCode: 'VIDEO_TASK_PENDING',
      errorDetail: { taskId: 't-1', providerStatus: 'RUNNING' },
    },
  ],
};

export const API_KEY_EXECUTION: ExecutionDto = {
  executionId: 0,
  status: 'FAILED',
  errorMessage: '节点 llm-1 执行失败: 401 Unauthorized: Invalid API key',
  nodeResults: [{ nodeId: 'llm-1', nodeName: 'Java讲解', status: 'FAILED', error: '401 Unauthorized: Invalid API key' }],
};

export class FakePaiAgent {
  readonly loginRequests: Array<{ username: string; password: string }> = [];
  readonly startRequests: Array<{ workflowId: number; inputData: string }> = [];
  readonly resumeRequests: Array<{ workflowId: number; executionId: number; body: Record<string, unknown> }> = [];
  readonly cancelRequests: Array<{ workflowId: number; executionId: number }> = [];
  readonly authHeaders: string[] = [];
  mode: Mode = {};
  scenarios = new Map<number, Scenario>([
    [1, { runningPolls: 1, final: SUCCESS_EXECUTION }],
    [2, { runningPolls: 0, final: VIDEO_PENDING_EXECUTION }],
    [3, { runningPolls: 0, final: API_KEY_EXECUTION }],
  ]);

  private readonly server: Server;
  private validTokens = new Set<string>();
  private tokenCounter = 0;
  private executionCounter = 100;
  private readonly executions = new Map<number, { workflowId: number; polls: number; cancel?: 'CANCELLING' | 'CANCELLED' }>();

  constructor() {
    this.server = createServer((req, res) => {
      void this.handle(req, res);
    });
  }

  async start(): Promise<string> {
    await new Promise<void>((resolve) => this.server.listen(0, '127.0.0.1', resolve));
    return this.url();
  }

  url(): string {
    return `http://127.0.0.1:${(this.server.address() as AddressInfo).port}`;
  }

  async close(): Promise<void> {
    this.server.closeAllConnections();
    await new Promise<void>((resolve) => this.server.close(() => resolve()));
  }

  /** 预先放一条执行记录（评测里“查询已有执行”之类的任务用）。 */
  seedExecution(executionId: number, workflowId: number): void {
    this.executions.set(executionId, { workflowId, polls: 0 });
  }

  /** 清掉请求记录和执行记录，恢复默认行为（评测里每个任务之前调用）。 */
  reset(): void {
    this.mode = {};
    this.startRequests.length = 0;
    this.resumeRequests.length = 0;
    this.cancelRequests.length = 0;
    this.executions.clear();
  }

  /** 让所有已发出的令牌失效（模拟 JWT 过期）。 */
  expireTokens(): void {
    this.validTokens = new Set();
  }

  private async handle(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const chunks: Buffer[] = [];
    for await (const chunk of req) {
      chunks.push(chunk as Buffer);
    }
    const raw = Buffer.concat(chunks).toString('utf8');
    const body = raw ? (JSON.parse(raw) as Record<string, unknown>) : {};
    const path = req.url ?? '';

    if (this.mode.gatewayDown) {
      res.writeHead(502, { 'Content-Type': 'text/html' }).end('<html>Bad Gateway</html>');
      return;
    }
    if (req.method === 'POST' && path === '/api/auth/login') {
      this.loginRequests.push({ username: String(body.username), password: String(body.password) });
      if (body.username === USER && body.password === PASSWORD) {
        const token = `tok-${++this.tokenCounter}`;
        this.validTokens.add(token);
        return this.json(res, { code: 200, message: '操作成功', data: { token, refreshToken: 'r', user: { username: USER } } });
      }
      return this.json(res, { code: 500, message: '用户名或密码错误' });
    }

    const auth = req.headers.authorization ?? '';
    this.authHeaders.push(auth);
    if (!this.validTokens.has(auth.replace(/^Bearer /, ''))) {
      res.writeHead(401, { 'Content-Type': 'application/json' }).end('{"code":401,"message":"未认证或认证已过期"}');
      return;
    }

    if (req.method === 'GET' && path === '/api/workflows') {
      return this.json(res, { code: 200, data: this.workflowList() });
    }
    const start = /^\/api\/workflows\/(\d+)\/execute\/async$/.exec(path);
    if (req.method === 'POST' && start) {
      return this.handleStart(res, Number(start[1]), String(body.inputData ?? ''));
    }
    const get = /^\/api\/workflows\/(\d+)\/executions\/(\d+)$/.exec(path);
    if (req.method === 'GET' && get) {
      return this.handleGet(res, Number(get[1]), Number(get[2]));
    }
    const resume = /^\/api\/workflows\/(\d+)\/executions\/(\d+)\/resume$/.exec(path);
    if (req.method === 'POST' && resume) {
      return this.handleResume(res, Number(resume[1]), Number(resume[2]), body);
    }
    const cancel = /^\/api\/workflows\/(\d+)\/executions\/(\d+)\/cancel$/.exec(path);
    if (req.method === 'POST' && cancel) {
      return this.handleCancel(res, Number(cancel[1]), Number(cancel[2]));
    }
    res.writeHead(404).end();
  }

  private handleStart(res: ServerResponse, workflowId: number, inputData: string): void {
    if (this.mode.hangStart) {
      return; // 不响应：连接保持到客户端放弃
    }
    this.startRequests.push({ workflowId, inputData });
    if (this.mode.busy || (this.mode.busyFirst ?? 0) > 0) {
      if (this.mode.busyFirst) {
        this.mode.busyFirst -= 1;
      }
      return this.json(res, { code: 429, message: '服务繁忙，请稍后重试' });
    }
    if (!this.scenarios.has(workflowId)) {
      return this.json(res, { code: 500, message: '工作流不存在' });
    }
    const executionId = ++this.executionCounter;
    this.executions.set(executionId, { workflowId, polls: 0 });
    this.json(res, { code: 200, data: { executionId, status: 'RUNNING' } });
  }

  /** 假的协作式取消：受理后第一次查询是 CANCELLING，之后是 CANCELLED；已经结束的返回 409（和真实后端一致）。 */
  private handleCancel(res: ServerResponse, workflowId: number, executionId: number): void {
    this.cancelRequests.push({ workflowId, executionId });
    const execution = this.executions.get(executionId);
    if (!execution || execution.workflowId !== workflowId) {
      return this.json(res, { code: 500, message: '执行记录不存在' });
    }
    if (this.mode.cancelTooLate) {
      return this.json(res, { code: 409, message: '该执行已结束（状态: SUCCESS），没有可以取消的内容' });
    }
    if (execution.cancel === 'CANCELLED') {
      return this.json(res, { code: 200, data: { executionId, status: 'CANCELLED', message: '这次执行已经是取消状态' } });
    }
    execution.cancel = 'CANCELLING';
    this.json(res, { code: 200, data: { executionId, status: 'CANCELLING', message: '已受理取消' } });
  }

  private handleGet(res: ServerResponse, workflowId: number, executionId: number): void {
    const execution = this.executions.get(executionId);
    if (!execution || execution.workflowId !== workflowId) {
      return this.json(res, { code: 500, message: '执行记录不存在' });
    }
    if (execution.cancel) {
      const status = execution.cancel;
      if (status === 'CANCELLING') {
        execution.cancel = 'CANCELLED'; // 下一次查询就是最终状态
      }
      return this.json(res, {
        code: 200,
        data: {
          executionId,
          status,
          errorMessage: status === 'CANCELLED' ? '执行已取消（已完成 1 个节点，其余未启动或已中断；可以从断点继续执行）' : null,
          nodeResults: [
            { nodeId: 'input-1', nodeName: '用户问题', status: 'SUCCESS' },
            { nodeId: 'llm-1', nodeName: 'Java讲解', status: 'CANCELLED', error: '执行已取消' },
          ],
        },
      });
    }
    const scenario = this.scenarios.get(workflowId)!;
    execution.polls += 1;
    if (execution.polls <= scenario.runningPolls) {
      return this.json(res, {
        code: 200,
        data: { executionId, status: 'RUNNING', nodeResults: [{ nodeId: 'input-1', nodeName: '用户问题', status: 'SUCCESS' }] },
      });
    }
    this.json(res, { code: 200, data: { ...scenario.final, executionId } });
  }

  private handleResume(res: ServerResponse, workflowId: number, executionId: number, body: Record<string, unknown>): void {
    this.resumeRequests.push({ workflowId, executionId, body });
    if (this.mode.resumeConflict) {
      return this.json(res, { code: 409, message: '该执行正在进行中，请勿重复提交' });
    }
    const respond = () => this.json(res, { code: 200, data: { ...SUCCESS_EXECUTION, executionId } });
    if (this.mode.resumeDelayMs) {
      setTimeout(respond, this.mode.resumeDelayMs);
    } else {
      respond();
    }
  }

  private workflowList(): unknown[] {
    const flow = (label: string, nodes: number) =>
      JSON.stringify({
        nodes: [
          { id: 'input-1', type: 'input', data: { label } },
          ...Array.from({ length: nodes - 1 }, (_, i) => ({ id: `n-${i}`, type: 'deepseek', data: { apiKey: 'sk-****1234' } })),
        ],
        edges: [],
      });
    return [
      { id: 1, name: 'Java面试题讲解', description: '输入一个 Java 知识点，生成讲解', flowData: flow('用户问题', 3), engineType: 'dag' },
      { id: 2, name: 'Java面试短视频', description: '讲解 + 配音 + 封面 + 视频', flowData: flow('知识点', 15), engineType: 'langgraph' },
      { id: 3, name: '客服问答', description: null, flowData: 'not json', engineType: 'dag' },
    ];
  }

  private json(res: ServerResponse, payload: unknown): void {
    res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(payload));
  }
}

export const credentials = { username: USER, password: PASSWORD };
