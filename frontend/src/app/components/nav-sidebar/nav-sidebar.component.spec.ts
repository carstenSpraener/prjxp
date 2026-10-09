import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, Routes } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { NavSidebarComponent, NAV_ITEMS } from './nav-sidebar.component';
import { Component } from '@angular/core';

// Dummy component for route testing
@Component({
  selector: 'dummy-target',
  standalone: true,
  template: '<div>Target</div>'
})
class DummyTargetComponent {}

const TEST_ROUTES: Routes = NAV_ITEMS.map(item => ({
  path: item.route.substring(1), // Remove leading '/'
  component: DummyTargetComponent
}));

describe('NavSidebarComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [NavSidebarComponent, DummyTargetComponent],
      providers: [
        provideRouter(TEST_ROUTES),
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    }).compileComponents();
  });

  it('should create', () => {
    const fixture = TestBed.createComponent(NavSidebarComponent);
    expect(fixture.componentInstance).toBeDefined();
  });

  it('should render all nav items from NAV_ITEMS', () => {
    const fixture = TestBed.createComponent(NavSidebarComponent);
    fixture.detectChanges();
    const navItems = fixture.nativeElement.querySelectorAll('.nav-item');
    expect(navItems.length).toBe(NAV_ITEMS.length);
  });

  it('should show icon-only mode when collapsed=true', () => {
    const fixture = TestBed.createComponent(NavSidebarComponent);
    fixture.componentInstance.collapsed = true;
    fixture.detectChanges();
    const labels = fixture.nativeElement.querySelectorAll('.label');
    expect(labels.length).toBe(0);
  });

  it('should show labels when collapsed=false', () => {
    const fixture = TestBed.createComponent(NavSidebarComponent);
    fixture.componentInstance.collapsed = false;
    fixture.detectChanges();
    const labels = fixture.nativeElement.querySelectorAll('.label');
    expect(labels.length).toBe(NAV_ITEMS.length);
  });

  it('should have correct nav item icons', () => {
    const fixture = TestBed.createComponent(NavSidebarComponent);
    fixture.detectChanges();
    const icons = fixture.nativeElement.querySelectorAll('.icon');
    expect(icons[0].textContent).toBe('📊');
    expect(icons[1].textContent).toBe('📥');
    expect(icons[2].textContent).toBe('📁');
  });

  it('isActive() should compare router.url correctly', () => {
    const fixture = TestBed.createComponent(NavSidebarComponent);
    const component = fixture.componentInstance;
    const router = TestBed.inject(Router);

    // Initially router.url is '/' (default root)
    expect(component.isActive('/')).toBe(true);
    expect(component.isActive('/dashboard')).toBe(false);
  });

  it('navItems should match NAV_ITEMS constant', () => {
    const fixture = TestBed.createComponent(NavSidebarComponent);
    const component = fixture.componentInstance;

    expect(component.navItems).toEqual(NAV_ITEMS);
  });
});
