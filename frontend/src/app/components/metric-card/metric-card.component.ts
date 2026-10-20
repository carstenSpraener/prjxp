import { Component, Input } from '@angular/core';

@Component({
  selector: 'metric-card',
  standalone: true,
  templateUrl: './metric-card.component.html',
  styleUrl: './metric-card.component.css'
})
export class MetricCardComponent {
  @Input() label = '';
  @Input() value: number | null = null;
  @Input() maxValue: number | null = null;
  @Input() unit = '';
  @Input() color: 'cyan' | 'magenta' | 'green' | 'orange' = 'cyan';

  get percentage(): number {
    if (this.maxValue && this.value) {
      return Math.round((this.value / this.maxValue) * 100);
    }
    return 0;
  }

  get displayValue(): string {
    if (this.value === null) return '--';

    // Format bytes to GB if unit is GB
    if (this.unit === 'GB' && this.maxValue) {
      return (this.value / (1024 * 1024 * 1024)).toFixed(1) + ' GB';
    }

    // Format cpuUsage as percentage
    if (this.unit === '%') {
      return Math.round(this.value * 100) + '%';
    }

    return this.value.toString() + (this.unit ? ' ' + this.unit : '');
  }

  get colorClass(): string {
    return `text-${this.color}`;
  }

  getColor(): string {
    const colors: Record<string, string> = {
      cyan: 'var(--accent-cyan)',
      magenta: 'var(--accent-magenta)',
      green: 'var(--accent-green)',
      orange: 'var(--accent-orange)'
    };
    return colors[this.color] || 'var(--accent-cyan)';
  }
}
