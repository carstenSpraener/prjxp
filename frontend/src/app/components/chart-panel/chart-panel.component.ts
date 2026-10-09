import { Component, Input } from '@angular/core';

export interface ChartDataPoint {
  label: string;
  value: number;
}

@Component({
  selector: 'chart-panel',
  standalone: true,
  templateUrl: './chart-panel.component.html',
  styleUrl: './chart-panel.component.css'
})
export class ChartPanelComponent {
  @Input() title = '';
  @Input() data: ChartDataPoint[] = [];

  get maxValue(): number {
    return Math.max(...this.data.map(d => d.value), 1);
  }

  getBarWidth(dp: ChartDataPoint): number {
    return (dp.value / this.maxValue) * 100;
  }
}
