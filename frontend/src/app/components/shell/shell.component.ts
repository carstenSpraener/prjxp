import { Component, OnDestroy, OnInit, ChangeDetectionStrategy, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Subscription } from 'rxjs';
import { NavSidebarComponent } from '../nav-sidebar/nav-sidebar.component';
import { PrjxpApiService } from '../../services/prjxp-api.service';
import { SetupService } from '../../services/setup.service';

/**
 * Application chrome (top bar + sidebar + content area) for every route except the setup wizard,
 * which renders standalone so it cannot be left through the navigation.
 *
 * Also owns the connection heartbeat and the "setup incomplete -> back to the wizard" redirect,
 * because both only matter while the shell is visible.
 */
@Component({
  selector: 'app-shell',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.Eager,
  imports: [RouterLink, RouterLinkActive, RouterOutlet, NavSidebarComponent],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.css'
})
export class ShellComponent implements OnInit, OnDestroy {
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
