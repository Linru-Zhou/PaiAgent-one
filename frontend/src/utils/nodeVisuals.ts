/**
 * 节点的外观（徽标文字、颜色、说明）。节点库和画布共用这一份，
 * 以前两边各写各的：节点库用数据库里的 emoji + 分类颜色，画布用另一张写死的表，
 * 图片 / 视频节点在画布上没有登记（落到灰色的 "FN"），条件节点在库里是紫色、画布上却是橙色，所以拖出来的节点对不上号。
 */
export interface NodeVisual {
  badge: string;
  tone: string;
  caption: string;
}

const VISUALS: Record<string, NodeVisual> = {
  input: { badge: 'IN', tone: 'node-tone-green', caption: 'Start input' },
  output: { badge: 'OUT', tone: 'node-tone-purple', caption: 'Final response' },
  llm: { badge: 'AI', tone: 'node-tone-blue', caption: 'Model call' },
  react_agent: { badge: 'AI', tone: 'node-tone-blue', caption: 'ReAct compatibility' },
  openai: { badge: 'AI', tone: 'node-tone-blue', caption: 'OpenAI' },
  deepseek: { badge: 'DS', tone: 'node-tone-blue', caption: 'DeepSeek' },
  qwen: { badge: 'QW', tone: 'node-tone-blue', caption: 'Qwen' },
  zhipu: { badge: 'ZP', tone: 'node-tone-blue', caption: 'ZhiPu' },
  ai_ping: { badge: 'AP', tone: 'node-tone-blue', caption: 'AIPing' },
  step: { badge: 'ST', tone: 'node-tone-blue', caption: 'StepFun' },
  tts: { badge: 'TTS', tone: 'node-tone-amber', caption: 'Audio tool' },
  image_generate: { badge: 'IMG', tone: 'node-tone-amber', caption: 'Image generation' },
  video_generate: { badge: 'VID', tone: 'node-tone-amber', caption: 'Video generation' },
  condition: { badge: 'IF', tone: 'node-tone-orange', caption: 'Condition branch' },
};

const FALLBACK: NodeVisual = { badge: 'FN', tone: 'node-tone-slate', caption: 'Workflow node' };

export const getNodeVisual = (type?: string): NodeVisual => VISUALS[type || ''] || FALLBACK;
