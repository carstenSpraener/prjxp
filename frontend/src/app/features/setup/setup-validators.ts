import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';
export const nonBlank: ValidatorFn = c => typeof c.value === 'string' && c.value.trim() ? null : {required: true};
export const urlOrEnv: ValidatorFn = c => {
  const value = typeof c.value === 'string' ? c.value.trim() : '';
  if (/^\$\{[A-Za-z_][A-Za-z0-9_.-]*(?::[^}]*)?}$/.test(value)) return null;
  try {
    const url = new URL(value);
    if (['http:', 'https:'].includes(url.protocol) && url.host) return null;
  } catch { /* A malformed URL is a form error. */ }
  return {urlOrEnv: true};
};
export function numeric(min: number, integer = false, max = Infinity): ValidatorFn {
  return (c: AbstractControl): ValidationErrors | null => c.value == null ? null :
    typeof c.value === 'number' && Number.isFinite(c.value) && c.value >= min && c.value <= max &&
    (!integer || Number.isInteger(c.value)) ? null : {range: true};
}
export const envName: ValidatorFn = c => /^[A-Za-z_][A-Za-z0-9_]*$/.test(c.value ?? '') ? null : {envName: true};
export function setupError(error: unknown): string {
  const code = error && typeof error === 'object' && 'error' in error &&
    error.error && typeof error.error === 'object' && 'code' in error.error ? error.error.code : null;
  const messages: Record<string, string> = {
    CONFIG_CHANGED: 'Konfiguration geändert. Neu laden erfordert Bestätigung; Ihre Eingaben bleiben erhalten.',
    CONFIG_OVERRIDDEN: 'Deployment überschreibt diese Einstellung.',
    INDEX_REBUILD_REQUIRED: 'Index extern neu aufbauen. Der Wizard löscht keine Daten.',
    SETUP_NOT_VERIFIED: 'Embedding und Chat müssen passend zum gespeicherten Stand getestet sein.',
    PRECONDITION_REQUIRED: 'Bearbeitungsversion fehlt. Bitte bewusst neu laden.',
    INVALID_REQUEST: 'Ungültige Einstellungen. Bitte Felder prüfen.',
    TEST_FAILED: 'Verbindungstest fehlgeschlagen.',
    MISSING_SECRET: 'Erforderliches Secret nicht verfügbar.'
  };
  return typeof code === 'string' && code in messages ? `${code}: ${messages[code]}` :
    'Anfrage fehlgeschlagen. Eingaben bleiben erhalten; bitte erneut versuchen.';
}
