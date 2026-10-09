export interface ToolCall {
  toolName: string;
  arguments: Record<string, unknown>;
  result: string;
}
