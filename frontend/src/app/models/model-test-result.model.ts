export interface ModelTestResult {
  success: boolean;
  message: string;
  latencyMs: number;
  response: string | null;      // nur Chat-Test
  dimensions: number | null;    // nur Embedding-Test
}
