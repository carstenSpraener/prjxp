import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ProjectListCardComponent } from './project-list-card.component';
import { LiveProjectCandidate } from '../../models/live-project-candidate.model';

describe('ProjectListCardComponent', () => {
  let component: ProjectListCardComponent;
  let fixture: ComponentFixture<ProjectListCardComponent>;

  const mockCandidate: LiveProjectCandidate = {
    name: 'test-project',
    directory: '/path/to/test-project',
    hasConfigFile: true,
    languages: ['Java', 'Kotlin']
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProjectListCardComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(ProjectListCardComponent);
    component = fixture.componentInstance;
    component.candidate = mockCandidate;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should display candidate name', () => {
    const nativeEl = fixture.nativeElement;
    expect(nativeEl.querySelector('h3').textContent).toContain(mockCandidate.name);
  });

  it('should display candidate directory', () => {
    const nativeEl = fixture.nativeElement;
    expect(nativeEl.querySelector('.card-body p').textContent).toContain(mockCandidate.directory);
  });

  it('should show config badge when hasConfigFile is true', () => {
    const nativeEl = fixture.nativeElement;
    expect(nativeEl.querySelector('.config-badge')).toBeTruthy();
  });

  it('should not show config badge when hasConfigFile is false', async () => {
    component.candidate = {
      name: 'test-project',
      directory: '/path/to/test-project',
      hasConfigFile: false,
      languages: ['Java', 'Kotlin']
    };
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const nativeEl = fixture.nativeElement;
    expect(nativeEl.querySelector('.config-badge')).toBeNull();
  });

  it('should display language badges', () => {
    const nativeEl = fixture.nativeElement;
    const langBadges = nativeEl.querySelectorAll('.lang-badge');
    expect(langBadges.length).toBe(mockCandidate.languages.length);
    expect(langBadges[0].textContent).toBe('Java');
    expect(langBadges[1].textContent).toBe('Kotlin');
  });

  it('should emit register event when button is clicked', () => {
    let emitted: LiveProjectCandidate | undefined;
    component.register.subscribe(candidate => {
      emitted = candidate;
    });

    const btn = fixture.nativeElement.querySelector('.btn-primary');
    btn.click();

    expect(emitted).toBe(mockCandidate);
  });
});
