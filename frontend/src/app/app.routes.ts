import { Routes } from '@angular/router';
import { ImportComponent } from './features/import/import.component';
import { DashboardComponent } from './features/dashboard/dashboard.component';
import { ProjectsComponent } from './features/projects/projects.component';
import { GlobalsComponent } from './features/globals/globals.component';
import { ChatComponent } from './features/chat/chat.component';
import { DocsComponent } from './features/docs/docs.component';
import { SetupWizardComponent, setupCanDeactivate } from './features/setup/setup-wizard.component';
import { setupIncompleteGuard } from './features/setup/setup-incomplete.guard';
import { ShellComponent } from './components/shell/shell.component';

export const routes: Routes = [
  // The wizard renders without the shell (no top bar, no sidebar) so it cannot be left
  // through the navigation; reaching any shell route while setup is incomplete redirects back.
  { path: 'setup', component: SetupWizardComponent, canDeactivate: [setupCanDeactivate] },
  {
    path: '', component: ShellComponent, canActivate: [setupIncompleteGuard],
    children: [
      { path: 'dashboard', component: DashboardComponent },
      { path: 'import', component: ImportComponent },
      { path: 'projects', component: ProjectsComponent },
      { path: 'globals', component: GlobalsComponent },
      { path: 'chat', component: ChatComponent },
      { path: 'docs', component: DocsComponent },
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' }
    ]
  },
  { path: '**', redirectTo: 'dashboard' }
];
