# PrjxpFrontend

This project was generated using [Angular CLI](https://github.com/angular/angular-cli) version 22.2.2.

## Development server

To start a local development server, run:

```bash
ng serve
```

Once the server is running, open your browser and navigate to `http://localhost:4200/`. The application will automatically reload whenever you modify any of the source files.

## Code scaffolding

Angular CLI includes powerful code scaffolding tools. To generate a new component, run:

```bash
ng generate component component-name
```

For a complete list of available schematics (such as `components`, `directives`, or `pipes`), run:

```bash
ng generate --help
```

## Building

To build the project run:

```bash
ng build
```

This will compile your project and store the build artifacts in the `dist/` directory. By default, the production build optimizes your application for performance and speed.

## Running unit tests

To execute unit tests with the [Vitest](https://vitest.dev/) test runner, use the following command:

```bash
ng test
```

For the setup wizard / Globals acceptance gate, run from this directory:

```powershell
npm run test:coverage
npm run build
```

`angular.json` enforces **80% lines for each touched production TS file and
generated HTML template**, not just an aggregate average. Shared validators,
forms, routes, App, Dashboard, Globals, both editors/cards and SetupService are
included. DTOs have no executable logic; the setup fixture is test-only.
Reports are under `coverage\prjxp-frontend`.
For a focused RED/GREEN cycle use `npm test -- --watch=false --include=src/app/features/setup/**/*.spec.ts`
without coverage, then run the complete coverage gate.

## Setup and saved configuration

The deployment base remains `/prjxp/`; the wizard route is `/prjxp/setup`.
Only a successfully loaded incomplete setup status redirects automatically.
Completed but invalid configurations show a version-scoped warning and separate
restart notice on Dashboard and Globals. Both pages offer
`/setup?rerun=1` without resetting the completion marker.

The four typed forms share an in-memory parent snapshot and drafts. Next saves
before advancing; tests are bound to the exact draft revision. Changed or late
results cannot unlock saving. Dimensions can only be cleared explicitly using
`resetDimensions: true`. Optimization changes to batch/timeout retest the full
embedding candidate before Optimization-Save -> Complete. If only Complete
fails, retry does not repeat a successful save. Browser/router exit warnings,
confirmed optional discard and confirmed bypass preserve drafts on errors.
No keys, PATs or receipts are written to Web Storage.

Globals edits the shared `/api/globals/embedding-config` DTO rather than the
legacy model-reference API. Saves/tests use the loaded version through quoted
`If-Match`; responses adopt quoted ETags (tests also adopt `configVersion`).
A matching embedding receipt uses `X-Setup-Test-Id`. All chat CRUD is versioned.
The existing chat test remains a **raw model DTO -> ModelTestResult** adapter
with a post-observation ETag; it is not silently changed to a setup envelope.
`/server-types` and Read Models raw-query endpoints and autocomplete datalists
remain supported. Save errors keep the editor open; conflict reload explicitly
confirms discarding drafts. Gemini/Custom embedding is not a runtime-supported
legacy migration target; use Ollama or OpenAI-compatible embedding instead.

Changes may require a backend restart; the UI never claims to restart it.
Restart the actual backend service in your deployment's Compose file, or stop
and relaunch the local backend process. Check status again to confirm activation.
Live provider calls are intentionally absent from automated tests.

### Verified acceptance (2026-11-12)

Full Vitest run: **532 tests, 42 files, all green**. Lines: **97.44%**
(992/1018); the 80% per-file gate passes for every executable included file.
Production build passes; the unchanged 500 kB warning budget reports an initial
714 kB bundle (below the 1 MB error budget).

| Executable file(s), relative to `src\app` | Measured lines |
| --- | ---: |
| `app.ts`, `app.routes.ts`, `app.html` | 100% each |
| `services\setup.service.ts`, `services\globals.service.ts` | 100% each |
| `features\setup\setup-validators.ts`, `setup-fixtures.ts` (test-only) | 100% each |
| `features\setup\setup-wizard.component.ts` | 97.53% |
| `features\setup\setup-wizard.component.html` | 100% |
| `features\setup\steps\setup-form.ts` | 100% |
| All four `features\setup\steps\setup-*-step.component.ts` and `.html` | 100% each |
| `components\setup-status-banner\setup-status-banner.component.ts` | 100% |
| `components\embedding-model-card\embedding-model-card.component.ts` and `.html` | 100% each |
| `components\embedding-model-editor-dialog\embedding-model-editor-dialog.component.ts` and `.html` | 100% each |
| `components\chat-model-editor-dialog\chat-model-editor-dialog.component.ts` | 97.26% |
| `components\chat-model-editor-dialog\chat-model-editor-dialog.component.html` | 88.31% |
| `features\dashboard\dashboard.component.ts` and `.html` | 100% each |
| `features\globals\globals.component.ts` | 96.29% |
| `features\globals\globals.component.html` | 83.72% |

Machine-readable evidence: `coverage\prjxp-frontend\coverage-summary.json`,
`coverage-final.json` and `lcov.info`. Type-only DTO files and CSS have no
executable lines. No production logic or generated templates are excluded merely
to reach the threshold.

## Running end-to-end tests

For end-to-end (e2e) testing, run:

```bash
ng e2e
```

Angular CLI does not come with an end-to-end testing framework by default. You can choose one that suits your needs.

## Additional Resources

For more information on using the Angular CLI, including detailed command references, visit the [Angular CLI Overview and Command Reference](https://angular.dev/tools/cli) page.
