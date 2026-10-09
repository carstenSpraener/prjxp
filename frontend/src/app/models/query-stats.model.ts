export interface QueryStats {
  totalQueries: number;
  avgResponseTimeMs: number;
  p95ResponseTimeMs: number;
  cacheHits: number;
  cacheMisses: number;
  recentQueries: QueryStatEntry[];
}

export interface QueryStatEntry {
  query: string;
  responseTimeMs: number;
  resultCount: number;
  fromCache: boolean;
}
