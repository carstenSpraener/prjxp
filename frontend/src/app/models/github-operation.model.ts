export interface GithubOperation {
  key: string;
  status: 'PENDING' | 'SUCCESS' | 'FAILED';
  message: string | null;
  timestamp: number;
}
