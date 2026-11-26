import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { SetupService } from '../../services/setup.service';

/**
 * Keeps the user inside the setup wizard until setup has been completed.
 *
 * "Completed" is deliberately {@code completed}, not {@code completionMode === 'VERIFIED'}:
 * "Später konfigurieren" completes the wizard in DEFERRED mode and must land on the dashboard.
 * The already known status (published by the app shell / wizard) is used when available so
 * ordinary navigation does not trigger an extra status request.
 */
export const setupIncompleteGuard: CanActivateFn = () => {
  const setup = inject(SetupService);
  const router = inject(Router);

  const known = setup.status();
  if (known) {
    return known.completed ? true : router.createUrlTree(['/setup']);
  }
  return setup.getStatus().pipe(
    map(status => status.completed ? true : router.createUrlTree(['/setup'])),
    // Never brick the UI when the status endpoint is unavailable (backend starting up).
    catchError(() => of(true))
  );
};
