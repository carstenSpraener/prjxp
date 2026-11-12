export type SetupStep = 'embedding' | 'github' | 'chat' | 'optimization';
export interface StepStatus {
  configured: boolean; tested: boolean; required: boolean; skipped: boolean;
  problemCode: string | null; message: string | null; testedAt: string | null;
}
export interface SetupStatus {
  configVersion: string; completed: boolean; completionMode: 'NONE' | 'VERIFIED' | 'DEFERRED';
  restartRequired: boolean; resumeStep: SetupStep;
  embedding: StepStatus; github: StepStatus; chat: StepStatus; optimization: StepStatus;
}
export interface EmbeddingConfigRequest {
  type?: string | null; apiBaseURL?: string | null; apiKey?: string | null;
  modelName?: string | null; dimensions?: number | null; timeout?: number | null;
  maxSegmentsPerBatch?: number | null; resetDimensions?: boolean | null;
}
export interface ChatModelRequest {
  stereoType?: string | null; serverType?: string | null; modelName?: string | null;
  apiKey?: string | null; providerUrl?: string | null; timeoutSecs?: number | null;
  temperature?: number | null;
}
export interface GithubConfigRequest { tokenKeyEnv?: string | null; defaultPat?: string | null; }
export interface OptimizationRequest {
  vectorDimension?: number | null; maxSegmentsPerBatch?: number | null;
  docpipeMaxThreads?: number | null; readerMaxOutputChars?: number | null;
  graphRerankFloor?: number | null; graphRerankBGraph?: number | null;
  graphRerankCap?: number | null; graphRerankWBase?: number | null; embeddingTimeout?: number | null;
}
export interface SetupConfiguration {
  status: SetupStatus; embedding: EmbeddingConfigRequest | null; chat: ChatModelRequest | null;
  github: GithubConfigRequest | null; optimization: OptimizationRequest | null;
  githubEnabled: boolean; githubKeyAvailable: boolean;
}
export interface SetupTestRequest<T> { configVersion: string; configuration: T | null; }
export interface SetupTestResult {
  success: boolean; message: string | null; latencyMs: number; response: string | null;
  dimensions: number | null; testId: string | null; configVersion: string;
}
type SaveBase = { configVersion: string; testId?: string | null };
export type SetupSaveRequest = SaveBase & (
  { step: 'embedding'; skip: false; embedding: EmbeddingConfigRequest } |
  { step: 'chat'; skip: false; chat: ChatModelRequest } |
  { step: 'github'; skip: false; github: GithubConfigRequest } |
  { step: 'optimization'; skip: false; optimization: OptimizationRequest } |
  { step: 'github' | 'optimization'; skip: true }
);
export interface SetupCompleteRequest { configVersion: string; defer: boolean; }
export interface SetupError { code: string; message: string; step: SetupStep | null; }
