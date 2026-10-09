import { TestBed } from '@angular/core/testing';
import { ChatInputComponent } from './chat-input.component';
import { vi } from 'vitest';

describe('ChatInputComponent', () => {
  let component: ChatInputComponent;
  let fixture: any;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChatInputComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(ChatInputComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should have empty messageText initially', () => {
    expect(component.messageText).toBe('');
  });

  it('sendMessage should emit text and clear input', () => {
    const emitted: string[] = [];
    component.send.subscribe(val => emitted.push(val));

    component.messageText = 'Hello world';
    component.sendMessage();

    expect(emitted).toEqual(['Hello world']);
    expect(component.messageText).toBe('');
  });

  it('sendMessage should not emit empty text', () => {
    const emitted: string[] = [];
    component.send.subscribe(val => emitted.push(val));

    component.messageText = '   ';
    component.sendMessage();

    expect(emitted).toEqual([]);
  });

  it('onKeydown with Enter should send message', () => {
    const emitted: string[] = [];
    component.send.subscribe(val => emitted.push(val));

    component.messageText = 'Test message';
    const event = new KeyboardEvent('keydown', { key: 'Enter' });
    const preventSpy = vi.spyOn(event, 'preventDefault');

    component.onKeydown(event);

    expect(emitted).toEqual(['Test message']);
    expect(component.messageText).toBe('');
    expect(preventSpy).toHaveBeenCalled();
  });

  it('onKeydown with Shift+Enter should not send', () => {
    const emitted: string[] = [];
    component.send.subscribe(val => emitted.push(val));

    component.messageText = 'Test message';
    const event = new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true });

    component.onKeydown(event);

    expect(emitted).toEqual([]);
    expect(component.messageText).toBe('Test message');
  });

  it('onKeydown with other keys should not send', () => {
    const emitted: string[] = [];
    component.send.subscribe(val => emitted.push(val));

    component.messageText = 'Test message';
    const event = new KeyboardEvent('keydown', { key: 'a' });

    component.onKeydown(event);

    expect(emitted).toEqual([]);
  });

  it('should render textarea and send button', () => {
    fixture.detectChanges();
    const textarea = fixture.nativeElement.querySelector('textarea');
    expect(textarea).toBeTruthy();

    const btn = fixture.nativeElement.querySelector('.btn-send');
    expect(btn).toBeTruthy();
    expect(btn.textContent).toContain('Send');
  });
});
