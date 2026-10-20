import { Component, OnInit, signal, effect, ViewChild, ElementRef, AfterViewInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ChatService, ChatPhaseData } from '../../services/chat.service';
import { GlobalsService } from '../../services/globals.service';
import { ChatSettingsPersistenceService } from '../../services/chat-settings-persistence.service';
import { PanelStateService } from '../../services/panel-state.service';
import { ChatMessageComponent } from '../../components/chat-message/chat-message.component';
import { ChatInputComponent } from '../../components/chat-input/chat-input.component';
import { ChatSettingsDialogComponent } from '../../components/chat-settings-dialog/chat-settings-dialog.component';
import { ChatMessage } from '../../models/chat-message.model';
import { catchError, tap, of } from 'rxjs';
function generateId(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
}

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [CommonModule, FormsModule, ChatMessageComponent, ChatInputComponent, ChatSettingsDialogComponent],
  templateUrl: './chat.component.html',
  styleUrl: './chat.component.css'
})
export class ChatComponent implements OnInit, AfterViewInit {
  messages = signal<ChatMessage[]>([]);
  selectedModelName = signal<string>('default');
  projectContext = signal<string | null>(null);
  useTools = signal<boolean>(false);
  showSettings = signal<boolean>(false);
  isSending = signal<boolean>(false);

  @ViewChild('messageArea') messageArea!: ElementRef<HTMLDivElement>;

  // Auto-scroll effect: triggers whenever messages() changes
  private readonly scrollEffect = effect(() => {
    this.messages(); // track signal
    setTimeout(() => {
      if (this.messageArea) {
        this.messageArea.nativeElement.scrollTop = this.messageArea.nativeElement.scrollHeight;
      }
    }, 100);
  });

  constructor(
    private chatService: ChatService,
    private globalsService: GlobalsService,
    private persistence: ChatSettingsPersistenceService,
    private panelState: PanelStateService
  ) {}

  // Auto-save effect: persist messages to localStorage whenever they change
  private readonly saveEffect = effect(() => {
    const msgs = this.messages();
    this.panelState.setChatMessages(msgs);
  });

  ngOnInit(): void {
    this.loadDefaultModel();
    // Restore persisted chat messages from localStorage
    const savedMessages = this.panelState.getChatMessages();
    if (savedMessages.length > 0) {
      this.messages.set(savedMessages);
    }
  }

  ngAfterViewInit(): void {
    // Scroll to bottom on initial load (for pre-existing messages)
    setTimeout(() => {
      if (this.messageArea) {
        this.messageArea.nativeElement.scrollTop = this.messageArea.nativeElement.scrollHeight;
      }
    }, 100);
  }

  loadDefaultModel(): void {
    const persisted = this.persistence.load();

    this.globalsService.getChatModels().pipe(
      tap(models => {
        if (models.length === 0) return;

        // Try to restore persisted model, otherwise use first
        let chosen = models[0].stereoType ?? 'default';
        if (persisted?.model && models.some(m => (m.stereoType ?? '') === persisted.model)) {
          chosen = persisted.model;
        }
        this.selectedModelName.set(chosen);

        // Restore project context if persisted (we don't have the projects list here yet,
        // so we just restore it; the settings dialog will validate on open)
        if (persisted?.projectContext) {
          this.projectContext.set(persisted.projectContext);
        }
        if (persisted?.useTools !== undefined) {
          this.useTools.set(persisted.useTools);
        }
      }),
      catchError(() => of())
    ).subscribe();
  }

  onSettingsClosed(settings: { model: string; projectContext: string | null; useTools: boolean }): void {
    console.log('ChatComponent: onSettingsClosed() received', settings);
    this.selectedModelName.set(settings.model);
    this.projectContext.set(settings.projectContext);
    this.useTools.set(settings.useTools);
    this.showSettings.set(false);
    console.log('ChatComponent: settings applied →', {
      model: this.selectedModelName(),
      project: this.projectContext(),
      tools: this.useTools()
    });

    // Persist to localStorage
    this.persistence.save({
      model: settings.model,
      projectContext: settings.projectContext,
      useTools: settings.useTools
    });
  }

  onMessageSent(text: string): void {
    // Build conversation history from last 10 messages (exclude 'thinking' messages)
    const history: { role: 'user' | 'assistant'; content: string }[] = this.messages()
      .filter(msg => msg.role !== 'thinking')
      .slice(-10)
      .map(msg => ({ role: msg.role as 'user' | 'assistant', content: msg.content }));

    // Append user message
    const userMsg: ChatMessage = {
      id: generateId(),
      role: 'user',
      content: text
    };
    this.messages.update(msgs => [...msgs, userMsg]);

    // Add a thinking bubble that we'll update as phases arrive
    const thinkingId = generateId();
    let initialThinkingContent = 'Verarbeite...';
    if (this.projectContext() && this.projectContext() !== null) {
      initialThinkingContent = 'RAG-Suche laeuft...';
    }
    const thinkingMsg: ChatMessage = {
      id: thinkingId,
      role: 'thinking',
      content: initialThinkingContent
    };
    this.messages.update(msgs => [...msgs, thinkingMsg]);

    // Send to backend with streaming
    this.isSending.set(true);
    this.chatService.sendStreaming(
      text,
      this.selectedModelName(),
      this.projectContext(),
      this.useTools(),
      history
    ).subscribe({
      next: (sseEvent) => {
        if (sseEvent.event === 'phase') {
          const data = sseEvent.data as ChatPhaseData;

          if (data.phase === 'tools') {
            // Update thinking bubble with tool execution message
            this.messages.update(msgs => msgs.map(m =>
              m.id === thinkingId
                ? { ...m, role: 'thinking', content: data.message || 'Tools werden ausgefuehrt...', toolCalls: data.toolCalls }
                : m
            ));
          } else {
            // Update thinking bubble with phase message
            this.messages.update(msgs => msgs.map(m =>
              m.id === thinkingId
                ? { ...m, role: 'thinking', content: data.message || '' }
                : m
            ));
          }
        } else if (sseEvent.event === 'complete') {
          // Replace thinking bubble with final assistant message
          const finalMsg: ChatMessage = {
            id: sseEvent.data.id || thinkingId,
            role: 'assistant',
            content: sseEvent.data.content,
            ragContext: sseEvent.data.ragContext,
            toolCalls: sseEvent.data.toolCalls
          };
          this.messages.update(msgs => msgs.map(m => m.id === thinkingId ? finalMsg : m));
          this.isSending.set(false);
        } else if (sseEvent.event === 'error') {
          // Replace thinking bubble with error message
          this.messages.update(msgs => msgs.map(m =>
            m.id === thinkingId
              ? { ...m, role: 'assistant', content: `Error: ${sseEvent.data.message}` }
              : m
          ));
          this.isSending.set(false);
        }
      },
      error: (err) => {
        this.messages.update(msgs => msgs.map(m =>
          m.id === thinkingId
            ? { ...m, role: 'assistant', content: `Error: ${err.message || 'Unknown error'}` }
            : m
        ));
        this.isSending.set(false);
      },
      complete: () => {
        this.isSending.set(false);
      }
    });
  }

  clearChat(): void {
    this.messages.set([]);
    this.panelState.clearChatMessages();
  }

  openSettings(): void {
    this.showSettings.set(true);
  }
}
