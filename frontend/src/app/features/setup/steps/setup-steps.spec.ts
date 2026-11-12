import { TestBed } from '@angular/core/testing';
import { SetupEmbeddingStepComponent } from './setup-embedding-step.component';
import { SetupGithubStepComponent } from './setup-github-step.component';
import { SetupChatStepComponent } from './setup-chat-step.component';
import { SetupOptimizationStepComponent } from './setup-optimization-step.component';
import { setupConfiguration } from '../setup-fixtures';
import { urlOrEnv, numeric, nonBlank } from '../setup-validators';
import { FormControl } from '@angular/forms';

describe('Setup typed forms', () => {
  it('validates URLs, environment references, nonblank names and finite numeric boundaries', () => {
    for (const value of ['http://localhost:11434', 'https://api.example/v1', '${URL}', '${URL:http://localhost}'])
      expect(urlOrEnv(new FormControl(value))).toBeNull();
    for (const value of ['', 'ftp://bad', 'https://', '${bad key}'])
      expect(urlOrEnv(new FormControl(value))).toBeTruthy();
    expect(nonBlank(new FormControl('  '))).toBeTruthy();
    for (const value of [NaN, Infinity, -1, 1.5]) expect(numeric(1, true)(new FormControl(value))).toBeTruthy();
    expect(numeric(0, true)(new FormControl(0))).toBeNull();
    expect(numeric(1, true)(new FormControl(null))).toBeNull();
  });
  it('embedding prefill/reload, invalidation and explicit reset retain typed secrets', () => {
    const fixture = TestBed.createComponent(SetupEmbeddingStepComponent);
    fixture.componentRef.setInput('initial', setupConfiguration().embedding);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    const draft = vi.spyOn(c.draftChange, 'emit');
    c.form.controls.dimensions.setValue(null);
    expect(draft).toHaveBeenLastCalledWith(expect.objectContaining({dimensions: null, resetDimensions: true}));
    c.form.controls.dimensions.setValue(5);
    expect(draft).toHaveBeenLastCalledWith(expect.objectContaining({dimensions: 5, resetDimensions: false}));
    for (const secret of ['********', '${KEY}', '', null]) {
      c.form.controls.apiKey.setValue(secret);
      expect(c.configuration().apiKey).toBe(secret);
    }
    c.form.controls.timeout.setValue(1.5);
    expect(c.form.invalid).toBe(true);
    fixture.componentRef.setInput('initial', {...setupConfiguration().embedding, modelName: 'reloaded'});
    fixture.detectChanges();
    expect(c.form.controls.modelName.value).toBe('reloaded');
    const test = vi.spyOn(c.testRequested, 'emit');
    c.requestTest();
    expect(test).toHaveBeenCalledOnce();
    fixture.componentRef.setInput('busy', true);
    fixture.detectChanges();
    c.requestTest();
    expect(test).toHaveBeenCalledOnce();
    expect(fixture.nativeElement.querySelector('input[type=password]').autocomplete).toBe('new-password');
    fixture.destroy();
  });
  it('github key name and optional PAT are distinct and defaults are valid', () => {
    const fixture = TestBed.createComponent(SetupGithubStepComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    expect(c.configuration().tokenKeyEnv).toBe('PRJXP_HUB_GITHUB_TOKEN_KEY');
    fixture.componentRef.setInput('initial', setupConfiguration().github);
    fixture.detectChanges();
    c.form.controls.tokenKeyEnv.setValue('invalid name');
    expect(c.form.invalid).toBe(true);
    c.form.controls.tokenKeyEnv.setValue('VALID_KEY');
    c.form.controls.defaultPat.setValue('********');
    const test = vi.spyOn(c.testRequested, 'emit');
    c.requestTest();
    expect(test).toHaveBeenCalledWith({tokenKeyEnv: 'VALID_KEY', defaultPat: '********'});
    fixture.destroy();
  });
  it('chat keeps default stereotype and validates supplier temperature, defaults and reload', () => {
    const fixture = TestBed.createComponent(SetupChatStepComponent);
    fixture.componentRef.setInput('initial', setupConfiguration().chat);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    expect(c.configuration().stereoType).toBe('default');
    c.form.controls.temperature.setValue(3);
    expect(c.form.invalid).toBe(true);
    c.form.controls.temperature.setValue(0);
    c.form.controls.serverType.setValue('gemini');
    c.form.controls.temperature.setValue(2);
    expect(c.form.valid).toBe(true);
    c.form.controls.timeoutSecs.setValue(0);
    c.requestTest();
    fixture.destroy();
  });
  it('optimization enforces dimension agreement and all advanced ranges without clamping', () => {
    const fixture = TestBed.createComponent(SetupOptimizationStepComponent);
    fixture.componentRef.setInput('initial', setupConfiguration().optimization);
    fixture.componentRef.setInput('measuredDimension', 3);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    expect(c.form.valid).toBe(true);
    c.form.controls.vectorDimension.setValue(4);
    expect(c.form.invalid).toBe(true);
    c.form.controls.vectorDimension.setValue(null);
    expect(c.configuration().vectorDimension).toBe(3);
    for (const key of ['graphRerankFloor', 'graphRerankBGraph', 'graphRerankCap', 'graphRerankWBase'] as const) {
      const original = c.form.controls[key].value;
      c.form.controls[key].setValue(-1);
      expect(c.form.invalid).toBe(true);
      c.form.controls[key].setValue(original);
    }
    c.form.controls.readerMaxOutputChars.setValue(0);
    expect(c.form.valid).toBe(true);
    fixture.componentRef.setInput('measuredDimension', null);
    fixture.detectChanges();
    expect(c.form.invalid).toBe(true);
    fixture.destroy();
  });
});
