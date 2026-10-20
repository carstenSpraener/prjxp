export interface ProjectInfo {
  name: string;
  status: 'IMPORTING' | 'CHUNKING' | 'EMBEDDING' | 'READY' | 'FAILED';
  kind?: 'LIVE' | 'SNAPSHOT';
  lastError?: string;
  chunkCount?: number;
  embeddingCount?: number;
  fileCount?: number;
  // YAML configuration fields (from backend)
  rootDir?: string;
  jsonlFile?: string;
  chunoWhiteList?: string;
  tibedBatchSize?: number;
  embeddingsFile?: string | null;
  tibedResetStore?: boolean;
  // Reindex progress tracking
  reindexStatus?: string;   // "IDLE", "CHUNKING", "EMBEDDING", "IMPORTING", "DOCPIPE", "COMPLETE", "FAILED"
  reindexProgress?: number; // 0-100 (10% chunking, 70% embedding, 20% storing)
  prompt?: string | null;   // standing instructions (from prjxp.yaml)
  fulltext?: boolean;       // lossless fulltext copy switch (from prjxp.yaml)
}
