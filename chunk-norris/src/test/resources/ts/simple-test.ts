import { Component, OnInit, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { BehaviorSubject } from 'rxjs';

/**
 * AppComponent für den einfachen Chunker-Test.
 */
@Component({
    selector: 'app-root',
    template: '<div></div>'
})
export class AppComponent implements OnInit {
    private titleService = inject(Title);
    public readonly sidenavState$ = new BehaviorSubject<'opened' | 'closed'>('opened');

    /**
     * Initialisiert die Komponente.
     */
    ngOnInit(): void {
        console.log('AppComponent initialized');
        this.loadConfiguration();
    }

    /**
     * Lädt die Basiskonfiguration.
     */
    private loadConfiguration(): void {
        const config = { theme: 'dark' };
        console.log('Config loaded', config);
    }

    /**
     * Schaltet die Seitennavigation um.
     */
    public toggleSidenav(): void {
        const currentState = this.sidenavState$.value;
        this.sidenavState$.next(currentState === 'opened' ? 'closed' : 'opened');
    }
}