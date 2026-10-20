import { RagContext } from './rag-context.model';
import { ToolCall } from './tool-call.model';

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant' | 'thinking';
  content: string;
  ragContext?: RagContext[];
  toolCalls?: ToolCall[];
}
