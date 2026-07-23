import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

// Cross-project import — see federated-remotes-admin.spec.ts for why
// the spec lives in src/ instead of projects/admin/.
import { UploadRemoteComponent } from '../../projects/admin/src/app/upload-remote.component';

// UploadRemoteComponent owns the five admin-bundle endpoints the host's
// deleted GameUploadService used to wrap. Pinning the URLs + methods
// here catches any accidental drift if someone refactors the BFF
// routes — federation has no shared interface, so the URL strings ARE
// the contract.
describe('UploadRemoteComponent', () => {
  let component: UploadRemoteComponent;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
      ],
    });
    component = TestBed.createComponent(UploadRemoteComponent).componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  describe('ngOnInit', () => {
    it('fetches /api/admin/roles and populates roles()', () => {
      component.ngOnInit();
      const req = httpMock.expectOne('/api/admin/roles');
      expect(req.request.method).toBe('GET');
      req.flush([
        { name: 'admin', displayName: 'Admin', description: '', enabled: true },
        { name: 'beta',  displayName: 'Beta',  description: '', enabled: true },
      ]);
      expect(component.roles().length).toBe(2);
      expect(component.roles()[0].name).toBe('admin');
    });

    it('falls back to empty roles on error', () => {
      component.ngOnInit();
      httpMock.expectOne('/api/admin/roles').flush(null, { status: 500, statusText: 'err' });
      expect(component.roles()).toEqual([]);
    });
  });

  describe('upload', () => {
    it('does nothing when no file is selected', () => {
      component.selectedFile = null;
      component.upload();
      httpMock.expectNone('/api/admin/games/upload');
      expect(component.uploadStatus()).toBe('idle');
    });

    it('POSTs multipart to /api/admin/games/upload and sets status=success', () => {
      const file = new File(['zip-bytes'], 'cave-hugmesh-bundle.zip', { type: 'application/zip' });
      component.selectedFile = file;
      component.upload();

      expect(component.uploadStatus()).toBe('uploading');
      const req = httpMock.expectOne('/api/admin/games/upload');
      expect(req.request.method).toBe('POST');
      // Multipart body is a FormData — the browser may wrap File-likes
      // so check by name instead of identity (object refs aren't stable
      // across FormData.get calls in some browsers).
      const form = req.request.body as FormData;
      expect(form instanceof FormData).toBeTrue();
      const bundle = form.get('bundle') as File;
      expect(bundle).toBeInstanceOf(File);
      expect(bundle.name).toBe('cave-hugmesh-bundle.zip');

      req.flush({
        slug: 'cave', contentHash: 'sha256:abc', versionDir: '/v/2026',
        uploadedAt: '2026-04-25T17:00:00Z',
      });
      expect(component.uploadStatus()).toBe('success');
      expect(component.uploadResult()?.slug).toBe('cave');
    });

    it('surfaces BFF error on upload failure and sets status=error', () => {
      component.selectedFile = new File(['x'], 'bad.zip');
      component.upload();
      httpMock.expectOne('/api/admin/games/upload').flush(
        { message: 'manifest schema_version != 2' },
        { status: 400, statusText: 'Bad Request' },
      );
      expect(component.uploadStatus()).toBe('error');
      expect(component.uploadError()).toBe('manifest schema_version != 2');
    });
  });

  describe('loadHistory', () => {
    it('skips the GET when historySlug is empty', () => {
      component.historySlug = '';
      component.loadHistory();
      httpMock.expectNone(r => /\/api\/admin\/games\/.+\/uploads/.test(r.url));
    });

    it('GETs /api/admin/games/{slug}/uploads and populates history()', () => {
      component.historySlug = 'cave';
      component.loadHistory();
      const req = httpMock.expectOne('/api/admin/games/cave/uploads');
      expect(req.request.method).toBe('GET');
      req.flush([
        { id: 1, slug: 'cave', versionDir: '/v/old', contentHash: 'sha256:old',
          manifestJson: '{}', uploadedBy: 'admin', uploadedAt: 't1', supersededAt: 't2' },
        { id: 2, slug: 'cave', versionDir: '/v/new', contentHash: 'sha256:new',
          manifestJson: '{}', uploadedBy: 'admin', uploadedAt: 't3', supersededAt: null },
      ]);
      expect(component.history().length).toBe(2);
    });
  });

  describe('grant / revoke', () => {
    it('grant: rejects role/user scope without a value', () => {
      component.accessSlug = 'cave';
      component.accessScope = 'role';
      component.accessValue = '';
      component.grant();
      httpMock.expectNone(r => r.url === '/api/admin/games/cave/access');
      expect(component.accessStatus()).toContain('value required');
    });

    it('grant: POSTs scope=all without a value', () => {
      component.accessSlug = 'cave';
      component.accessScope = 'all';
      component.grant();
      const req = httpMock.expectOne('/api/admin/games/cave/access');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ scope: 'all', value: undefined });
      req.flush({});
      expect(component.accessStatus()).toContain('granted');
    });

    it('grant: POSTs scope=role with the value', () => {
      component.accessSlug = 'cave';
      component.accessScope = 'role';
      component.accessValue = 'beta';
      component.grant();
      const req = httpMock.expectOne('/api/admin/games/cave/access');
      expect(req.request.body).toEqual({ scope: 'role', value: 'beta' });
      req.flush({});
      expect(component.accessStatus()).toContain('beta');
    });

    it('revoke: DELETEs with scope query param + optional value', () => {
      component.accessSlug = 'cave';
      component.accessScope = 'user';
      component.accessValue = 'casdoor-uuid';
      component.revoke();
      // Component builds the URL with URLSearchParams baked into the
      // path (not as separate req.params), so match against r.url
      // directly rather than the parsed params.
      const req = httpMock.expectOne(r =>
        r.method === 'DELETE' &&
        r.url.startsWith('/api/admin/games/cave/access?') &&
        r.url.includes('scope=user') &&
        r.url.includes('value=casdoor-uuid'),
      );
      req.flush({});
      expect(component.accessStatus()).toContain('revoked');
    });
  });
});
