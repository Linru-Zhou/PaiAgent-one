import type { Config } from '../src/config.ts';
import { credentials } from './fake-paiagent.ts';

export function testConfig(baseUrl: string, overrides: Partial<Config> = {}): Config {
  return {
    baseUrl,
    username: credentials.username,
    password: credentials.password,
    token: undefined,
    requestTimeoutMs: 2_000,
    submitTimeoutMs: 2_000,
    resumeWaitMs: 2_000,
    maxInputChars: 2_000,
    maxOutputChars: 4_000,
    allowedWorkflows: null,
    idempotencyTtlMs: 60_000,
    ...overrides,
  };
}
