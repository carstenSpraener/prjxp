import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { signal } from '@angular/core';
import { ShellComponent } from './shell.component';
import { PrjxpApiService } from '../../services/prjxp-api.service';
import { SetupService } from '../../services/setup.service';
import { setupConfiguration } from '../../features/setup/setup-fixtures';

describe('ShellComponent', () => {
  function configure(api: { ping: ReturnType<typeof vi.fn> }, status = setupConfiguration().status) {
    const statusSignal = signal(status);
    TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: PrjxpApiService, useValue: api },
        { provide: SetupService, useValue: { status: statusSignal, getStatus: () => of(status) } }
      ]
    });
    return TestBed.createComponent(ShellComponent);
  }

  it('renders the top bar with title, menu toggle, status indicator and the sidebar', () => {
    const fixture = configure({ ping: vi.fn(() => of('pong')) });
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.top-bar')).not.toBeNull();
    expect(el.querySelector('.title')?.textContent).toContain('PRJXP');
    expect(el.querySelector('.menu-toggle')).not.toBeNull();
    expect(el.querySelector('.status-indicator')).not.toBeNull();
    expect(el.querySelector('nav-sidebar')).not.toBeNull();
    expect(el.querySelector('.logo img')?.getAttribute('src')).toContain('assets/favicon.svg');
    expect(el.querySelector('router-outlet')).not.toBeNull();
  });

  it('toggleSidebar() flips the collapsed signal', () => {
    const fixture = configure({ ping: vi.fn(() => of('pong')) });
    const shell = fixture.componentInstance;

    expect(shell.sidebarCollapsed()).toBe(false);
    shell.toggleSidebar();
    expect(shell.sidebarCollapsed()).toBe(true);
    shell.toggleSidebar();
    expect(shell.sidebarCollapsed()).toBe(false);
  });

  it('passes the collapsed signal to nav-sidebar', () => {
    const fixture = configure({ ping: vi.fn(() => of('pong')) });
    fixture.detectChanges();

    fixture.componentInstance.toggleSidebar();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('nav-sidebar')).not.toBeNull();
  });

  it('isOnline is true when ping succeeds', () => {
    const fixture = configure({ ping: vi.fn(() => of('pong')) });
    expect(fixture.componentInstance.isOnline()).toBe(true);
  });

  it('isOnline is false when ping fails', () => {
    const fixture = configure({ ping: vi.fn(() => throwError(() => new Error('offline'))) });
    expect(fixture.componentInstance.isOnline()).toBe(false);
  });
});
