import { TestBed } from '@angular/core/testing';
import { ChartPanelComponent, ChartDataPoint } from './chart-panel.component';

describe('ChartPanelComponent', () => {
  let component: ChartPanelComponent;
  let fixture: any;

  const mockData: ChartDataPoint[] = [
    { label: 'Item A', value: 50 },
    { label: 'Item B', value: 100 }
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChartPanelComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(ChartPanelComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should render title', () => {
    component.title = 'Test Chart';
    fixture.detectChanges();
    const titleEl = fixture.nativeElement.querySelector('h3');
    expect(titleEl.textContent).toBe('Test Chart');
  });

  it('should show "No data available" when data is empty', () => {
    component.data = [];
    fixture.detectChanges();
    const noDataMsg = fixture.nativeElement.querySelector('p');
    expect(noDataMsg.textContent).toContain('No data available');
  });

  it('should render bars for each data point', () => {
    component.data = mockData;
    fixture.detectChanges();
    const barItems = fixture.nativeElement.querySelectorAll('.bar-item');
    expect(barItems.length).toBe(2);
  });

  it('should render bar labels and values', () => {
    component.data = mockData;
    fixture.detectChanges();
    const labels = fixture.nativeElement.querySelectorAll('.bar-label');
    expect(labels[0].textContent).toBe('Item A');
    expect(labels[1].textContent).toBe('Item B');

    const values = fixture.nativeElement.querySelectorAll('.bar-value');
    expect(values[0].textContent).toBe('50');
    expect(values[1].textContent).toBe('100');
  });

  it('getBarWidth should calculate correct percentage', () => {
    component.data = mockData; // max is 100
    expect(component.getBarWidth(mockData[0])).toBe(50);  // 50/100 = 50%
    expect(component.getBarWidth(mockData[1])).toBe(100); // 100/100 = 100%
  });

  it('maxValue should return max of all values or 1 if empty', () => {
    component.data = mockData;
    expect(component.maxValue).toBe(100);

    component.data = [];
    expect(component.maxValue).toBe(1);
  });

  it('maxValue should handle single data point', () => {
    component.data = [{ label: 'Single', value: 42 }];
    expect(component.maxValue).toBe(42);
  });
});
