import { Component, HostListener, OnInit, OnDestroy, ChangeDetectionStrategy, AfterViewChecked, ViewChild, ElementRef } from '@angular/core';
import { ActivatedRoute, Router, RouterLink, CanDeactivateFn } from '@angular/router';
import { Observable, Subscription, concatMap, of, throwError, skip } from 'rxjs';
import { SetupService } from '../../services/setup.service';
import { GlobalsService } from '../../services/globals.service';
import { ChatModelRequest, EmbeddingConfigRequest, GithubConfigRequest, OptimizationRequest,
  SetupConfiguration, SetupSaveRequest, SetupStatus, SetupStep, SetupTestResult } from '../../models/setup.model';
import { SetupEmbeddingStepComponent } from './steps/setup-embedding-step.component';
import { SetupGithubStepComponent } from './steps/setup-github-step.component';
import { SetupChatStepComponent } from './steps/setup-chat-step.component';
import { SetupOptimizationStepComponent } from './steps/setup-optimization-step.component';
import { setupError } from './setup-validators';

type Drafts = {embedding: EmbeddingConfigRequest; github: GithubConfigRequest; chat: ChatModelRequest;
  optimization: OptimizationRequest};
type Receipt = {revision: number; result: SetupTestResult};
const canonical = (value: unknown): string | undefined => JSON.stringify(value, (_, entry: unknown) =>
  entry && typeof entry === 'object' && !Array.isArray(entry) ?
    Object.fromEntries(Object.entries(entry).sort(([a], [b]) => a.localeCompare(b))) : entry);
const equal = (a: unknown, b: unknown) => canonical(a) === canonical(b);
export const setupCanDeactivate: CanDeactivateFn<SetupWizardComponent> = component => component.canDeactivate();

@Component({selector: 'app-setup-wizard', standalone: true,
  changeDetection: ChangeDetectionStrategy.Default,
  imports: [RouterLink, SetupEmbeddingStepComponent, SetupGithubStepComponent, SetupChatStepComponent,
    SetupOptimizationStepComponent],
  templateUrl: './setup-wizard.component.html', styleUrl: './setup-wizard.component.css'})
export class SetupWizardComponent implements OnInit, OnDestroy, AfterViewChecked {
  readonly steps: SetupStep[] = ['embedding', 'github', 'chat', 'optimization'];
  readonly labels = {embedding: 'Embedding', github: 'GitHub', chat: 'Chat', optimization: 'Optimization'};
  currentStep: SetupStep = 'embedding';
  savedConfiguration: SetupConfiguration | null = null;
  private savedDrafts: Drafts | null = null;
  drafts: Drafts = {embedding: {}, github: {}, chat: {}, optimization: {}};
  status: SetupStatus | null = null;
  configVersion = '';
  validity = {embedding: false, github: false, chat: false, optimization: false};
  revisions = {embedding: 0, github: 0, chat: 0, optimization: 0};
  receipts: Partial<Record<SetupStep, Receipt>> = {};
  private blockedTests = {embedding: false, github: false, chat: false, optimization: false};
  loading = false;
  testing = false;
  saving = false;
  completing = false;
  finished = false;
  conflict = false;
  error: string | null = null;
  optimizationSaved = false;
  serverTypes: string[] = [];
  private requestId = 0;
  private destroyed = false;
  private subscriptions = new Subscription();
  @ViewChild('stepHeading') private stepHeading?: ElementRef<HTMLElement>;
  private focusedStep: SetupStep | null = null;
  constructor(private setup: SetupService, private route: ActivatedRoute, private router: Router,
    private globals: GlobalsService) {}
  get busy(): boolean { return this.loading || this.testing || this.saving || this.completing; }
  get dirty(): boolean { return !!this.savedDrafts && !equal(this.drafts, this.savedDrafts); }
  get retestRequired(): boolean {
    return !!this.savedDrafts && (this.drafts.embedding.timeout !== this.savedDrafts.embedding.timeout ||
      this.drafts.embedding.maxSegmentsPerBatch !== this.savedDrafts.embedding.maxSegmentsPerBatch);
  }
  get measuredDimension(): number | null {
    if (this.testValid('embedding')) return this.receipts.embedding?.result.dimensions ??
      this.savedDrafts?.optimization.vectorDimension ?? this.savedDrafts?.embedding.dimensions ?? null;
    return null;
  }
  ngOnInit(): void {
    this.reload();
    this.subscriptions.add(this.route.queryParamMap.pipe(skip(1)).subscribe(() => this.reload()));
    this.subscriptions.add(this.globals.getServerTypes().subscribe({
      next: types => this.serverTypes = types, error: () => this.serverTypes = []
    }));
  }
  reload(): void {
    if (this.busy || this.dirty && !window.confirm('Ungespeicherte Eingaben verwerfen und gespeicherten Stand laden?')) return;
    this.loading = true; this.error = null;
    const id = ++this.requestId;
    this.subscriptions.add(this.setup.getConfiguration().subscribe({
      next: cfg => {
        if (this.destroyed || id !== this.requestId) return;
        this.savedConfiguration = cfg;
        this.status = cfg.status; this.configVersion = cfg.status.configVersion;
        this.drafts = {
          embedding: {...cfg.embedding, type: cfg.embedding?.type ?? 'OLLAMA',
            timeout: cfg.embedding?.timeout ?? 20, maxSegmentsPerBatch: cfg.embedding?.maxSegmentsPerBatch ?? 32},
          github: {...cfg.github, tokenKeyEnv: cfg.github?.tokenKeyEnv ?? 'PRJXP_HUB_GITHUB_TOKEN_KEY'},
          chat: {...cfg.chat, stereoType: 'default', serverType: cfg.chat?.serverType ?? 'ollama',
            timeoutSecs: cfg.chat?.timeoutSecs ?? 60, temperature: cfg.chat?.temperature ?? 0.7},
          optimization: {...cfg.optimization, maxSegmentsPerBatch: cfg.embedding?.maxSegmentsPerBatch ?? 32,
            embeddingTimeout: cfg.embedding?.timeout ?? 20}
        };
        this.savedDrafts = structuredClone(this.drafts);
        this.receipts = {}; this.blockedTests = {embedding: false, github: false, chat: false, optimization: false};
        this.conflict = false; this.optimizationSaved = false; this.finished = false;
        this.currentStep = this.route.snapshot.queryParamMap.get('rerun') === '1' ? 'embedding' : cfg.status.resumeStep;
        this.loading = false;
      },
      error: error => { this.loading = false; this.fail(error); }
    }));
  }
  testValid(step: 'embedding' | 'chat'): boolean {
    const receipt = this.receipts[step];
    if (receipt) return receipt.revision === this.revisions[step] && this.plausible(step, receipt.result);
    return !this.blockedTests[step] && !!this.savedDrafts && equal(this.drafts[step], this.savedDrafts[step]) &&
      !!this.status?.[step].configured && !!this.status?.[step].tested;
  }
  result(step: SetupStep): SetupTestResult | null { return this.receipts[step]?.result ?? null; }
  private plausible(step: SetupStep, r: SetupTestResult): boolean {
    return r.success && (step === 'github' || !!r.testId) &&
      (step !== 'embedding' || r.dimensions != null && Number.isInteger(r.dimensions) && r.dimensions > 0 &&
        (this.drafts.embedding.dimensions == null || this.drafts.embedding.dimensions === r.dimensions)) &&
      (step !== 'chat' || !!r.response?.trim());
  }
  private changed<K extends SetupStep>(step: K, draft: Drafts[K]): void {
    if (equal(this.drafts[step], draft)) return;
    this.drafts[step] = draft; this.revisions[step]++;
    delete this.receipts[step]; this.optimizationSaved = false;
  }
  changeEmbedding(draft: EmbeddingConfigRequest): void {
    if (equal(this.drafts.embedding, draft)) return;
    this.changed('embedding', draft);
    this.changed('optimization', {...this.drafts.optimization, maxSegmentsPerBatch: draft.maxSegmentsPerBatch,
      embeddingTimeout: draft.timeout, vectorDimension: null});
  }
  changeGithub(draft: GithubConfigRequest): void { this.changed('github', draft); }
  changeChat(draft: ChatModelRequest): void { this.changed('chat', draft); }
  changeOptimization(draft: OptimizationRequest): void {
    this.changed('optimization', draft);
    this.changed('embedding', {...this.drafts.embedding, maxSegmentsPerBatch: draft.maxSegmentsPerBatch,
      timeout: draft.embeddingTimeout});
  }
  test(step: 'embedding' | 'github' | 'chat'): void {
    if (!this.ready() || !this.validity[step] || step === 'github' &&
      (!this.savedConfiguration?.githubEnabled || !this.savedConfiguration.githubKeyAvailable || !this.drafts.github.defaultPat)) return;
    this.testing = true; this.error = null;
    this.blockedTests[step] = equal(this.drafts[step], this.savedDrafts?.[step]);
    delete this.receipts[step];
    const revision = this.revisions[step], id = ++this.requestId;
    const request = step === 'embedding' ? this.setup.testEmbedding({configVersion: this.configVersion, configuration: {...this.drafts.embedding}}) :
      step === 'chat' ? this.setup.testChat({configVersion: this.configVersion, configuration: {...this.drafts.chat}}) :
        this.setup.testGithub({configVersion: this.configVersion, configuration: {...this.drafts.github}});
    this.subscriptions.add(request.subscribe({
      next: result => {
        if (this.destroyed || id !== this.requestId) return;
        this.configVersion = result.configVersion; this.testing = false;
        if (revision !== this.revisions[step]) return;
        this.receipts[step] = {revision, result};
        if (!this.plausible(step, result)) this.error = 'Test fehlgeschlagen oder Ergebnis nicht plausibel. Erneut testen.';
      },
      error: error => { if (id === this.requestId) {this.testing = false; this.fail(error);} }
    }));
  }
  private ready(): boolean { return !!this.savedConfiguration && !!this.configVersion && !this.busy && !this.conflict && !this.finished; }
  canProceed(): boolean {
    if (!this.ready() || !this.validity[this.currentStep]) return false;
    if (this.currentStep === 'embedding' || this.currentStep === 'chat') return this.testValid(this.currentStep);
    return this.currentStep === 'github' && !!this.savedConfiguration?.githubEnabled &&
      (!this.drafts.github.defaultPat || !!this.savedConfiguration.githubKeyAvailable);
  }
  next(): void {
    if (!this.canProceed() || this.currentStep === 'optimization') return;
    const step = this.currentStep;
    const revision = this.revisions[step], snapshot = structuredClone(this.drafts), dimension = this.measuredDimension;
    this.saving = true; this.error = null;
    this.subscriptions.add(this.setup.save(this.saveRequest(step)).subscribe({
      next: status => {
        this.acceptSave(step, status, snapshot, dimension); this.saving = false;
        if (revision === this.revisions[step]) this.currentStep = this.steps[this.steps.indexOf(step) + 1];
        else this.error = 'Anfragestand gespeichert. Neuere lokale Eingaben sind noch ungespeichert.';
      },
      error: error => { this.saving = false; this.fail(error); }
    }));
  }
  back(): void {
    if (!this.ready() || this.currentStep === 'embedding') return;
    this.currentStep = this.steps[this.steps.indexOf(this.currentStep) - 1];
  }
  skip(): void {
    if (!this.ready() || this.currentStep !== 'github') return;
    if (!this.confirmOptional('github')) return;
    const revision = this.revisions.github, snapshot = structuredClone(this.drafts);
    snapshot.github = {...this.savedDrafts!.github};
    this.saving = true; this.error = null;
    this.subscriptions.add(this.setup.save({configVersion: this.configVersion, step: 'github', skip: true}).subscribe({
      next: status => {
        if (revision === this.revisions.github) this.drafts.github = {...snapshot.github};
        this.acceptSave('github', status, snapshot); this.saving = false;
        if (revision === this.revisions.github) this.currentStep = 'chat';
        else this.error = 'Schritt übersprungen. Neuere lokale Eingaben sind noch ungespeichert.';
      },
      error: error => {this.saving = false; this.fail(error);}
    }));
  }
  private confirmOptional(step: 'github' | 'optimization'): boolean {
    return equal(this.drafts[step], this.savedDrafts?.[step]) ||
      window.confirm('Ungespeicherte optionale Eingaben verwerfen? Gespeicherte Werte bleiben erhalten.');
  }
  canFinish(skip = false): boolean {
    const saved = this.savedDrafts;
    const coreUnchanged = !!saved && equal({...this.drafts.embedding, timeout: saved.embedding.timeout,
      maxSegmentsPerBatch: saved.embedding.maxSegmentsPerBatch}, saved.embedding);
    const storedEmbeddingVerified = !!saved && (!this.blockedTests.embedding ||
      equal(this.drafts.embedding, saved.embedding) && this.testValid('embedding'));
    return this.ready() && this.currentStep === 'optimization' && this.testValid('chat') &&
      !!saved && equal(this.drafts.chat, saved.chat) && !!this.status?.chat.tested &&
      (skip ? storedEmbeddingVerified && !!this.status?.embedding.configured && !!this.status.embedding.tested :
        coreUnchanged && this.validity.optimization && (this.testValid('embedding') || this.retestRequired));
  }
  finish(skip = false): void {
    if (!this.canFinish(skip)) return;
    if (skip && !this.confirmOptional('optimization')) return;
    if (skip) {
      this.drafts.optimization = {...this.savedDrafts!.optimization};
      this.drafts.embedding = {...this.savedDrafts!.embedding};
      this.blockedTests.embedding = false;
      delete this.receipts.embedding;
    }
    this.completing = true; this.error = null;
    const id = ++this.requestId, revision = this.revisions.embedding;
    const sequenceRevisions = {...this.revisions}, snapshot = structuredClone(this.drafts);
    const unchanged = () => this.steps.every(step => this.revisions[step] === sequenceRevisions[step]);
    const candidate = {...snapshot.embedding};
    const test$: Observable<SetupTestResult | null> = !skip && this.retestRequired && !this.testValid('embedding') ?
      this.setup.testEmbedding({configVersion: this.configVersion, configuration: candidate}) : of(null);
    if (!skip && this.retestRequired && !this.testValid('embedding')) delete this.receipts.embedding;
    this.subscriptions.add(test$.pipe(
      concatMap(result => {
        if (id !== this.requestId || !unchanged()) return throwError(() => ({}));
        if (result) {
          this.configVersion = result.configVersion;
          this.receipts.embedding = {revision, result};
          if (!this.plausible('embedding', result)) return throwError(() => ({error: {code: 'TEST_FAILED'}}));
        }
        if (this.optimizationSaved) return of(this.status!);
        const dimension = this.measuredDimension;
        if (!skip && (dimension == null || snapshot.optimization.vectorDimension != null &&
          snapshot.optimization.vectorDimension !== dimension)) return throwError(() => ({error: {code: 'INVALID_REQUEST'}}));
        const req: SetupSaveRequest = skip ? {configVersion: this.configVersion, step: 'optimization', skip: true} :
          {configVersion: this.configVersion, step: 'optimization', skip: false,
            testId: this.receipts.embedding?.result.testId, optimization: {...snapshot.optimization, vectorDimension: dimension}};
        return this.setup.save(req).pipe(concatMap(status => {
          if (!skip) {
            snapshot.optimization = {...snapshot.optimization, vectorDimension: dimension};
            if (unchanged()) this.drafts.optimization = {...snapshot.optimization};
          }
          this.acceptSave('optimization', status, snapshot);
          if (!skip) this.savedDrafts!.embedding = {...candidate};
          this.blockedTests.embedding = !status.embedding.tested;
          delete this.receipts.embedding;
          this.optimizationSaved = unchanged();
          if (!unchanged()) return throwError(() => ({}));
          return of(status);
        }));
      }),
      concatMap(status => this.setup.complete({configVersion: status.configVersion, defer: false}))
    ).subscribe({
      next: status => {
        if (this.destroyed || id !== this.requestId) return;
        this.status = status; this.configVersion = status.configVersion;
        this.completing = false; this.finished = unchanged() && status.completed && status.completionMode === 'VERIFIED';
        if (this.finished) this.savedDrafts = structuredClone(snapshot);
        else this.error = 'Abschluss nicht verifiziert. Gespeicherte Schritte bleiben erhalten.';
      },
      error: error => {this.completing = false; this.fail(error);}
    }));
  }
  private saveRequest(step: SetupStep): SetupSaveRequest {
    const base = {configVersion: this.configVersion, skip: false as const, testId: this.receipts[step]?.result.testId};
    switch (step) {
      case 'embedding': return {...base, step, embedding: {...this.drafts.embedding}};
      case 'chat': return {...base, step, chat: {...this.drafts.chat}};
      case 'github': return {...base, step, github: {...this.drafts.github}};
      case 'optimization': return {...base, step, optimization: {...this.drafts.optimization}};
    }
  }
  private acceptSave(step: SetupStep, status: SetupStatus, snapshot = structuredClone(this.drafts), dimension = this.measuredDimension): void {
    this.status = status; this.configVersion = status.configVersion;
    if (this.savedDrafts) {
      this.savedDrafts[step] = structuredClone(snapshot[step]);
      if (step === 'embedding') {
        const measured = dimension ?? snapshot.embedding.dimensions ?? this.savedDrafts.optimization.vectorDimension;
        if (equal(this.drafts.embedding, snapshot.embedding))
          this.drafts.optimization = {...this.drafts.optimization, vectorDimension: measured};
        this.savedDrafts.optimization = {...this.savedDrafts.optimization, vectorDimension: measured,
          maxSegmentsPerBatch: snapshot.embedding.maxSegmentsPerBatch, embeddingTimeout: snapshot.embedding.timeout};
      }
    }
    this.blockedTests[step] = !status[step].tested;
    delete this.receipts[step];
    this.savedConfiguration = {...this.savedConfiguration!, status};
  }
  bypass(): void {
    if (!this.ready() || !window.confirm('Später konfigurieren? Gespeicherte Schritte bleiben erhalten; ungespeicherte Eingaben werden verworfen. Die Konfiguration kann unvollständig sein.')) return;
    this.completing = true; this.error = null;
    this.subscriptions.add(this.setup.complete({configVersion: this.configVersion, defer: true}).subscribe({
      next: status => {
        this.status = status; this.configVersion = status.configVersion; this.completing = false;
        if (!status.completed || status.completionMode !== 'DEFERRED') {this.error = 'Aufschub nicht bestätigt.'; return;}
        this.drafts = structuredClone(this.savedDrafts!);
        this.receipts = {}; this.finished = true;
        void this.router.navigate(['/dashboard']);
      },
      error: error => {this.completing = false; this.fail(error);}
    }));
  }
  checkStatus(): void {
    if (this.busy) return;
    this.loading = true; this.error = null;
    this.subscriptions.add(this.setup.getStatus().subscribe({
      next: status => {this.status = status; this.configVersion = status.configVersion; this.loading = false;},
      error: error => {this.loading = false; this.fail(error);}
    }));
  }
  private fail(error: unknown): void {
    this.error = setupError(error);
    this.conflict = this.error.startsWith('CONFIG_CHANGED') || this.error.startsWith('CONFIG_OVERRIDDEN');
  }
  canDeactivate(): boolean {
    return !this.busy && (!this.dirty || this.finished || window.confirm('Ungespeicherte Setup-Eingaben verwerfen und Seite verlassen?'));
  }
  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(event: Event): void {
    if (this.dirty && !this.finished || this.busy) event.preventDefault();
  }
  ngAfterViewChecked(): void {
    if (this.stepHeading && this.focusedStep !== this.currentStep) {
      this.focusedStep = this.currentStep; this.stepHeading.nativeElement.focus();
    }
  }
  ngOnDestroy(): void { this.destroyed = true; this.requestId++; this.subscriptions.unsubscribe(); }
}
