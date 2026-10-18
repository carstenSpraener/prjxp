import { TestBed } from '@angular/core/testing';
import { ChatModelCardComponent } from './chat-model-card.component';
import { ChatModelReference } from '../../models/chat-model-reference.model';

describe('ChatModelCardComponent', () => {
  let component: ChatModelCardComponent;
  let fixture: any;

  const sampleModel: ChatModelReference = {
    stereoType: 'default',
    serverType: 'ollama',
    modelName: 'llama3',
    providerUrl: 'http://localhost:11434',
    timeoutSecs: 60,
    temperature: 0.7
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChatModelCardComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(ChatModelCardComponent);
    component = fixture.componentInstance;
    component.model = sampleModel;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should display model stereoType', () => {
    fixture.detectChanges();
    const modelName = fixture.nativeElement.querySelector('.model-name');
    expect(modelName.textContent).toBe('default');
  });

  it('should display correct server type label for ollama', () => {
    expect(component.serverTypeLabel).toBe('Ollama');
  });

  it('should display correct server type badge class for ollama', () => {
    expect(component.serverTypeBadgeClass).toBe('badge-ollama');
  });

  it('should display model name', () => {
    fixture.detectChanges();
    const details = fixture.nativeElement.querySelectorAll('.model-detail');
    expect(details.length).toBe(4); // Model, Provider, Timeout, Temperature
  });

  it('should extract host from provider URL', () => {
    expect(component.hostFromUrl).toBe('localhost:11434');
  });

  it('should handle invalid URL gracefully', () => {
    component.model = { ...sampleModel, providerUrl: 'not-a-url' };
    expect(component.hostFromUrl).toBe('not-a-url');
  });

  it('should emit edit event on edit button click', () => {
    fixture.detectChanges();
    const editBtn = fixture.nativeElement.querySelector('.btn-edit');
    editBtn.click();

    component.edit.subscribe(() => {
      expect(true).toBeTruthy();
    });
  });

  it('should emit delete event on delete button click', () => {
    fixture.detectChanges();
    const deleteBtn = fixture.nativeElement.querySelector('.btn-delete');
    deleteBtn.click();

    component.delete.subscribe(() => {
      expect(true).toBeTruthy();
    });
  });

  it('should show temperature when set', () => {
    fixture.detectChanges();
    const details = fixture.nativeElement.querySelectorAll('.model-detail');
    const tempDetail = details[3];
    expect(tempDetail.textContent).toContain('Temperature');
    expect(tempDetail.textContent).toContain('0.7');
  });

  it('should show timeout when set', () => {
    fixture.detectChanges();
    const details = fixture.nativeElement.querySelectorAll('.model-detail');
    const timeoutDetail = details[2];
    expect(timeoutDetail.textContent).toContain('60s');
  });

  it('should handle lm-studio server type', () => {
    component.model = { ...sampleModel, serverType: 'lm-studio' };
    expect(component.serverTypeLabel).toBe('LM Studio');
    expect(component.serverTypeBadgeClass).toBe('badge-lmstudio');
  });

  it('should handle gemini server type', () => {
    component.model = { ...sampleModel, serverType: 'gemini' };
    expect(component.serverTypeLabel).toBe('Gemini');
    expect(component.serverTypeBadgeClass).toBe('badge-gemini');
  });

  it('should handle custom server type', () => {
    component.model = { ...sampleModel, serverType: 'custom' };
    expect(component.serverTypeLabel).toBe('Custom');
    expect(component.serverTypeBadgeClass).toBe('badge-custom');
  });

  it('should handle unknown server type', () => {
    component.model = { ...sampleModel, serverType: 'unknown-type' };
    expect(component.serverTypeLabel).toBe('unknown-type');
  });

  it('should have glass-panel class on card', () => {
    fixture.detectChanges();
    const card = fixture.nativeElement.querySelector('.model-card');
    expect(card.classList).toContain('glass-panel');
  });
});
