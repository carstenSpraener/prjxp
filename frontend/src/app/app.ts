import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * Root outlet only. The application chrome lives in ShellComponent and is part of the
 * shell route, so routes like /setup can render without the top bar and sidebar.
 *
 * Must stay {@link ChangeDetectionStrategy.Eager}: every routed component is refreshed by the
 * zone-triggered pass that starts here. With the v22 default (OnPush) this root is skipped and
 * routed views never re-render after async work (e.g. the wizard after loading its config).
 */
@Component({
  selector: 'app-root',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.Eager,
  imports: [RouterOutlet],
  template: '<router-outlet></router-outlet>'
})
export class AppComponent {}
