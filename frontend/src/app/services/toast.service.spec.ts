import { TestBed } from '@angular/core/testing';
import { ToastService } from './toast.service';
import { vi } from 'vitest';

describe('ToastService', () => {
  let service: ToastService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [ToastService] });
    service = TestBed.inject(ToastService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('success() should add a success toast', () => {
    service.success('Imported bar');

    expect(service.list()).toEqual([{ id: 1, type: 'success', message: 'Imported bar' }]);
  });

  it('error() should add an error toast', () => {
    service.error('git pull failed');

    expect(service.list()).toEqual([{ id: 1, type: 'error', message: 'git pull failed' }]);
  });

  it('should assign increasing ids', () => {
    service.success('a');
    service.error('b');

    expect(service.list().map(t => t.id)).toEqual([1, 2]);
  });

  it('dismiss() should remove the toast', () => {
    service.success('a');
    service.error('b');

    service.dismiss(1);

    expect(service.list()).toEqual([{ id: 2, type: 'error', message: 'b' }]);
  });

  it('should auto-dismiss after 5 seconds', () => {
    vi.useFakeTimers();
    try {
      service.success('bye');
      expect(service.list()).toHaveLength(1);

      vi.advanceTimersByTime(5000);

      expect(service.list()).toHaveLength(0);
    } finally {
      vi.useRealTimers();
    }
  });
});
