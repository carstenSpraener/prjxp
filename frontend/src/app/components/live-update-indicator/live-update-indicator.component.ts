import { Component, Input } from '@angular/core';
import { ProjectInfo } from '../../models/project-info.model';

/**
 * Visual feedback for the hub's automatic live updates: an animated indicator
 * (spinning ring + pulsing dot) plus a thin progress bar, shown only while a live
 * update is actively running (status UPDATING). Renders nothing for IDLE (including a
 * queued-but-not-yet-running update) / DISABLED / SNAPSHOT projects (no liveUpdate block).
 */
@Component({
  selector: 'live-update-indicator',
  standalone: true,
  templateUrl: './live-update-indicator.component.html',
  styleUrl: './live-update-indicator.component.css'
})
export class LiveUpdateIndicatorComponent {
  @Input() liveUpdate: ProjectInfo['liveUpdate'] = null;

  get isUpdating(): boolean {
    return this.liveUpdate?.status === 'UPDATING';
  }

  /** 0-100 progress of the running update; null when the backend has not reported one yet. */
  get progress(): number | null {
    return this.liveUpdate?.progress ?? null;
  }
}
