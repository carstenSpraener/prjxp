import { Component, computed, OnDestroy, OnInit, signal, HostListener } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { SetupService } from '../../services/setup.service';
@Component({selector: 'setup-status-banner', standalone: true, imports: [RouterLink], template: `
  <a routerLink="/setup" [queryParams]="{rerun: 1}">Setup erneut ausführen</a>
  @if(setup.error()) { <p role="alert">Setup-Status unbekannt: {{ setup.error() }}</p><button type="button" [disabled]="setup.loading()" (click)="retry()">Status erneut prüfen</button> }
  @if(warning()) {
    <aside role="status">
      <strong>Setup unvollständig / Nachweise ungültig</strong>
      @for(step of requiredSteps; track step) {
        @if(!setup.status()?.[step]?.configured || !setup.status()?.[step]?.tested) {
          <p>{{ step }}: {{ setup.status()?.[step]?.problemCode }} — {{ setup.status()?.[step]?.message }}</p>
        }
      }
      <button type="button" aria-label="Setup-Warnung für diese Version schließen" (click)="dismiss()">Schließen</button>
    </aside>
  }
  @if(setup.status()?.restartRequired) { <p role="status">Gespeichert, noch nicht aktiv: Backend neu starten.</p> }
  `, styles: [`aside {border: 1px solid #ef729b; padding: 1rem; margin: 1rem 0;} a {display: inline-block; margin-bottom: 1rem;}`]})
export class SetupStatusBannerComponent implements OnInit, OnDestroy {
  readonly requiredSteps = ['embedding', 'chat'] as const;
  private dismissedVersion = signal<string | null>(null);
  readonly warning = computed(() => {
    const status = this.setup.status();
    return !!status?.completed && status.configVersion !== this.dismissedVersion() &&
      this.requiredSteps.some(step => !status[step].configured || !status[step].tested);
  });
  private subscriptions = new Subscription();
  constructor(public setup: SetupService) {}
  ngOnInit(): void { this.retry(); }
  @HostListener('window:online')
  retry(): void { this.subscriptions.add(this.setup.getStatus().subscribe({error: () => {}})); }
  dismiss(): void { this.dismissedVersion.set(this.setup.status()?.configVersion ?? null); }
  ngOnDestroy(): void { this.subscriptions.unsubscribe(); }
}
