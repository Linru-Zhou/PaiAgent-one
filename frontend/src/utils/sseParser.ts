/**
 * 增量 SSE 解析器。
 *
 * 为什么不用浏览器自带的 EventSource：它发不了 Authorization 头（只能把 token 拼进 URL），
 * 出错时拿不到 HTTP 状态码（401 和 429 和断网看起来一样），还会自动重连——对"每次连接都会触发一次工作流执行"
 * 的接口，自动重连等于重复执行。这里改用 fetch 读流，自己按 SSE 规范解析。
 *
 * 网络分块的边界是任意的：一个事件可能被切成几段，也可能一段里有好几个事件，
 * 行结束符还可能是 \n、\r\n 或 \r（\r 与后面的 \n 被分在两个块里也要处理对）。
 */
export interface SseMessage {
  event: string;
  data: string;
}

export class SseParser {
  private buffer = '';
  private eventName = '';
  private dataLines: string[] = [];

  /** 喂入一段新收到的文本，返回其中已经完整的事件。 */
  push(chunk: string): SseMessage[] {
    this.buffer += chunk;
    const messages: SseMessage[] = [];

    for (;;) {
      // 结尾孤立的 \r 可能是 \r\n 的前半截，等下一块再决定
      const match = /\r\n|\n|\r(?!$)/.exec(this.buffer);
      if (!match) {
        break;
      }
      const line = this.buffer.slice(0, match.index);
      this.buffer = this.buffer.slice(match.index + match[0].length);
      const message = this.consumeLine(line);
      if (message) {
        messages.push(message);
      }
    }
    return messages;
  }

  private consumeLine(line: string): SseMessage | null {
    if (line === '') {
      if (this.dataLines.length === 0 && !this.eventName) {
        return null;
      }
      const message = { event: this.eventName || 'message', data: this.dataLines.join('\n') };
      this.eventName = '';
      this.dataLines = [];
      return message;
    }
    if (line.startsWith(':')) {
      return null; // 注释行（常用作心跳）
    }
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) {
      value = value.slice(1);
    }
    if (field === 'event') {
      this.eventName = value;
    } else if (field === 'data') {
      this.dataLines.push(value);
    }
    return null; // id / retry 等字段这里用不到
  }
}
