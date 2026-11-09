import { TestBed } from '@angular/core/testing';
import { ToastContainerComponent } from './toast-container.component';
import { ToastService } from '../../services/toast.service';

describe('ToastContainerComponent', () => {
  let component: ToastContainerComponent;
  let fixture: any;
  let toastService: ToastService;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ToastContainerComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(ToastContainerComponent);
    component = fixture.componentInstance;
    toastService = TestBed.inject(ToastService);
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should render nothing when there are no toasts', () => {
    expect(fixture.nativeElement.querySelectorAll('.toast')).toHaveLength(0);
  });

  it('should render the toast list with type styling', () => {
    toastService.success('Imported bar');
    toastService.error('git pull failed');
    fixture.detectChanges();

    const toasts = fixture.nativeElement.querySelectorAll('.toast') as NodeListOf<HTMLElement>;
    expect(toasts).toHaveLength(2);
    expect(toasts[0].textContent).toContain('Imported bar');
    expect(toasts[0].classList).toContain('toast-success');
    expect(toasts[1].textContent).toContain('git pull failed');
    expect(toasts[1].classList).toContain('toast-error');
  });

  it('close button should dismiss the toast', () => {
    toastService.success('bye');
    fixture.detectChanges();

    const close = fixture.nativeElement.querySelector('.toast-close') as HTMLButtonElement;
    expect(close).toBeTruthy();
    close.click();
    fixture.detectChanges();

    expect(toastService.list()).toHaveLength(0);
    expect(fixture.nativeElement.querySelectorAll('.toast')).toHaveLength(0);
  });
});
