import { TestBed } from '@angular/core/testing';
import { LiveUpdateIndicatorComponent } from './live-update-indicator.component';

describe('LiveUpdateIndicatorComponent', () => {
  let component: LiveUpdateIndicatorComponent;
  let fixture: any;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LiveUpdateIndicatorComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(LiveUpdateIndicatorComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should render nothing for IDLE status', () => {
    component.liveUpdate = { status: 'IDLE' };
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.live-update-indicator')).toBeNull();
    expect(fixture.nativeElement.querySelector('.live-update-progress')).toBeNull();
  });

  it('should render nothing for DISABLED status', () => {
    component.liveUpdate = { status: 'DISABLED' };
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.live-update-indicator')).toBeNull();
  });

  it('should render nothing when liveUpdate is null (SNAPSHOT project)', () => {
    component.liveUpdate = null;
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.live-update-indicator')).toBeNull();
  });

  it('should render icon and progress bar for UPDATING status', () => {
    component.liveUpdate = { status: 'UPDATING', progress: 45 };
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.live-update-indicator')).toBeTruthy();
    const progress = fixture.nativeElement.querySelector('.live-update-progress');
    expect(progress).toBeTruthy();
    expect(progress.querySelector('.progress-fill').style.width).toBe('45%');
    expect(progress.querySelector('.progress-pct').textContent).toBe('45%');
  });

  it('should default the progress to 0 when the backend has not reported one yet', () => {
    component.liveUpdate = { status: 'UPDATING' };
    fixture.detectChanges();

    const progress = fixture.nativeElement.querySelector('.live-update-progress');
    expect(progress.querySelector('.progress-fill').style.width).toBe('0%');
    expect(progress.querySelector('.progress-pct').textContent).toBe('0%');
  });
});
