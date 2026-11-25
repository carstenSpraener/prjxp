import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { SetupService } from '../../services/setup.service';
import { SetupStatusBannerComponent } from './setup-status-banner.component';
import { setupConfiguration } from '../../features/setup/setup-fixtures';

describe('Setup banner', () => {
  it('warns only completed invalid required steps, dismisses only one version and shows restart/error separately', () => {
    const status = signal(setupConfiguration().status);
    const error = signal<string | null>(null);
    const getStatus = vi.fn(() => of(status()));
    TestBed.configureTestingModule({imports: [SetupStatusBannerComponent], providers: [provideRouter([]),
      {provide: SetupService, useValue: {status, error, loading: signal(false), getStatus}}]});
    const fixture = TestBed.createComponent(SetupStatusBannerComponent);
    const c = fixture.componentInstance;
    fixture.detectChanges();
    expect(c.warning()).toBe(false);
    status.set({...status(), completed: true, embedding: {...status().embedding, tested: false, problemCode: 'NOT_TESTED'}});
    fixture.detectChanges();
    expect(c.warning()).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('NOT_TESTED');
    c.dismiss();
    expect(c.warning()).toBe(false);
    status.set({...status(), configVersion: 'v2', restartRequired: true});
    expect(c.warning()).toBe(true);
    error.set('Offline');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Offline');
    c.retry();
    expect(getStatus).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.querySelector('a').getAttribute('href')).toContain('rerun=1');
    fixture.destroy();
  });
});
