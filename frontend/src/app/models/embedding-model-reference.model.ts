export interface EmbeddingModelReference {
  serverType?: string;       // 'ollama' | 'openai' | 'lm-studio' | 'gemini' | 'custom'
  providerUrl?: string;      // e.g. http://192.168.188.92:11434
  modelName?: string;        // e.g. mxbai-embed-large
  apiKey?: string | null;   // optional, for openai/gemini
  timeoutSecs?: number;     // default: 60
}
