# Phase 02: Core Layout & Cyberpunk Theme

> **STRICT EXECUTION RULES FOR THE AGENT:**
> 1. DO NOT run repository scans.
> 2. Read ONLY the files explicitly listed in the "File Scope" section below.
> 3. All required interfaces/DTOs are provided inline in this document.
> 4. **MANDATORY FINAL STEP:** Execute `/compact` IMMEDIATELY after green tests (>= 80% coverage).

## 1. Target & Scope
* **Objective:** Polish the shell layout with full cyberpunk styling, animated navigation, and responsive design.
* **Target Files to Create/Modify:**
    * Modify: `frontend/src/app/app.component.ts` - Add sidebar toggle, active route highlighting
    * Modify: `frontend/src/app/app.component.html` - Full shell template with animations
    * Modify: `frontend/src/app/app.component.css` - Cyberpunk styles, neon effects, animations
    * Modify: `frontend/src/styles.css` - Enhanced global styles with animations
    * Create: `frontend/src/app/components/nav-sidebar/nav-sidebar.component.ts`
    * Create: `frontend/src/app/components/nav-sidebar/nav-sidebar.component.html`
    * Create: `frontend/src/app/components/nav-sidebar/nav-sidebar.component.css`
    * Create: `frontend/src/app/components/nav-sidebar/nav-sidebar.component.spec.ts`
    * Create: `frontend/src/assets/favicon.svg` - Simple prjxp logo

## 2. Inline Required Context

### NavItem Interface
```typescript
export interface NavItem {
  label: string;
  route: string;
  icon: string;
}

export const NAV_ITEMS: NavItem[] = [
  { label: 'Dashboard', route: '/dashboard', icon: '📊' },
  { label: 'Import', route: '/import', icon: '📥' },
  { label: 'Projects', route: '/projects', icon: '📁' }
];
```

### Shell Layout Structure (HTML)
```html
<div class="shell">
  <!-- Top Bar -->
  <header class="top-bar glass-panel">
    <button class="menu-toggle" (click)="toggleSidebar()">☰</button>
    <div class="logo">
      <img src="assets/favicon.svg" alt="prjxp" height="32">
      <span class="title">PRJXP HUB</span>
    </div>
    <div class="status-indicator">
      <span class="dot" [class.online]="isOnline"></span>
      {{ isOnline ? 'Connected' : 'Disconnected' }}
    </div>
  </header>

  <!-- Sidebar -->
  <nav-sidebar [collapsed]="sidebarCollapsed"></nav-sidebar>

  <!-- Main Content -->
  <main class="main-content" [class.sidebar-collapsed]="sidebarCollapsed">
    <router-outlet></router-outlet>
  </main>
</div>
```

### CSS Animations (Keyframes)
```css
/* Neon pulse animation */
@keyframes neonPulse {
  0%, 100% { box-shadow: 0 0 5px var(--accent-cyan), 0 0 10px rgba(0,245,255,0.3); }
  50% { box-shadow: 0 0 15px var(--accent-cyan), 0 0 30px rgba(0,245,255,0.6); }
}

/* Gradient border animation */
@keyframes gradientBorder {
  0% { background-position: 0% 50%; }
  50% { background-position: 100% 50%; }
  100% { background-position: 0% 50%; }
}

/* Fade in animation */
@keyframes fadeIn {
  from { opacity: 0; transform: translateY(-10px); }
  to { opacity: 1; transform: translateY(0); }
}

/* Scanline effect */
@keyframes scanline {
  0% { transform: translateY(-100%); }
  100% { transform: translateY(100vh); }
}
```

## 3. Implementation Details

### 3.1 NavSidebar Component
Extracted sidebar component with:
- Active route highlighting (neon glow on active item)
- Hover effects with smooth transitions
- Collapsible state (icon-only mode when collapsed)

```typescript
@Component({
  selector: 'nav-sidebar',
  standalone: true,
  imports: [RouterLink, RouterLinkActive],
  templateUrl: './nav-sidebar.component.html',
  styleUrls: ['./nav-sidebar.component.css']
})
export class NavSidebarComponent {
  @Input() collapsed = false;
  navItems = NAV_ITEMS;

  constructor(private router: Router) {}

  isActive(route: string): boolean {
    return this.router.url === route;
  }
}
```

### 3.2 AppComponent Enhancements
- **Sidebar toggle**: Boolean signal `sidebarCollapsed`
- **Online status**: Ping mcp-server on init, update status indicator
- **Active route highlighting**: CSS class on active nav item

```typescript
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, NavSidebarComponent],
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent {
  sidebarCollapsed = signal(false);
  isOnline = signal(false);

  constructor(
    private api: PrjxpApiService,
    private router: Router
  ) {
    this.checkConnection();
  }

  toggleSidebar(): void {
    this.sidebarCollapsed.update(v => !v);
  }

  private checkConnection(): void {
    this.api.ping().subscribe({
      next: () => this.isOnline.set(true),
      error: () => this.isOnline.set(false)
    });
  }
}
```

### 3.3 Favicon (SVG)
Simple geometric logo:
```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
  <rect width="64" height="64" rx="12" fill="#0a0e17"/>
  <text x="32" y="44" text-anchor="middle" fill="#00f5ff"
        font-family="JetBrains Mono, monospace" font-size="28" font-weight="bold">
    px
  </text>
</svg>
```

## 4. Tests (TDD)

### NavSidebarComponent Tests
- Should render all nav items from NAV_ITEMS
- Should highlight active route with neon glow class
- Should show icon-only mode when collapsed=true
- Click on nav item should navigate to route

### AppComponent Tests (extended)
- toggleSidebar() should flip sidebarCollapsed signal
- checkConnection() should set isOnline based on ping result
- Shell should have top-bar, sidebar, and main-content areas

## 5. Acceptance Criteria
- [ ] Sidebar toggles between full and collapsed (icon-only) modes
- [ ] Active nav item has neon glow effect
- [ ] Online status indicator shows connection state to mcp-server
- [ ] All CSS animations work (neon pulse, fade in)
- [ ] Responsive: sidebar collapses on mobile, hamburger menu visible
- [ ] All tests pass with >= 80% coverage
