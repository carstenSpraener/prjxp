import { Directive, EventEmitter, Input, Output, OnChanges, OnDestroy } from '@angular/core';
import { AbstractControl, FormGroup } from '@angular/forms';
import { Subscription } from 'rxjs';
import { SetupTestResult } from '../../../models/setup.model';

@Directive()
export abstract class SetupForm<T, C extends { [K in keyof C]: AbstractControl }> implements OnChanges, OnDestroy {
  @Input() initial: T | null = null;
  @Input() result: SetupTestResult | null = null;
  @Input() testValid = false;
  @Input() busy = false;
  @Output() draftChange = new EventEmitter<T>();
  @Output() validityChange = new EventEmitter<boolean>();
  @Output() testRequested = new EventEmitter<T>();
  abstract form: FormGroup<C>;
  abstract configuration(): T;
  protected abstract patch(): void;
  private subscription?: Subscription;
  ngOnChanges(): void {
    this.patch();
    this.busy ? this.form.disable({emitEvent: false}) : this.form.enable({emitEvent: false});
    this.reportValidity();
    if (!this.subscription) this.subscription = this.form.valueChanges.subscribe(() => {
      this.draftChange.emit(this.configuration());
      this.reportValidity();
    });
  }
  ngOnInit(): void { if (!this.subscription) this.ngOnChanges(); }
  protected reportValidity(): void { this.validityChange.emit(this.form.valid); }
  requestTest(): void {
    if (this.busy || this.form.invalid) return;
    this.testRequested.emit(this.configuration());
  }
  ngOnDestroy(): void { this.subscription?.unsubscribe(); }
}
