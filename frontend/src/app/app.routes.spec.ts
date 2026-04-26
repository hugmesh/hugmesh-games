import { loadRemote } from './app.routes';

// Specs for the loadRemote helper. It's the single integration point
// between the host's routing and the federation runtime — every
// federated route (/login, /admin, /admin/games) goes through it. The
// happy path is implicitly covered by e2e; the failure modes need
// direct coverage because they're easy to introduce silently
// (someone removes the main.ts global stash, someone renames an
// exposed class) and can take down auth at runtime.
describe('loadRemote', () => {
  const GLOBAL_KEY = '__SNAPDRAGON_LOAD_REMOTE_MODULE__';
  type LoadRemoteModuleFn = (opts: { remoteName: string; exposedModule: string }) => Promise<Record<string, unknown>>;
  type WithGlobal = Record<string, LoadRemoteModuleFn | undefined>;

  function setGlobal(fn: LoadRemoteModuleFn | undefined): void {
    (globalThis as unknown as WithGlobal)[GLOBAL_KEY] = fn;
  }

  afterEach(() => setGlobal(undefined));

  it('rejects when the global stash is missing', async () => {
    setGlobal(undefined);
    await expectAsync(loadRemote('login', './LoginComponent', 'LoginRemoteComponent'))
      .toBeRejectedWithError(/initFederation didn't run/);
  });

  it('rejects when the named export is not a class', async () => {
    // Module returns the export under a different name (e.g. the remote
    // bundle was rebuilt with a renamed class). loadRemote should fail
    // with a clear message so the route's NavigationError points to the
    // right place rather than a generic "undefined is not a constructor"
    // somewhere deep in Angular's component factory.
    setGlobal(async () => ({ NotTheRightName: class {} }));
    await expectAsync(loadRemote('login', './LoginComponent', 'LoginRemoteComponent'))
      .toBeRejectedWithError(/login\/\.\/LoginComponent.*"LoginRemoteComponent" not a class/);
  });

  it('rejects when the named export is present but not a function', async () => {
    setGlobal(async () => ({ LoginRemoteComponent: { not: 'a class' } }));
    await expectAsync(loadRemote('login', './LoginComponent', 'LoginRemoteComponent'))
      .toBeRejectedWithError(/"LoginRemoteComponent" not a class/);
  });

  it('resolves with the class when everything lines up', async () => {
    class FakeLogin {}
    setGlobal(async ({ remoteName, exposedModule }) => {
      // Verify the call args are forwarded verbatim — federation's
      // remoteName + exposedModule contract is the BFF/manifest contract.
      expect(remoteName).toBe('login');
      expect(exposedModule).toBe('./LoginComponent');
      return { LoginRemoteComponent: FakeLogin };
    });
    const cls = await loadRemote('login', './LoginComponent', 'LoginRemoteComponent');
    expect(cls).toBe(FakeLogin);
  });

  it('forwards rejection from the underlying loadRemoteModule', async () => {
    setGlobal(() => Promise.reject(new Error('network 404 on /_remotes/login/remoteEntry.json')));
    await expectAsync(loadRemote('login', './LoginComponent', 'LoginRemoteComponent'))
      .toBeRejectedWithError(/network 404/);
  });
});
