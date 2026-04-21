import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable } from 'rxjs';

export interface SelectItem {
    label: string;
    value: any;
}

export interface Benutzer {
    id: string;
    name: string;
    isBlocked: boolean;
}

@Injectable({
    providedIn: 'root'
})
export class BenutzerDataService {
    private httpClient = inject(HttpClient);
    private apiStatusSubject = new BehaviorSubject<string | null>(null);
    private updateBlockedStatusApiSubject = new BehaviorSubject<string | null>(null);
    private URL_UPDATE_BENUTZER_BLOCKED = '/api/benutzer/update-blocked';

    /**
     * Liefert eine Liste der Regionalbereiche.
     */
    public getRegionalbereiche(): SelectItem[] {
        return [
            { label: 'Nord', value: 'N' },
            { label: 'Süd', value: 'S' }
        ];
    }

    /**
     * Setzt den Status der API-Verbindung zurück.
     */
    public resetApiStatus():void {
        this.apiStatusSubject.next(null);
        this.updateBlockedStatusApiSubject.next(null);
    }

    /**
     * Aktualisiert den Sperrstatus eines Benutzers über das Backend.
     */
    public updateUserBlockedStatus(benutzer: Benutzer) {
        this.httpClient.post(this.URL_UPDATE_BENUTZER_BLOCKED, benutzer).subscribe((response: Benutzer) => {
            console.log('Update success', response);
        }, err => {
            console.error('Update failed', err);
        });
    }
}