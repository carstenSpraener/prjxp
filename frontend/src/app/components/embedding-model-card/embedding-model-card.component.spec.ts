import { TestBed } from '@angular/core/testing';
import { EmbeddingModelCardComponent } from './embedding-model-card.component';
describe('EmbeddingModelCardComponent', () => {
  it('renders shared fields, badge, host, seconds, dimensions and batch', () => {
    const fixture = TestBed.createComponent(EmbeddingModelCardComponent);
    fixture.componentRef.setInput('model', {type: 'OLLAMA', apiBaseURL: 'http://localhost:11434',
      modelName: 'embed', timeout: 20, dimensions: 384, maxSegmentsPerBatch: 32});
    fixture.detectChanges();
    const c = fixture.componentInstance;
    expect(c.serverTypeLabel).toBe('Ollama');
    expect(c.serverTypeBadgeClass).toBe('badge-ollama');
    expect(c.hostFromUrl).toBe('localhost:11434');
    expect(fixture.nativeElement.textContent).toContain('20s');
    expect(fixture.nativeElement.textContent).toContain('384');
    expect(fixture.nativeElement.textContent).toContain('32');
    const edit = vi.spyOn(c.edit, 'emit');
    fixture.nativeElement.querySelector('.btn-edit').click();
    expect(edit).toHaveBeenCalledOnce();
  });
  it('supports OpenAI and environment URLs; empty depends on config not timeout', () => {
    const fixture = TestBed.createComponent(EmbeddingModelCardComponent);
    const c = fixture.componentInstance;
    c.model = {type: 'OPEN_AI', apiBaseURL: '${URL}'};
    expect(c.serverTypeLabel).toBe('OpenAI');
    expect(c.serverTypeBadgeClass).toBe('badge-openai');
    expect(c.hostFromUrl).toBe('${URL}');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.empty-hint')).toBeNull();
  });
  it('handles unknown or absent configuration safely', () => {
    const fixture = TestBed.createComponent(EmbeddingModelCardComponent);
    const c = fixture.componentInstance;
    expect(c.serverTypeLabel).toBe('Unknown');
    expect(c.serverTypeBadgeClass).toBe('badge-unknown');
    expect(c.hostFromUrl).toBe('');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.empty-hint')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.server-badge')).toBeNull();
    c.model = {type: 'future'};
    expect(c.serverTypeLabel).toBe('future');
  });
  it('defaults for type and timeout do not imply a configured model', () => {
    const fixture = TestBed.createComponent(EmbeddingModelCardComponent);
    fixture.componentRef.setInput('model', {type: 'OLLAMA', timeout: 20, maxSegmentsPerBatch: 32});
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.empty-hint')).toBeTruthy();
  });
});
