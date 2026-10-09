import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { GlobalsService } from './globals.service';
import { ChatModelReference } from '../models/chat-model-reference.model';
import { EmbeddingModelReference } from '../models/embedding-model-reference.model';
import { API_BASE } from '../config/api-config';

describe('GlobalsService', () => {
  let service: GlobalsService;
  let httpMock: HttpTestingController;

  const baseUrl = `${API_BASE}/globals`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [GlobalsService]
    });

    service = TestBed.inject(GlobalsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('getChatModels should call correct endpoint', () => {
    const mockModels: ChatModelReference[] = [
      { stereoType: 'default', serverType: 'ollama', modelName: 'llama3', providerUrl: 'http://localhost:11434' }
    ];

    service.getChatModels().subscribe(models => {
      expect(models).toEqual(mockModels);
    });

    const req = httpMock.expectOne(`${baseUrl}/chat-models`);
    expect(req.request.method).toBe('GET');
    req.flush(mockModels);
  });

  it('createChatModel should call POST endpoint', () => {
    const newModel: ChatModelReference = {
      stereoType: 'coder',
      serverType: 'lm-studio',
      modelName: 'gemma-4-31b',
      providerUrl: 'http://localhost:1234'
    };

    service.createChatModel(newModel).subscribe(result => {
      expect(result).toEqual(newModel);
    });

    const req = httpMock.expectOne(`${baseUrl}/chat-models`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(newModel);
    req.flush(newModel);
  });

  it('updateChatModel should call PUT endpoint with name', () => {
    const updateData: Partial<ChatModelReference> = { modelName: 'llama3.1' };

    service.updateChatModel('default', updateData).subscribe(result => {
      expect(result).toEqual(updateData);
    });

    const req = httpMock.expectOne(`${baseUrl}/chat-models/default`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(updateData);
    req.flush(updateData);
  });

  it('deleteChatModel should call DELETE endpoint', () => {
    service.deleteChatModel('default').subscribe(() => {
      expect(true).toBeTruthy();
    });

    const req = httpMock.expectOne(`${baseUrl}/chat-models/default`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('getServerTypes should call correct endpoint', () => {
    const types = ['ollama', 'openapi', 'lm-studio', 'gemini', 'custom'];

    service.getServerTypes().subscribe(result => {
      expect(result).toEqual(types);
    });

    const req = httpMock.expectOne(`${baseUrl}/server-types`);
    expect(req.request.method).toBe('GET');
    req.flush(types);
  });

  it('getEmbeddingModel should call correct endpoint', () => {
    const mockModel: EmbeddingModelReference = {
      serverType: 'ollama',
      modelName: 'mxbai-embed-large',
      providerUrl: 'http://localhost:11434',
      timeoutSecs: 60
    };

    service.getEmbeddingModel().subscribe(model => {
      expect(model).toEqual(mockModel);
    });

    const req = httpMock.expectOne(`${baseUrl}/embedding-model`);
    expect(req.request.method).toBe('GET');
    req.flush(mockModel);
  });

  it('updateEmbeddingModel should call PUT endpoint', () => {
    const updateData: Partial<EmbeddingModelReference> = {
      modelName: 'nomic-embed-text',
      providerUrl: 'http://localhost:11434'
    };

    service.updateEmbeddingModel(updateData).subscribe(result => {
      expect(result).toEqual(updateData);
    });

    const req = httpMock.expectOne(`${baseUrl}/embedding-model`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(updateData);
    req.flush(updateData);
  });
});
