/** 断点续跑面板用到的纯函数：校验用户填写的变量、把后端返回码翻成人话。 */
export type ParsedVariables =
  | { ok: true; value: Record<string, unknown> | undefined }
  | { ok: false; error: string };

export const parseModifiedVariables = (text: string): ParsedVariables => {
  const trimmed = text.trim();
  if (!trimmed) {
    return { ok: true, value: undefined };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch (e) {
    return { ok: false, error: `不是合法的 JSON：${e instanceof Error ? e.message : '解析失败'}` };
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { ok: false, error: '需要是 JSON 对象，例如 {"input": "新的输入"}' };
  }
  return { ok: true, value: parsed as Record<string, unknown> };
};

export const describeResumeFailure = (code: number | undefined, message: string | undefined): string => {
  if (code === 409) {
    return '这次执行仍在运行（或已被别人续跑），请等它结束后再试，不要重复点击';
  }
  if (code === 429) {
    return '服务当前繁忙，请稍后重试';
  }
  return message || '断点续执行失败';
};
