import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * Root outlet only. The application chrome lives in ShellComponent and is part of the
 * shell route, so routes like /setup can render without the top bar and sidebar.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet],
  template: '<router-outlet></router-outlet>'
})
export class AppComponent {}
