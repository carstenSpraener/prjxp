import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { AppComponent } from './app';
import { NavSidebarComponent } from './components/nav-sidebar/nav-sidebar.component';
import { PrjxpApiService } from './services/prjxp-api.service';
import { of, throwError } from 'rxjs';

describe('AppComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([])
      ]
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app).toBeDefined();
  });

  it(`should have title 'PRJXP HUB'`, () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.title')?.textContent).toContain('PRJXP');
  });

  it('should have router outlet', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('router-outlet')).toBeDefined();
  });

  it('should have top bar with menu toggle', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.menu-toggle')).toBeDefined();
  });

  it('should have status indicator', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.status-indicator')).toBeDefined();
  });

  it('should render nav-sidebar component', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('nav-sidebar')).toBeDefined();
  });

  it('toggleSidebar() should flip sidebarCollapsed signal', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app.sidebarCollapsed()).toBe(false);

    app.toggleSidebar();
    expect(app.sidebarCollapsed()).toBe(true);

    app.toggleSidebar();
    expect(app.sidebarCollapsed()).toBe(false);
  });

  it('should render favicon image', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const img = fixture.nativeElement.querySelector('.logo img');
    expect(img).toBeDefined();
    expect(img.src).toContain('assets/favicon.svg');
  });

  it('isOnline signal should be true when ping succeeds', () => {
    const mockPing = vi.fn(() => of('pong'));
    const apiSpy = { ping: mockPing };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: PrjxpApiService, useValue: apiSpy }
      ]
    });

    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app.isOnline()).toBe(true);
  });

  it('isOnline signal should be false when ping fails', () => {
    const mockPing = vi.fn(() => throwError(() => new Error('Network error')));
    const apiSpy = { ping: mockPing };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: PrjxpApiService, useValue: apiSpy }
      ]
    });

    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app.isOnline()).toBe(false);
  });

  it('should pass collapsed signal to nav-sidebar', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    const navSidebarEl = fixture.nativeElement.querySelector('nav-sidebar');
    expect(navSidebarEl).toBeDefined();

    // Initially sidebarCollapsed signal is false
    expect(fixture.componentInstance.sidebarCollapsed()).toBe(false);

    // Toggle and check signal value
    fixture.componentInstance.toggleSidebar();
    fixture.detectChanges();
    expect(fixture.componentInstance.sidebarCollapsed()).toBe(true);

    // Toggle again
    fixture.componentInstance.toggleSidebar();
    fixture.detectChanges();
    expect(fixture.componentInstance.sidebarCollapsed()).toBe(false);
  });
});
