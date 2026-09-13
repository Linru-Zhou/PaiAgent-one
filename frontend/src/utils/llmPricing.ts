/**
 * 大模型参考计价表（非实时价格，仅用于让用户对每次调用的花费有个数量级概念）。
 * 单位统一按每 1000 token 计，货币视厂商而定；具体以官方最新定价为准。
 */
interface PricingRule {
  match: RegExp;
  label: string;
  currency: 'CNY' | 'USD';
  inputPer1k: number;
  outputPer1k: number;
}

const PRICING_TABLE: PricingRule[] = [
  { match: /deepseek-reasoner|deepseek-r1/i, label: 'DeepSeek Reasoner', currency: 'CNY', inputPer1k: 0.002, outputPer1k: 0.008 },
  { match: /deepseek/i, label: 'DeepSeek Chat', currency: 'CNY', inputPer1k: 0.001, outputPer1k: 0.002 },
  { match: /qwen-max/i, label: 'Qwen Max', currency: 'CNY', inputPer1k: 0.0024, outputPer1k: 0.0096 },
  { match: /qwen-plus/i, label: 'Qwen Plus', currency: 'CNY', inputPer1k: 0.0008, outputPer1k: 0.002 },
  { match: /qwen/i, label: 'Qwen Turbo', currency: 'CNY', inputPer1k: 0.0003, outputPer1k: 0.0006 },
  { match: /glm-4-flash/i, label: 'GLM-4-Flash', currency: 'CNY', inputPer1k: 0, outputPer1k: 0 },
  { match: /glm/i, label: 'GLM-4', currency: 'CNY', inputPer1k: 0.0001, outputPer1k: 0.0001 },
  { match: /step/i, label: 'Step (阶跃星辰)', currency: 'CNY', inputPer1k: 0.005, outputPer1k: 0.02 },
  { match: /gpt-4o-mini/i, label: 'GPT-4o mini', currency: 'USD', inputPer1k: 0.00015, outputPer1k: 0.0006 },
  { match: /gpt-4o/i, label: 'GPT-4o', currency: 'USD', inputPer1k: 0.0025, outputPer1k: 0.01 },
  { match: /gpt-3\.5/i, label: 'GPT-3.5 Turbo', currency: 'USD', inputPer1k: 0.0005, outputPer1k: 0.0015 },
];

export interface CostEstimate {
  label: string;
  currency: 'CNY' | 'USD';
  cost: number;
}

export const estimateLlmCost = (
  modelOrProvider: string | undefined | null,
  inputTokens: number,
  outputTokens: number
): CostEstimate | null => {
  if (!modelOrProvider) {
    return null;
  }
  const rule = PRICING_TABLE.find((r) => r.match.test(modelOrProvider));
  if (!rule) {
    return null;
  }
  const cost = (inputTokens / 1000) * rule.inputPer1k + (outputTokens / 1000) * rule.outputPer1k;
  return { label: rule.label, currency: rule.currency, cost };
};

export const formatCost = (estimate: CostEstimate) => {
  const symbol = estimate.currency === 'CNY' ? '¥' : '$';
  if (estimate.cost === 0) {
    return `${symbol}0（该模型免费/试用额度）`;
  }
  if (estimate.cost < 0.0001) {
    return `< ${symbol}0.0001`;
  }
  return `${symbol}${estimate.cost.toFixed(4)}`;
};
