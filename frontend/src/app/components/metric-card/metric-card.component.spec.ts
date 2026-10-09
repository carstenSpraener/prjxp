import { TestBed } from '@angular/core/testing';
import { MetricCardComponent } from './metric-card.component';

describe('MetricCardComponent', () => {
  let component: MetricCardComponent;
  let fixture: any;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MetricCardComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(MetricCardComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should render label', () => {
    component.label = 'CPU Usage';
    fixture.detectChanges();
    const labelEl = fixture.nativeElement.querySelector('.card-label');
    expect(labelEl.textContent).toBe('CPU Usage');
  });

  it('should render value and unit', () => {
    component.value = 42;
    component.unit = 'ms';
    fixture.detectChanges();
    const valueEl = fixture.nativeElement.querySelector('.card-value');
    expect(valueEl.textContent).toBe('42 ms');
  });

  it('displayValue should return -- when value is null', () => {
    component.value = null;
    expect(component.displayValue).toBe('--');
  });

  it('displayValue should format bytes to GB when unit is GB', () => {
    component.value = 1073741824; // 1 GB in bytes
    component.maxValue = 16000000000; // some max
    component.unit = 'GB';
    expect(component.displayValue).toBe('1.0 GB');
  });

  it('displayValue should format as percentage when unit is %', () => {
    component.value = 0.45;
    component.unit = '%';
    expect(component.displayValue).toBe('45%');
  });

  it('displayValue should return plain number when no unit', () => {
    component.value = 100;
    component.unit = '';
    expect(component.displayValue).toBe('100');
  });

  it('percentage should calculate correctly when maxValue is provided', () => {
    component.value = 50;
    component.maxValue = 100;
    expect(component.percentage).toBe(50);
  });

  it('percentage should return 0 when maxValue is null', () => {
    component.value = 50;
    component.maxValue = null;
    expect(component.percentage).toBe(0);
  });

  it('percentage should return 0 when value is null', () => {
    component.value = null;
    component.maxValue = 100;
    expect(component.percentage).toBe(0);
  });

  it('colorClass should return correct CSS class', () => {
    component.color = 'cyan';
    expect(component.colorClass).toBe('text-cyan');

    component.color = 'magenta';
    expect(component.colorClass).toBe('text-magenta');

    component.color = 'green';
    expect(component.colorClass).toBe('text-green');

    component.color = 'orange';
    expect(component.colorClass).toBe('text-orange');
  });

  it('should show progress bar when maxValue is provided', () => {
    component.value = 75;
    component.maxValue = 100;
    fixture.detectChanges();
    const progressBar = fixture.nativeElement.querySelector('.progress-bar');
    expect(progressBar).toBeTruthy();

    const progressFill = fixture.nativeElement.querySelector('.progress-fill');
    expect(progressFill.style.width).toBe('75%');
  });

  it('should not show progress bar when maxValue is null', () => {
    component.value = 75;
    component.maxValue = null;
    fixture.detectChanges();
    const progressBar = fixture.nativeElement.querySelector('.progress-bar');
    expect(progressBar).toBeFalsy();
  });
});
