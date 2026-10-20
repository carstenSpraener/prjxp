import { Component, signal, OnInit, OnDestroy } from '@angular/core';
import { RouterOutlet, Router } from '@angular/router';
import { PrjxpApiService } from './services/prjxp-api.service';
import { NavSidebarComponent } from './components/nav-sidebar/nav-sidebar.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, NavSidebarComponent],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class AppComponent implements OnInit, OnDestroy {
  sidebarCollapsed = signal(false);
  isOnline = signal(false);

  private heartbeatInterval: any = null;

  constructor(
    private api: PrjxpApiService,
    private router: Router
  ) {
    this.checkConnection();
  }

  ngOnInit(): void {
    // Check connection every 5 seconds (heartbeat)
    this.heartbeatInterval = setInterval(() => {
      this.checkConnection();
    }, 5000);
  }

  ngOnDestroy(): void {
    // Clean up interval on component destroy
    if (this.heartbeatInterval) {
      clearInterval(this.heartbeatInterval);
      this.heartbeatInterval = null;
    }
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
