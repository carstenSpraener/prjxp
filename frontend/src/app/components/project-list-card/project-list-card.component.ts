import { Component, Input, Output, EventEmitter } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { LiveProjectCandidate } from '../../models/live-project-candidate.model';

@Component({
  selector: 'project-list-card',
  standalone: true,
  imports: [NgFor, NgIf],
  templateUrl: './project-list-card.component.html',
  styleUrl: './project-list-card.component.css'
})
export class ProjectListCardComponent {
  @Input() candidate!: LiveProjectCandidate;
  @Output() register = new EventEmitter<LiveProjectCandidate>();

  onRegister(): void {
    this.register.emit(this.candidate);
  }
}
