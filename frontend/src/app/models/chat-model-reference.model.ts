export interface ChatModelReference {
  stereoType?: string;
  serverType?: string;
  modelName?: string;
  apiKey?: string | null;
  providerUrl?: string;
  timeoutSecs?: number;
  temperature?: number;
  args?: Record<string, string>;
  prompt?: string | null;   // optional standing instructions, supports ${ENV} placeholders
}
