import { vi } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ImportComponent } from './import.component';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ImportService } from '../../services/import.service';
import { ProjectInfo } from '../../models/project-info.model';
import { LiveProjectCandidate, ProjectCreateRequest } from '../../models/live-project-candidate.model';
import { of, throwError, tap } from 'rxjs';

describe('ImportComponent', () => {
  let component: ImportComponent;
  let fixture: ComponentFixture<ImportComponent>;
  let importService: ImportService;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ImportComponent],
      providers: [
        ImportService,
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(ImportComponent);
    component = fixture.componentInstance;
    importService = TestBed.inject(ImportService);
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeDefined();
  });

  it('should default to upload tab', () => {
    expect(component.activeTab()).toBe('upload');
  });

  it('should switch to live tab', () => {
    component.activeTab.set('live');
    fixture.detectChanges();
    expect(component.activeTab()).toBe('live');
  });

  it('should switch to embedded tab', () => {
    component.activeTab.set('embedded');
    fixture.detectChanges();
    expect(component.activeTab()).toBe('embedded');
  });

  describe('onFileSelected', () => {
    it('should call uploadArchive and add project to uploadedProjects', () => {
      const file = new File(['content'], 'test.tar.gz');
      const mockProject: ProjectInfo = { name: 'test', status: 'READY' };

      vi.spyOn(importService, 'uploadArchive').mockImplementation((f: File) => {
        return f === file ? of(mockProject) : of({} as ProjectInfo);
      });

      component.onFileSelected(file);
      expect(component.uploadedProjects().length).toBe(1);
      expect(component.uploadedProjects()[0]).toEqual(mockProject);
    });
  });

  describe('onEmbeddedFileSelected', () => {
    it('should call uploadEmbedded and add project to embeddedProjects', () => {
      const file = new File(['content'], 'test-embedded.tgz');
      const mockProject: ProjectInfo = { name: 'test', status: 'IMPORTING' };

      vi.spyOn(importService, 'uploadEmbedded').mockImplementation((f: File) => {
        return f === file ? of(mockProject) : of({} as ProjectInfo);
      });

      component.onEmbeddedFileSelected(file);
      expect(component.embeddedProjects().length).toBe(1);
      expect(component.embeddedProjects()[0]).toEqual(mockProject);
    });
  });

  describe('scanForLiveProjects', () => {
    it('should call scanLiveProjects and update liveCandidates', () => {
      const mockCandidates: LiveProjectCandidate[] = [
        { name: 'proj1', directory: '/path/proj1', hasConfigFile: true, languages: ['Java'] }
      ];

      const spy = vi.spyOn(importService, 'scanLiveProjects').mockReturnValue(of(mockCandidates));

      component.scanForLiveProjects();

      expect(spy).toHaveBeenCalled();
      // After the observable completes, liveCandidates should be set and isScanning false
      expect(component.liveCandidates().length).toBe(1);
      expect(component.isScanning()).toBe(false);
    });

    it('should set isScanning to false on error', () => {
      vi.spyOn(importService, 'scanLiveProjects').mockReturnValue(throwError(() => new Error('fail')));

      component.scanForLiveProjects();
      expect(component.isScanning()).toBe(false);
    });
  });

  describe('registerProject', () => {
    it('should call registerProject with correct request and remove candidate from list', () => {
      const candidate: LiveProjectCandidate = {
        name: 'my-proj',
        directory: '/path/my-proj',
        hasConfigFile: false,
        languages: ['Kotlin']
      };

      component.liveCandidates.set([candidate]);

      vi.spyOn(importService, 'registerProject').mockReturnValue(of({} as ProjectInfo));

      component.registerProject(candidate);

      expect(importService.registerProject).toHaveBeenCalled();
      const callArgs = (importService.registerProject as any).mock.calls[0][0] as ProjectCreateRequest;
      expect(callArgs.name).toBe('my-proj');
      expect(callArgs.kind).toBe('LIVE');
      expect(callArgs.rootDir).toBe('/path/my-proj');

      expect(component.liveCandidates().length).toBe(0);
    });
  });
});
