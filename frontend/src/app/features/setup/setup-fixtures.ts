import { SetupConfiguration, StepStatus } from '../../models/setup.model';
export function setupConfiguration(): SetupConfiguration {
  const required: StepStatus = { configured: true, tested: true, required: true, skipped: false,
    problemCode: null, message: null, testedAt: '2026-11-12T12:00:00Z' };
  const optional = {...required, required: false};
  return {
    status: { configVersion: 'v1', completed: false, completionMode: 'NONE', restartRequired: false,
      resumeStep: 'embedding', embedding: {...required}, chat: {...required},
      github: {...optional}, optimization: {...optional} },
    embedding: { type: 'OLLAMA', apiBaseURL: 'http://localhost:11434', apiKey: null, modelName: 'embed',
      dimensions: 3, timeout: 20, maxSegmentsPerBatch: 32, resetDimensions: false },
    chat: { stereoType: 'default', serverType: 'ollama', providerUrl: 'http://localhost:11434',
      apiKey: null, modelName: 'chat', timeoutSecs: 60, temperature: 0.7 },
    github: { tokenKeyEnv: 'PRJXP_HUB_GITHUB_TOKEN_KEY', defaultPat: null },
    optimization: { vectorDimension: 3, maxSegmentsPerBatch: 32, embeddingTimeout: 20,
      docpipeMaxThreads: 4, readerMaxOutputChars: 0, graphRerankFloor: 0.5,
      graphRerankBGraph: 1, graphRerankCap: 2, graphRerankWBase: 1 },
    githubEnabled: true, githubKeyAvailable: true
  };
}
