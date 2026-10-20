export interface LiveProjectCandidate {
  name: string;
  directory: string;
  hasConfigFile: boolean;
  languages: string[];
}

export interface ProjectCreateRequest {
  name: string;
  kind: 'LIVE' | 'SNAPSHOT';
  rootDir?: string;
  chunoWhiteList?: string;
  tibedBatchSize?: number;
}
