import { Routes } from '@angular/router';
import { ImportComponent } from './features/import/import.component';
import { DashboardComponent } from './features/dashboard/dashboard.component';
import { ProjectsComponent } from './features/projects/projects.component';
import { GlobalsComponent } from './features/globals/globals.component';
import { ChatComponent } from './features/chat/chat.component';

export const routes: Routes = [
  { path: 'dashboard', component: DashboardComponent },
  { path: 'import', component: ImportComponent },
  { path: 'projects', component: ProjectsComponent },
  { path: 'globals', component: GlobalsComponent },
  { path: 'chat', component: ChatComponent },
  { path: '', redirectTo: '/dashboard', pathMatch: 'full' },
  { path: '**', redirectTo: '/dashboard' }
];
