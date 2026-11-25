import { Component } from '@angular/core';

/**
 * Zentrales Dialog-Overlay: dunkler, leicht unscharfer Vollbild-Hintergrund,
 * der den projizierten Dialog-Inhalt zentriert.
 *
 * Bewusst OHNE Click-Handler: Ein Klick auf den Hintergrund schließt den
 * Dialog nicht — nur explizite Buttons (Save/Cancel/✕) tun das. So gehen
 * bei versehentlichen Klicks außerhalb keine Eingaben verloren.
 */
@Component({
  selector: 'dialog-overlay',
  standalone: true,
  imports: [],
  template: `<div class="dialog-overlay"><ng-content /></div>`,
  styleUrl: './dialog-overlay.component.css'
})
export class DialogOverlayComponent {}
