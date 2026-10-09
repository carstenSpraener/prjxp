import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ReindexConfirmationDialogComponent } from './reindex-confirmation-dialog.component';

describe('ReindexConfirmationDialogComponent', () => {
  let component: ReindexConfirmationDialogComponent;
  let fixture: ComponentFixture<ReindexConfirmationDialogComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ReindexConfirmationDialogComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(ReindexConfirmationDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should emit confirmed when confirm is called', () => {
    let emitted = false;
    component.confirmed.subscribe(() => { emitted = true; });

    component.onConfirm();
    expect(emitted).toBeTruthy();
  });

  it('should emit cancelled when cancel is called', () => {
    let emitted = false;
    component.cancelled.subscribe(() => { emitted = true; });

    component.onCancel();
    expect(emitted).toBeTruthy();
  });
});
