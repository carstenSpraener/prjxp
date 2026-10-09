import { Component, Input } from '@angular/core';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';

export interface NavItem {
  label: string;
  route: string;
  icon: string;
}

export const NAV_ITEMS: NavItem[] = [
  { label: 'Dashboard', route: '/dashboard', icon: 'icon-dashboard.svg' },
  { label: 'Import', route: '/import', icon: 'icon-import.svg' },
  { label: 'Projects', route: '/projects', icon: 'icon-projects.svg' },
  { label: 'Globals', route: '/globals', icon: 'icon-globals.svg' },
  { label: 'Chat', route: '/chat', icon: 'icon-chat.svg' }
];

@Component({
  selector: 'nav-sidebar',
  standalone: true,
  imports: [RouterLink, RouterLinkActive],
  templateUrl: './nav-sidebar.component.html',
  styleUrl: './nav-sidebar.component.css'
})
export class NavSidebarComponent {
  @Input() collapsed = false;
  navItems = NAV_ITEMS;

  constructor(private router: Router) {}

  isActive(route: string): boolean {
    return this.router.url === route;
  }
}
