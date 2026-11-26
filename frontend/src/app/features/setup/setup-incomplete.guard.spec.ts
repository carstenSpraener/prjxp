import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { GuardResult, Router, UrlTree, provideRouter } from '@angular/router';
import { Observable, firstValueFrom, of, throwError } from 'rxjs';
import { signal } from '@angular/core';
import { setupIncompleteGuard } from './setup-incomplete.guard';
import { SetupService } from '../../services/setup.service';
import { setupConfiguration } from './setup-fixtures';

describe('setupIncompleteGuard', () => {
  function configure(service: unknown) {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
        { provide: SetupService, useValue: service }]
    });
    return TestBed.inject(Router);
  }

  async function evaluate(): Promise<GuardResult> {
    const result = TestBed.runInInjectionContext(() => setupIncompleteGuard({} as never, {} as never));
    return result instanceof Observable ? firstValueFrom(result) : result;
  }

  it('allows navigation once setup is completed', async () => {
    configure({ status: signal({ ...setupConfiguration().status, completed: true }), getStatus: vi.fn() });
    expect(await evaluate()).toBe(true);
  });

  it('redirects to the wizard while setup is incomplete', async () => {
    configure({ status: signal(setupConfiguration().status), getStatus: vi.fn() });
    const result = await evaluate();
    expect(result).toBeInstanceOf(UrlTree);
    expect(String(result)).toBe('/setup');
  });

  it('fetches the status when none is known yet', async () => {
    const getStatus = vi.fn(() => of({ ...setupConfiguration().status, completed: true }));
    configure({ status: signal(null), getStatus });
    expect(await evaluate()).toBe(true);
    expect(getStatus).toHaveBeenCalled();
  });

  it('fails open when the status endpoint is unavailable', async () => {
    configure({ status: signal(null), getStatus: () => throwError(() => new Error('offline')) });
    expect(await evaluate()).toBe(true);
  });
});
