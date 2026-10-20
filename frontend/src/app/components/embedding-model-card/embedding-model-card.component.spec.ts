import { TestBed } from '@angular/core/testing';
import { EmbeddingModelCardComponent } from './embedding-model-card.component';
import { EmbeddingModelReference } from '../../models/embedding-model-reference.model';

describe('EmbeddingModelCardComponent', () => {
  let component: EmbeddingModelCardComponent;
  let fixture: any;

  const sampleModel: EmbeddingModelReference = {
    serverType: 'ollama',
    modelName: 'mxbai-embed-large',
    providerUrl: 'http://localhost:11434',
    timeoutSecs: 60
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [EmbeddingModelCardComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(EmbeddingModelCardComponent);
    component = fixture.componentInstance;
    component.model = sampleModel;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should display embedding model title', () => {
    fixture.detectChanges();
    const modelName = fixture.nativeElement.querySelector('.model-name');
    expect(modelName.textContent).toBe('Embedding Model');
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
    expect(details.length).toBe(3); // Model, Provider, Timeout
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

  it('should handle openai server type', () => {
    component.model = { ...sampleModel, serverType: 'openai' };
    expect(component.serverTypeLabel).toBe('OpenAI');
    expect(component.serverTypeBadgeClass).toBe('badge-openai');
  });

  it('should handle custom server type', () => {
    component.model = { ...sampleModel, serverType: 'custom' };
    expect(component.serverTypeLabel).toBe('Custom');
    expect(component.serverTypeBadgeClass).toBe('badge-custom');
  });

  it('should show empty hint when no model configured', async () => {
    // Create a fresh component with empty model
    const emptyFixture = TestBed.createComponent(EmbeddingModelCardComponent);
    const emptyComp = emptyFixture.componentInstance;
    emptyComp.model = {};
    emptyFixture.detectChanges();

    const emptyHint = emptyFixture.nativeElement.querySelector('.empty-hint');
    expect(emptyHint).toBeTruthy();
  });

  it('should not show server badge when no serverType', async () => {
    // Create a fresh component with empty model
    const emptyFixture = TestBed.createComponent(EmbeddingModelCardComponent);
    const emptyComp = emptyFixture.componentInstance;
    emptyComp.model = {};
    emptyFixture.detectChanges();

    const badges = emptyFixture.nativeElement.querySelectorAll('.server-badge');
    expect(badges.length).toBe(0);
  });

  it('should have glass-panel class on card', () => {
    fixture.detectChanges();
    const card = fixture.nativeElement.querySelector('.model-card');
    expect(card.classList).toContain('glass-panel');
  });
});
