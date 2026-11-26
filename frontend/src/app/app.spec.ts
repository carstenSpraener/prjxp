import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app';

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
    expect(fixture.componentInstance).toBeDefined();
  });

  it('should have router outlet', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('router-outlet')).toBeDefined();
  });

  it('should not render application chrome itself (it lives in the shell route)', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.top-bar')).toBeNull();
    expect(compiled.querySelector('nav-sidebar')).toBeNull();
    expect(compiled.querySelector('.title')).toBeNull();
  });

  it('keeps Eager change detection — the zoned pass starts at this root view', () => {
    // With the Angular 22 default the traversal stops here (onPush) and routed views never
    // re-render after async work (the wizard hung on "Konfiguration wird geladen...").
    const def = (AppComponent as unknown as { ɵcmp: { onPush: boolean } }).ɵcmp;
    expect(def.onPush).toBe(false);
  });
});
