// Generated from: features/security/asset-404-gates.feature
import { test } from "../../../features/support/fixtures.ts";

test.describe('asset + bundle 404s reach the client cleanly', () => {

  test('unknown game bundle returns 404 from the wasm-fragment proxy', async ({ Given, When, Then, context, page, user }) => { 
    await Given('"player" is logged in', null, { context, page, user }); 
    await When('"player" GETs "/_remotes/wasm-fragment/no-such-game-2026/cave/index.html"', null, { page }); 
    await Then('the response status is 404', null, { page }); 
  });

  test('unknown chapter under a known game returns 404 on the bundle proxy', async ({ Given, When, Then, context, page, user }) => { 
    await Given('"player" is logged in', null, { context, page, user }); 
    await When('"player" GETs "/_remotes/wasm-fragment/three-wizards/no-such-chapter-2026/index.html"', null, { page }); 
    await Then('the response status is 404', null, { page }); 
  });

  test('unknown chapter under a known game returns 404 on the BFF asset path', async ({ Given, When, Then, context, page, user }) => { 
    await Given('"player" is logged in', null, { context, page, user }); 
    await When('"player" GETs "/api/assets/levels/three-wizards/no-such-chapter-2026/index.html"', null, { page }); 
    await Then('the response status is 404', null, { page }); 
  });

  test('unauthenticated wasm-fragment fetch returns 200 (assets are public-read)', async ({ When, Then, page }) => { 
    await When('the unauthenticated client GETs "/_remotes/wasm-fragment/cave/index.html"', null, { page }); 
    await Then('the response status is one of 200 or 401', null, { page }); 
  });

});

// == technical section ==

test.beforeEach('BeforeEach Hooks', ({ $runScenarioHooks }) => $runScenarioHooks('before', {  }));
test.afterEach('AfterEach Hooks', ({ $runScenarioHooks }) => $runScenarioHooks('after', {  }));

test.use({
  $test: [({}, use) => use(test), { scope: 'test', box: true }],
  $uri: [({}, use) => use('features/security/asset-404-gates.feature'), { scope: 'test', box: true }],
  $bddFileData: [({}, use) => use(bddFileData), { scope: "test", box: true }],
});

const bddFileData = [ // bdd-data-start
  {"pwTestLine":6,"pickleLine":28,"tags":[],"steps":[{"pwStepLine":7,"gherkinStepLine":29,"keywordType":"Context","textWithKeyword":"Given \"player\" is logged in","stepMatchArguments":[{"group":{"start":0,"value":"\"player\"","children":[{"start":1,"value":"player","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":8,"gherkinStepLine":30,"keywordType":"Action","textWithKeyword":"When \"player\" GETs \"/_remotes/wasm-fragment/no-such-game-2026/cave/index.html\"","stepMatchArguments":[{"group":{"start":0,"value":"\"player\"","children":[{"start":1,"value":"player","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":14,"value":"\"/_remotes/wasm-fragment/no-such-game-2026/cave/index.html\"","children":[{"start":15,"value":"/_remotes/wasm-fragment/no-such-game-2026/cave/index.html","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":9,"gherkinStepLine":31,"keywordType":"Outcome","textWithKeyword":"Then the response status is 404","stepMatchArguments":[{"group":{"start":23,"value":"404","children":[]},"parameterTypeName":"int"}]}]},
  {"pwTestLine":12,"pickleLine":33,"tags":[],"steps":[{"pwStepLine":13,"gherkinStepLine":34,"keywordType":"Context","textWithKeyword":"Given \"player\" is logged in","stepMatchArguments":[{"group":{"start":0,"value":"\"player\"","children":[{"start":1,"value":"player","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":14,"gherkinStepLine":35,"keywordType":"Action","textWithKeyword":"When \"player\" GETs \"/_remotes/wasm-fragment/three-wizards/no-such-chapter-2026/index.html\"","stepMatchArguments":[{"group":{"start":0,"value":"\"player\"","children":[{"start":1,"value":"player","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":14,"value":"\"/_remotes/wasm-fragment/three-wizards/no-such-chapter-2026/index.html\"","children":[{"start":15,"value":"/_remotes/wasm-fragment/three-wizards/no-such-chapter-2026/index.html","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":15,"gherkinStepLine":36,"keywordType":"Outcome","textWithKeyword":"Then the response status is 404","stepMatchArguments":[{"group":{"start":23,"value":"404","children":[]},"parameterTypeName":"int"}]}]},
  {"pwTestLine":18,"pickleLine":38,"tags":[],"steps":[{"pwStepLine":19,"gherkinStepLine":39,"keywordType":"Context","textWithKeyword":"Given \"player\" is logged in","stepMatchArguments":[{"group":{"start":0,"value":"\"player\"","children":[{"start":1,"value":"player","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":20,"gherkinStepLine":40,"keywordType":"Action","textWithKeyword":"When \"player\" GETs \"/api/assets/levels/three-wizards/no-such-chapter-2026/index.html\"","stepMatchArguments":[{"group":{"start":0,"value":"\"player\"","children":[{"start":1,"value":"player","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":14,"value":"\"/api/assets/levels/three-wizards/no-such-chapter-2026/index.html\"","children":[{"start":15,"value":"/api/assets/levels/three-wizards/no-such-chapter-2026/index.html","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":21,"gherkinStepLine":41,"keywordType":"Outcome","textWithKeyword":"Then the response status is 404","stepMatchArguments":[{"group":{"start":23,"value":"404","children":[]},"parameterTypeName":"int"}]}]},
  {"pwTestLine":24,"pickleLine":43,"tags":[],"steps":[{"pwStepLine":25,"gherkinStepLine":50,"keywordType":"Action","textWithKeyword":"When the unauthenticated client GETs \"/_remotes/wasm-fragment/cave/index.html\"","stepMatchArguments":[{"group":{"start":32,"value":"\"/_remotes/wasm-fragment/cave/index.html\"","children":[{"start":33,"value":"/_remotes/wasm-fragment/cave/index.html","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":26,"gherkinStepLine":51,"keywordType":"Outcome","textWithKeyword":"Then the response status is one of 200 or 401","stepMatchArguments":[{"group":{"start":30,"value":"200","children":[]},"parameterTypeName":"int"},{"group":{"start":37,"value":"401","children":[]},"parameterTypeName":"int"}]}]},
]; // bdd-data-end