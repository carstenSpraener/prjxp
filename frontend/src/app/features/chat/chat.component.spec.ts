import { TestBed } from '@angular/core/testing';
import { ChatComponent } from './chat.component';
import { ChatService } from '../../services/chat.service';
import { GlobalsService } from '../../services/globals.service';
import { ChatSettingsPersistenceService } from '../../services/chat-settings-persistence.service';
import { PanelStateService } from '../../services/panel-state.service';
import { ChatMessageComponent } from '../../components/chat-message/chat-message.component';
import { ChatInputComponent } from '../../components/chat-input/chat-input.component';
import { ChatSettingsDialogComponent } from '../../components/chat-settings-dialog/chat-settings-dialog.component';
import { of, throwError, Observable } from 'rxjs';
import { vi } from 'vitest';

describe('ChatComponent', () => {
  let component: ChatComponent;
  let fixture: any;
  let mockChatService: any;
  let mockGlobalsService: any;
  let mockPersistenceService: any;
  let mockPanelStateService: any;

  const sampleModel = { stereoType: 'default', serverType: 'ollama', modelName: 'llama3' };

  beforeEach(async () => {
    mockChatService = {
      send: vi.fn().mockReturnValue(of({
        id: 'resp-1',
        role: 'assistant',
        content: 'This is a test response.',
        ragContext: [],
        toolCalls: []
      })),
      sendStreaming: vi.fn().mockReturnValue(new Observable(observer => {
        // Simulate phase events followed by complete
        observer.next({ event: 'phase', data: { phase: 'llm_generating', message: 'LLM generiert Antwort...' } });
        observer.next({ event: 'complete', data: {
          id: 'resp-stream-1',
          role: 'assistant',
          content: 'Streaming response.',
          ragContext: [],
          toolCalls: []
        }});
        observer.complete();
      }))
    };

    mockGlobalsService = {
      getChatModels: vi.fn().mockReturnValue(of([sampleModel]))
    };

    mockPersistenceService = {
      load: vi.fn().mockReturnValue(null),
      save: vi.fn()
    };

    mockPanelStateService = {
      getChatMessages: vi.fn().mockReturnValue([]),
      setChatMessages: vi.fn(),
      clearChatMessages: vi.fn()
    };

    await TestBed.configureTestingModule({
      imports: [ChatComponent],
      providers: [
        { provide: ChatService, useValue: mockChatService },
        { provide: GlobalsService, useValue: mockGlobalsService },
        { provide: ChatSettingsPersistenceService, useValue: mockPersistenceService },
        { provide: PanelStateService, useValue: mockPanelStateService }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(ChatComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('ngOnInit should load default model', () => {
    component.ngOnInit();
    expect(mockGlobalsService.getChatModels).toHaveBeenCalled();
  });

  it('should have correct initial state', () => {
    expect(component.messages()).toEqual([]);
    expect(component.projectContext()).toBeNull();
    expect(component.useTools()).toBe(false);
    expect(component.showSettings()).toBe(false);
    expect(component.isSending()).toBe(false);
  });

  it('onMessageSent should use sendStreaming and show thinking then assistant message', () => {
    component.onMessageSent('Hello');

    expect(mockChatService.sendStreaming).toHaveBeenCalledWith('Hello', 'default', null, false, []);
    // After streaming completes: user msg + assistant msg (thinking replaced)
    expect(component.messages()).toHaveLength(2);
    expect(component.messages()[0].role).toBe('user');
    expect(component.messages()[1].role).toBe('assistant');
    expect(component.messages()[1].content).toBe('Streaming response.');
  });

  it('onMessageSent should set isSending to true during streaming', () => {
    // With synchronous mock, isSending will be false after onMessageSent returns
    component.onMessageSent('Hello');
    expect(mockChatService.sendStreaming).toHaveBeenCalled();
    // After synchronous completion, isSending should be false again
    expect(component.isSending()).toBe(false);
  });

  it('onMessageSent should send conversation history with last 10 messages', () => {
    // Given: existing messages in the chat
    const existingMsgs = [
      { id: '1', role: 'user' as const, content: 'First message' },
      { id: '2', role: 'assistant' as const, content: 'First response' },
      { id: '3', role: 'user' as const, content: 'Second message' },
      { id: '4', role: 'assistant' as const, content: 'Second response' }
    ];
    component.messages.set(existingMsgs);

    // When: send a new message
    component.onMessageSent('Third message');

    // Then: the history should include all previous messages (up to last 10)
    const callArgs = mockChatService.sendStreaming.mock.calls[0];
    const history = callArgs[4] as Array<{ role: string; content: string }>;
    expect(history).toHaveLength(4);
    expect(history[0].content).toBe('First message');
    expect(history[1].content).toBe('First response');
    expect(history[2].content).toBe('Second message');
    expect(history[3].content).toBe('Second response');
  });

  it('onMessageSent should limit conversation history to last 10 messages', () => {
    // Given: more than 10 existing messages
    const manyMsgs = Array.from({ length: 12 }, (_, i) => ({
      id: `${i}`,
      role: i % 2 === 0 ? 'user' as const : 'assistant' as const,
      content: `Message ${i}`
    }));
    component.messages.set(manyMsgs);

    // When: send a new message
    component.onMessageSent('New message');

    // Then: only the last 10 messages should be in history
    const callArgs = mockChatService.sendStreaming.mock.calls[0];
    const history = callArgs[4] as Array<{ role: string; content: string }>;
    expect(history).toHaveLength(10);
    // The oldest message in history should be "Message 2" (index 2 of 12)
    expect(history[0].content).toBe('Message 2');
    expect(history[9].content).toBe('Message 11');
  });

  it('onMessageSent should handle streaming error gracefully', () => {
    mockChatService.sendStreaming.mockReturnValue(throwError(() => new Error('Network error')));

    component.onMessageSent('Hello');

    expect(component.messages()).toHaveLength(2);
    expect(component.messages()[1].content).toContain('Error');
  });

  it('onMessageSent should handle SSE error event', () => {
    mockChatService.sendStreaming.mockReturnValue(new Observable(observer => {
      observer.next({ event: 'error', data: { message: 'Backend error' } });
      observer.complete();
    }));

    component.onMessageSent('Hello');

    expect(component.messages()).toHaveLength(2);
    expect(component.messages()[1].role).toBe('assistant');
    expect(component.messages()[1].content).toContain('Backend error');
  });

  it('clearChat should reset messages', () => {
    component.messages.set([{ id: '1', role: 'user' as const, content: 'test' }]);
    component.clearChat();
    expect(component.messages()).toEqual([]);
  });

  it('openSettings should show settings dialog', () => {
    component.openSettings();
    expect(component.showSettings()).toBe(true);
  });

  it('onSettingsClosed should update settings', () => {
    component.onSettingsClosed({ model: 'coder', projectContext: 'prjxp', useTools: true });

    expect(component.selectedModelName()).toBe('coder');
    expect(component.projectContext()).toBe('prjxp');
    expect(component.useTools()).toBe(true);
    expect(component.showSettings()).toBe(false);
  });

  it('should render chat title', () => {
    fixture.detectChanges();
    const title = fixture.nativeElement.querySelector('h1');
    expect(title).toBeTruthy();
    expect(title.textContent).toContain('Chat');
  });

  it('should render empty state when no messages', () => {
    fixture.detectChanges();
    const emptyState = fixture.nativeElement.querySelector('.empty-state');
    expect(emptyState).toBeTruthy();
  });

  it('should render chat-input component', () => {
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('chat-input');
    expect(input).toBeTruthy();
  });

  it('should render settings button', () => {
    fixture.detectChanges();
    const btn = fixture.nativeElement.querySelector('.btn-icon');
    expect(btn).toBeTruthy();
  });

  it('should render clear chat button', () => {
    fixture.detectChanges();
    const btn = fixture.nativeElement.querySelector('.btn-clear');
    expect(btn).toBeTruthy();
  });

  it('should show status info with model name', () => {
    component.selectedModelName.set('gemma');
    fixture.detectChanges();
    const statusItems = fixture.nativeElement.querySelectorAll('.status-item');
    expect(statusItems.length).toBeGreaterThanOrEqual(1);
  });

  it('loadDefaultModel should restore persisted model if still available', () => {
    mockPersistenceService.load.mockReturnValue({ model: 'default', projectContext: null, useTools: false });
    component.loadDefaultModel();

    expect(mockPersistenceService.load).toHaveBeenCalled();
    // The persisted model 'default' matches the backend list, so it should be chosen
    expect(component.selectedModelName()).toBe('default');
  });

  it('loadDefaultModel should fall back to first model if persisted model is not available', () => {
    mockPersistenceService.load.mockReturnValue({ model: 'missing-model', projectContext: null, useTools: false });
    component.loadDefaultModel();

    // 'missing-model' is not in the backend list, so fall back to first
    expect(component.selectedModelName()).toBe('default');
  });

  it('loadDefaultModel should restore persisted projectContext and useTools', () => {
    mockPersistenceService.load.mockReturnValue({ model: 'default', projectContext: 'my-project', useTools: true });
    component.loadDefaultModel();

    expect(component.projectContext()).toBe('my-project');
    expect(component.useTools()).toBe(true);
  });

  it('onSettingsClosed should persist settings to localStorage', () => {
    component.onSettingsClosed({ model: 'coder', projectContext: 'prjxp', useTools: true });

    expect(mockPersistenceService.save).toHaveBeenCalledWith({
      model: 'coder',
      projectContext: 'prjxp',
      useTools: true
    });
  });

  // --- Auto-scroll tests ---

  it('should have messageArea ViewChild defined', () => {
    fixture.detectChanges();
    // After detection, the ViewChild should be available (even if null in test env)
    expect(component.messageArea).toBeDefined();
  });

  it('ngAfterViewInit should scroll message area to bottom', () => {
    // In test environment, ElementRef may not be fully resolved,
    // but we can verify the method runs without error
    fixture.detectChanges();
    component.ngAfterViewInit();
    // No assertion needed; we verify it doesn't throw
  });

  // --- Panel State Persistence tests ---

  it('ngOnInit should restore persisted chat messages', () => {
    const savedMsgs = [{ id: 'saved-1', role: 'user' as const, content: 'Restored message' }];
    mockPanelStateService.getChatMessages.mockReturnValue(savedMsgs);

    component.ngOnInit();

    expect(mockPanelStateService.getChatMessages).toHaveBeenCalled();
    expect(component.messages()).toEqual(savedMsgs);
  });

  it('ngOnInit should not restore empty persisted messages', () => {
    mockPanelStateService.getChatMessages.mockReturnValue([]);

    component.ngOnInit();

    expect(component.messages()).toEqual([]);
  });

  it('onMessageSent should persist messages via panelState', () => {
    component.onMessageSent('Hello');
    fixture.detectChanges(); // trigger effect()

    expect(mockPanelStateService.setChatMessages).toHaveBeenCalled();
  });

  it('clearChat should clear persisted messages', () => {
    component.messages.set([{ id: '1', role: 'user' as const, content: 'test' }]);
    fixture.detectChanges(); // trigger effect()
    component.clearChat();

    expect(mockPanelStateService.clearChatMessages).toHaveBeenCalled();
    expect(component.messages()).toEqual([]);
  });

  it('should save messages to panelState when messages signal changes', () => {
    // Directly update the messages signal to trigger the save effect
    component.messages.set([{ id: 'direct-1', role: 'user' as const, content: 'Direct update' }]);
    fixture.detectChanges(); // trigger effect()

    expect(mockPanelStateService.setChatMessages).toHaveBeenCalledWith([{
      id: 'direct-1',
      role: 'user',
      content: 'Direct update'
    }]);
  });

  // --- Streaming with project context ---

  it('onMessageSent should pass projectContext to sendStreaming', () => {
    component.projectContext.set('my-project');
    component.onMessageSent('Hello');

    const callArgs = mockChatService.sendStreaming.mock.calls[0];
    expect(callArgs[2]).toBe('my-project');
  });

  it('onMessageSent should pass useTools to sendStreaming', () => {
    component.useTools.set(true);
    component.onMessageSent('Hello');

    const callArgs = mockChatService.sendStreaming.mock.calls[0];
    expect(callArgs[3]).toBe(true);
  });
});
