import { Component, signal, OnInit, OnDestroy } from '@angular/core';
import { RouterOutlet, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { PrjxpApiService } from './services/prjxp-api.service';
import { NavSidebarComponent } from './components/nav-sidebar/nav-sidebar.component';
import { SetupService } from './services/setup.service';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, NavSidebarComponent],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class AppComponent implements OnInit, OnDestroy {
  sidebarCollapsed = signal(false);
  isOnline = signal(false);

  private heartbeatInterval: ReturnType<typeof setInterval> | null = null;
  private subscriptions = new Subscription();

  constructor(
    private api: PrjxpApiService,
    private router: Router,
    private setup: SetupService
  ) {
    this.refreshSetup();
    this.checkConnection(true);
  }

  ngOnInit(): void {
    // Check connection every 5 seconds (heartbeat)
    this.heartbeatInterval = setInterval(() => {
      this.checkConnection();
    }, 5000);
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
    // Clean up interval on component destroy
    if (this.heartbeatInterval) {
      clearInterval(this.heartbeatInterval);
      this.heartbeatInterval = null;
    }
  }

  toggleSidebar(): void {
    this.sidebarCollapsed.update(v => !v);
  }

  private checkConnection(initial = false): void {
    this.subscriptions.add(this.api.ping().subscribe({
      next: () => {
        const reconnect = !this.isOnline() && !initial;
        this.isOnline.set(true);
        if (reconnect) this.refreshSetup();
      },
      error: () => this.isOnline.set(false)
    }));
  }
  private refreshSetup(): void {
    this.subscriptions.add(this.setup.getStatus().subscribe({
      next: status => {
        if (status.completed === false && this.setup.status()?.configVersion === status.configVersion &&
          this.setup.status()?.completed === false && !/^\/setup(?:[/?#]|$)/.test(this.router.url)) {
          void this.router.navigate(['/setup'], {replaceUrl: true});
        }
      }, error: () => {}
    }));
  }
}
