import { TestBed } from '@angular/core/testing';
import { ChatSettingsDialogComponent } from './chat-settings-dialog.component';
import { GlobalsService } from '../../services/globals.service';
import { ChatService } from '../../services/chat.service';
import { ChatSettingsPersistenceService } from '../../services/chat-settings-persistence.service';
import { of } from 'rxjs';
import { vi } from 'vitest';

describe('ChatSettingsDialogComponent', () => {
  let component: ChatSettingsDialogComponent;
  let fixture: any;
  let mockGlobalsService: any;
  let mockChatService: any;
  let mockPersistenceService: any;

  beforeEach(async () => {
    mockGlobalsService = {
      getChatModels: vi.fn().mockReturnValue(of([
        { stereoType: 'default', serverType: 'ollama', modelName: 'llama3' }
      ]))
    };

    mockChatService = {
      getAvailableProjects: vi.fn().mockReturnValue(of(['prjxp', 'my-project']))
    };

    mockPersistenceService = {
      load: vi.fn().mockReturnValue(null),
      save: vi.fn()
    };

    await TestBed.configureTestingModule({
      imports: [ChatSettingsDialogComponent],
      providers: [
        { provide: GlobalsService, useValue: mockGlobalsService },
        { provide: ChatService, useValue: mockChatService },
        { provide: ChatSettingsPersistenceService, useValue: mockPersistenceService }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(ChatSettingsDialogComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('ngOnInit should load chat models and projects', () => {
    component.ngOnInit();
    expect(mockGlobalsService.getChatModels).toHaveBeenCalled();
    expect(mockChatService.getAvailableProjects).toHaveBeenCalled();
  });

  it('should have correct initial state', () => {
    expect(component.selectedModel()).toBe('');
    expect(component.selectedProject()).toBe('None');
    expect(component.useTools()).toBe(false);
  });

  it('loadChatModels should set selectedModel to first model', () => {
    component.loadChatModels();
    expect(component.chatModels()).toHaveLength(1);
    expect(component.selectedModel()).toBe('default');
  });

  it('loadProjects should populate availableProjects', () => {
    component.loadProjects();
    expect(component.availableProjects()).toEqual(['prjxp', 'my-project']);
  });

  it('onSave with project should emit correct settings', () => {
    component.selectedModel.set('default');
    component.selectedProject.set('prjxp');
    component.useTools.set(true);

    const emitted: any[] = [];
    component.close.subscribe(val => emitted.push(val));

    component.onSave();

    expect(emitted).toEqual([{
      model: 'default',
      projectContext: 'prjxp',
      useTools: true
    }]);
  });

  it('onSave with None project should emit null projectContext', () => {
    component.selectedModel.set('default');
    component.selectedProject.set('None');

    const emitted: any[] = [];
    component.close.subscribe(val => emitted.push(val));

    component.onSave();

    expect(emitted).toEqual([{
      model: 'default',
      projectContext: null,
      useTools: false
    }]);
  });

  it('onCancel should emit close event', () => {
    const emitted: any[] = [];
    component.close.subscribe(val => emitted.push(val));

    component.onCancel();

    expect(emitted).toHaveLength(1);
  });

  it('should render select elements', () => {
    fixture.detectChanges();
    const selects = fixture.nativeElement.querySelectorAll('select');
    expect(selects.length).toBe(2);
  });

  it('should render toggle checkbox', () => {
    fixture.detectChanges();
    const checkbox = fixture.nativeElement.querySelector('input[type="checkbox"]');
    expect(checkbox).toBeTruthy();
  });

  it('should render save and cancel buttons', () => {
    fixture.detectChanges();
    const saveBtn = fixture.nativeElement.querySelector('.btn-save');
    expect(saveBtn).toBeTruthy();

    const cancelBtn = fixture.nativeElement.querySelector('.btn-cancel');
    expect(cancelBtn).toBeTruthy();
  });

  it('onSave should persist settings before emitting', () => {
    component.selectedModel.set('default');
    component.selectedProject.set('prjxp');
    component.useTools.set(true);

    const emitted: any[] = [];
    component.close.subscribe(val => emitted.push(val));

    component.onSave();

    expect(mockPersistenceService.save).toHaveBeenCalledWith({
      model: 'default',
      projectContext: 'prjxp',
      useTools: true
    });
    expect(emitted).toEqual([{
      model: 'default',
      projectContext: 'prjxp',
      useTools: true
    }]);
  });

  it('onCancel should restore from persisted settings', () => {
    mockPersistenceService.load.mockReturnValue({
      model: 'persisted-model',
      projectContext: 'persisted-project',
      useTools: true
    });

    const emitted: any[] = [];
    component.close.subscribe(val => emitted.push(val));

    component.onCancel();

    expect(mockPersistenceService.load).toHaveBeenCalled();
    expect(emitted).toEqual([{
      model: 'persisted-model',
      projectContext: 'persisted-project',
      useTools: true
    }]);
  });

  it('onCancel should fall back to input values when no persisted settings', () => {
    component.currentModel = 'input-model';
    component.currentProjectContext = 'input-project';
    component.currentUseTools = true;

    const emitted: any[] = [];
    component.close.subscribe(val => emitted.push(val));

    component.onCancel();

    // load() returns null (mocked), so fall back to input values
    expect(emitted).toEqual([{
      model: 'input-model',
      projectContext: 'input-project',
      useTools: true
    }]);
  });
});
