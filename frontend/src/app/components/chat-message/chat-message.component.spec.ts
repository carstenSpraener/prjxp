import { TestBed } from '@angular/core/testing';
import { ChatMessageComponent } from './chat-message.component';
import { ChatMessage } from '../../models/chat-message.model';
import { provideMarkdown } from 'ngx-markdown';
import { vi } from 'vitest';

describe('ChatMessageComponent', () => {
  let component: ChatMessageComponent;
  let fixture: any;

  const userMessage: ChatMessage = {
    id: '1',
    role: 'user',
    content: 'How does RAG work?'
  };

  const assistantMessage: ChatMessage = {
    id: '2',
    role: 'assistant',
    content: 'RAG uses retrieval-augmented generation.',
    ragContext: [
      { projectName: 'prjxp', chunkId: 'chunk-1', snippet: 'RAG context snippet', score: 0.95 }
    ],
    toolCalls: [
      { toolName: 'project-search', arguments: {}, result: 'Found 3 results' }
    ]
  };

  const thinkingMessage: ChatMessage = {
    id: '3',
    role: 'thinking',
    content: 'RAG-Suche laeuft...'
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChatMessageComponent],
      providers: [provideMarkdown()]
    }).compileComponents();

    fixture = TestBed.createComponent(ChatMessageComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('isUser should be true for user messages', () => {
    component.message = userMessage;
    expect(component.isUser).toBe(true);
  });

  it('isUser should be false for assistant messages', () => {
    component.message = assistantMessage;
    expect(component.isUser).toBe(false);
  });

  it('isThinking should be true for thinking messages', () => {
    component.message = thinkingMessage;
    expect(component.isThinking).toBe(true);
  });

  it('isThinking should be false for assistant messages', () => {
    component.message = assistantMessage;
    expect(component.isThinking).toBe(false);
  });

  it('isAssistant should be true for assistant messages', () => {
    component.message = assistantMessage;
    expect(component.isAssistant).toBe(true);
  });

  it('isAssistant should be false for thinking messages', () => {
    component.message = thinkingMessage;
    expect(component.isAssistant).toBe(false);
  });

  it('hasRagContext should be true when ragContext is present', () => {
    component.message = assistantMessage;
    expect(component.hasRagContext).toBe(true);
  });

  it('hasRagContext should be false when no ragContext', () => {
    component.message = userMessage;
    expect(component.hasRagContext).toBe(false);
  });

  it('hasToolCalls should be true when toolCalls are present', () => {
    component.message = assistantMessage;
    expect(component.hasToolCalls).toBe(true);
  });

  it('hasToolCalls should be false when no toolCalls', () => {
    component.message = userMessage;
    expect(component.hasToolCalls).toBe(false);
  });

  it('toggleRagContext should toggle showRagContext', () => {
    component.showRagContext = false;
    component.toggleRagContext();
    expect(component.showRagContext).toBe(true);
    component.toggleRagContext();
    expect(component.showRagContext).toBe(false);
  });

  it('toggleToolCalls should toggle showToolCalls', () => {
    component.showToolCalls = false;
    component.toggleToolCalls();
    expect(component.showToolCalls).toBe(true);
    component.toggleToolCalls();
    expect(component.showToolCalls).toBe(false);
  });

  it('should render user message with correct styling', () => {
    component.message = userMessage;
    fixture.detectChanges();
    const wrapper = fixture.nativeElement.querySelector('.message-wrapper');
    expect(wrapper.classList).toContain('user');
  });

  it('should render assistant message with correct styling', () => {
    component.message = assistantMessage;
    fixture.detectChanges();
    const wrapper = fixture.nativeElement.querySelector('.message-wrapper');
    expect(wrapper.classList).toContain('assistant');
  });

  it('should render thinking message with correct styling', () => {
    component.message = thinkingMessage;
    fixture.detectChanges();
    const wrapper = fixture.nativeElement.querySelector('.message-wrapper');
    expect(wrapper.classList).toContain('thinking');
  });

  it('should show RAG context toggle button for assistant with ragContext', () => {
    component.message = assistantMessage;
    fixture.detectChanges();
    const btn = fixture.nativeElement.querySelector('.rag-toggle-btn');
    expect(btn).toBeTruthy();
  });

  it('should not show RAG context toggle for user messages', () => {
    component.message = userMessage;
    fixture.detectChanges();
    const btn = fixture.nativeElement.querySelector('.rag-toggle-btn');
    expect(btn).toBeFalsy();
  });

  it('should show tool calls toggle button for assistant with toolCalls', () => {
    component.message = assistantMessage;
    fixture.detectChanges();
    const btn = fixture.nativeElement.querySelector('.tool-toggle-btn');
    expect(btn).toBeTruthy();
  });

  it('should show pulsing dots for thinking messages', () => {
    component.message = thinkingMessage;
    fixture.detectChanges();
    const dots = fixture.nativeElement.querySelectorAll('.dot');
    expect(dots.length).toBe(3);
  });

  it('should show thinking text content', () => {
    component.message = thinkingMessage;
    fixture.detectChanges();
    const textEl = fixture.nativeElement.querySelector('.thinking-text');
    expect(textEl).toBeTruthy();
    expect(textEl.textContent).toContain('RAG-Suche laeuft');
  });

  it('should not show pulsing dots for assistant messages', () => {
    component.message = assistantMessage;
    fixture.detectChanges();
    const dots = fixture.nativeElement.querySelectorAll('.dot');
    expect(dots.length).toBe(0);
  });

  it('should render assistant message with markdown component', () => {
    component.message = assistantMessage;
    fixture.detectChanges();
    const markdownEl = fixture.nativeElement.querySelector('markdown');
    expect(markdownEl).toBeTruthy();
  });

  it('should render user message with pre tag (plain text)', () => {
    component.message = userMessage;
    fixture.detectChanges();
    const preEl = fixture.nativeElement.querySelector('pre.message-content');
    expect(preEl).toBeTruthy();
    const markdownEl = fixture.nativeElement.querySelector('markdown');
    expect(markdownEl).toBeFalsy();
  });

  it('should not render markdown component for thinking messages', () => {
    component.message = thinkingMessage;
    fixture.detectChanges();
    const markdownEl = fixture.nativeElement.querySelector('markdown');
    expect(markdownEl).toBeFalsy();
  });
});
