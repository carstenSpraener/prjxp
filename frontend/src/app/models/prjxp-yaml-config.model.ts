export interface PrjxpYamlConfig {
  name: string;
  rootDir?: string;           // default "."
  jsonlFile?: string;         // default "px-chunks.jsonl"
  chunoWhiteList?: string;    // default "java,ts"
  tibedBatchSize?: number;    // default 32
  embeddingsFile?: string;     // nullable, for pre-computed embeddings
  tibedResetStore?: boolean;  // default false
  prompt?: string | null;   // optional standing instructions, supports ${ENV} placeholders
  fulltext?: boolean;       // default false, lossless fulltext copy for readFile
}

export const DEFAULT_YAML_CONFIG: Omit<PrjxpYamlConfig, 'name'> = {
  rootDir: '.',
  jsonlFile: 'px-chunks.jsonl',
  chunoWhiteList: 'java,ts',
  tibedBatchSize: 32,
  embeddingsFile: undefined,
  tibedResetStore: false,
  prompt: null,
  fulltext: false
};

export interface ProjectUpdateRequest {
  rootDir?: string;
  chunoWhiteList?: string;
  tibedBatchSize?: number;
  prompt?: string | null;
  fulltext?: boolean;
}

export interface ProjectStats {
  name: string;
  chunkCount: number;
  embeddingCount: number;
  fileCount: number;
  rootDir: string;
  languages: string[];
}

export interface ProjectCreateRequest {
  name: string;
  kind: 'LIVE' | 'SNAPSHOT';
  rootDir?: string;
  chunoWhiteList?: string;
  tibedBatchSize?: number;
  fulltext?: boolean;
}
