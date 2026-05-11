// Generated from: features/security/upload-auth-gates.feature
import { test } from "../../../features/support/fixtures.ts";

test.describe('/api/admin/games/upload — auth + scope gates', () => {

  test('unauthenticated POST is rejected with 401', async ({ When, Then, page }) => { 
    await When('the unauthenticated client POSTs to "/api/admin/games/upload"', null, { page }); 
    await Then('the response status is 401', null, { page }); 
  });

  test('a plain player cannot upload — 403', async ({ Given, When, Then, context, page, user }) => { 
    await Given('"player" is logged in', null, { context, page, user }); 
    await When('"player" POSTs an empty body to "/api/admin/games/upload"', null, { page }); 
    await Then('the response status is 403', null, { page }); 
  });

  test('a mod (non-publisher) cannot upload — 403', async ({ Given, When, Then, context, page, user }) => { 
    await Given('"mod" is logged in', null, { context, page, user }); 
    await When('"mod" POSTs an empty body to "/api/admin/games/upload"', null, { page }); 
    await Then('the response status is 403', null, { page }); 
  });

  test('a publisher with no scope on the target slug is rejected', async ({ Given, When, Then, context, page, user }) => { 
    await Given('"publisher-three-wizards" is logged in', null, { context, page, user }); 
    await When('"publisher-three-wizards" attempts to upload an empty bundle for game "dodecahedron"', null, { page }); 
    await Then('the response status is one of 403 or 415', null, { page }); 
  });

  test('an admin reaches the body parser (request body invalid, not auth)', async ({ Given, When, Then, context, page, user }) => { 
    await Given('"admin" is logged in', null, { context, page, user }); 
    await When('"admin" POSTs an empty body to "/api/admin/games/upload"', null, { page }); 
    await Then('the response status is one of 400 or 415', null, { page }); 
  });

});

// == technical section ==

test.beforeEach('BeforeEach Hooks', ({ $runScenarioHooks }) => $runScenarioHooks('before', {  }));
test.afterEach('AfterEach Hooks', ({ $runScenarioHooks }) => $runScenarioHooks('after', {  }));

test.use({
  $test: [({}, use) => use(test), { scope: 'test', box: true }],
  $uri: [({}, use) => use('features/security/upload-auth-gates.feature'), { scope: 'test', box: true }],
  $bddFileData: [({}, use) => use(bddFileData), { scope: "test", box: true }],
});

const bddFileData = [ // bdd-data-start
  {"pwTestLine":6,"pickleLine":19,"tags":[],"steps":[{"pwStepLine":7,"gherkinStepLine":20,"keywordType":"Action","textWithKeyword":"When the unauthenticated client POSTs to \"/api/admin/games/upload\"","stepMatchArguments":[{"group":{"start":36,"value":"\"/api/admin/games/upload\"","children":[{"start":37,"value":"/api/admin/games/upload","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":8,"gherkinStepLine":21,"keywordType":"Outcome","textWithKeyword":"Then the response status is 401","stepMatchArguments":[{"group":{"start":23,"value":"401","children":[]},"parameterTypeName":"int"}]}]},
  {"pwTestLine":11,"pickleLine":23,"tags":[],"steps":[{"pwStepLine":12,"gherkinStepLine":24,"keywordType":"Context","textWithKeyword":"Given \"player\" is logged in","stepMatchArguments":[{"group":{"start":0,"value":"\"player\"","children":[{"start":1,"value":"player","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":13,"gherkinStepLine":25,"keywordType":"Action","textWithKeyword":"When \"player\" POSTs an empty body to \"/api/admin/games/upload\"","stepMatchArguments":[{"group":{"start":0,"value":"\"player\"","children":[{"start":1,"value":"player","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":32,"value":"\"/api/admin/games/upload\"","children":[{"start":33,"value":"/api/admin/games/upload","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":14,"gherkinStepLine":26,"keywordType":"Outcome","textWithKeyword":"Then the response status is 403","stepMatchArguments":[{"group":{"start":23,"value":"403","children":[]},"parameterTypeName":"int"}]}]},
  {"pwTestLine":17,"pickleLine":28,"tags":[],"steps":[{"pwStepLine":18,"gherkinStepLine":29,"keywordType":"Context","textWithKeyword":"Given \"mod\" is logged in","stepMatchArguments":[{"group":{"start":0,"value":"\"mod\"","children":[{"start":1,"value":"mod","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":19,"gherkinStepLine":30,"keywordType":"Action","textWithKeyword":"When \"mod\" POSTs an empty body to \"/api/admin/games/upload\"","stepMatchArguments":[{"group":{"start":0,"value":"\"mod\"","children":[{"start":1,"value":"mod","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":29,"value":"\"/api/admin/games/upload\"","children":[{"start":30,"value":"/api/admin/games/upload","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":20,"gherkinStepLine":31,"keywordType":"Outcome","textWithKeyword":"Then the response status is 403","stepMatchArguments":[{"group":{"start":23,"value":"403","children":[]},"parameterTypeName":"int"}]}]},
  {"pwTestLine":23,"pickleLine":33,"tags":[],"steps":[{"pwStepLine":24,"gherkinStepLine":34,"keywordType":"Context","textWithKeyword":"Given \"publisher-three-wizards\" is logged in","stepMatchArguments":[{"group":{"start":0,"value":"\"publisher-three-wizards\"","children":[{"start":1,"value":"publisher-three-wizards","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":25,"gherkinStepLine":35,"keywordType":"Action","textWithKeyword":"When \"publisher-three-wizards\" attempts to upload an empty bundle for game \"dodecahedron\"","stepMatchArguments":[{"group":{"start":0,"value":"\"publisher-three-wizards\"","children":[{"start":1,"value":"publisher-three-wizards","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":70,"value":"\"dodecahedron\"","children":[{"start":71,"value":"dodecahedron","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":26,"gherkinStepLine":36,"keywordType":"Outcome","textWithKeyword":"Then the response status is one of 403 or 415","stepMatchArguments":[{"group":{"start":30,"value":"403","children":[]},"parameterTypeName":"int"},{"group":{"start":37,"value":"415","children":[]},"parameterTypeName":"int"}]}]},
  {"pwTestLine":29,"pickleLine":38,"tags":[],"steps":[{"pwStepLine":30,"gherkinStepLine":39,"keywordType":"Context","textWithKeyword":"Given \"admin\" is logged in","stepMatchArguments":[{"group":{"start":0,"value":"\"admin\"","children":[{"start":1,"value":"admin","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":31,"gherkinStepLine":40,"keywordType":"Action","textWithKeyword":"When \"admin\" POSTs an empty body to \"/api/admin/games/upload\"","stepMatchArguments":[{"group":{"start":0,"value":"\"admin\"","children":[{"start":1,"value":"admin","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":31,"value":"\"/api/admin/games/upload\"","children":[{"start":32,"value":"/api/admin/games/upload","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":32,"gherkinStepLine":41,"keywordType":"Outcome","textWithKeyword":"Then the response status is one of 400 or 415","stepMatchArguments":[{"group":{"start":30,"value":"400","children":[]},"parameterTypeName":"int"},{"group":{"start":37,"value":"415","children":[]},"parameterTypeName":"int"}]}]},
]; // bdd-data-end