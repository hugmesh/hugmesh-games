// Generated from: features/security/mod-grant-gates.feature
import { test } from "../../../features/support/fixtures.ts";

test.describe('/api/admin/role-grants + /api/publisher/grant-mod — auth + scope gates', () => {

  test('unauthenticated grant-mod via admin endpoint is rejected — 401', async ({ When, Then, page }) => { 
    await When('the unauthenticated client POSTs to "/api/admin/role-grants/grant-mod"', null, { page }); 
    await Then('the response status is 401', null, { page }); 
  });

  test('a plain player cannot grant mod via the admin endpoint — 403', async ({ Given, When, Then, context, page, user }) => { 
    await Given('"player" is logged in', null, { context, page, user }); 
    await When('"player" POSTs grant-mod JSON to "/api/admin/role-grants/grant-mod" targeting "tw-player-002" on game "three-wizards"', null, { page }); 
    await Then('the response status is 403', null, { page }); 
  });

  test('a publisher cannot use the admin endpoint — 403', async ({ Given, When, Then, context, page, user }) => { 
    await Given('"publisher-three-wizards" is logged in', null, { context, page, user }); 
    await When('"publisher-three-wizards" POSTs grant-mod JSON to "/api/admin/role-grants/grant-mod" targeting "tw-player-002" on game "three-wizards"', null, { page }); 
    await Then('the response status is 403', null, { page }); 
  });

  test('a publisher cannot grant mod on a game they don\'t own — 403', async ({ Given, When, Then, context, page, user }) => { 
    await Given('"publisher-three-wizards" is logged in', null, { context, page, user }); 
    await When('"publisher-three-wizards" POSTs grant-mod JSON to "/api/publisher/grant-mod" targeting "tw-player-002" on game "dodecahedron"', null, { page }); 
    await Then('the response status is 403', null, { page }); 
  });

  test('a plain player cannot use the publisher endpoint either — 403', async ({ Given, When, Then, context, page, user }) => { 
    await Given('"player" is logged in', null, { context, page, user }); 
    await When('"player" POSTs grant-mod JSON to "/api/publisher/grant-mod" targeting "tw-player-002" on game "three-wizards"', null, { page }); 
    await Then('the response status is 403', null, { page }); 
  });

});

// == technical section ==

test.beforeEach('BeforeEach Hooks', ({ $runScenarioHooks }) => $runScenarioHooks('before', {  }));
test.afterEach('AfterEach Hooks', ({ $runScenarioHooks }) => $runScenarioHooks('after', {  }));

test.use({
  $test: [({}, use) => use(test), { scope: 'test', box: true }],
  $uri: [({}, use) => use('features/security/mod-grant-gates.feature'), { scope: 'test', box: true }],
  $bddFileData: [({}, use) => use(bddFileData), { scope: "test", box: true }],
});

const bddFileData = [ // bdd-data-start
  {"pwTestLine":6,"pickleLine":20,"tags":[],"steps":[{"pwStepLine":7,"gherkinStepLine":21,"keywordType":"Action","textWithKeyword":"When the unauthenticated client POSTs to \"/api/admin/role-grants/grant-mod\"","stepMatchArguments":[{"group":{"start":36,"value":"\"/api/admin/role-grants/grant-mod\"","children":[{"start":37,"value":"/api/admin/role-grants/grant-mod","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":8,"gherkinStepLine":22,"keywordType":"Outcome","textWithKeyword":"Then the response status is 401","stepMatchArguments":[{"group":{"start":23,"value":"401","children":[]},"parameterTypeName":"int"}]}]},
  {"pwTestLine":11,"pickleLine":24,"tags":[],"steps":[{"pwStepLine":12,"gherkinStepLine":25,"keywordType":"Context","textWithKeyword":"Given \"player\" is logged in","stepMatchArguments":[{"group":{"start":0,"value":"\"player\"","children":[{"start":1,"value":"player","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":13,"gherkinStepLine":26,"keywordType":"Action","textWithKeyword":"When \"player\" POSTs grant-mod JSON to \"/api/admin/role-grants/grant-mod\" targeting \"tw-player-002\" on game \"three-wizards\"","stepMatchArguments":[{"group":{"start":0,"value":"\"player\"","children":[{"start":1,"value":"player","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":33,"value":"\"/api/admin/role-grants/grant-mod\"","children":[{"start":34,"value":"/api/admin/role-grants/grant-mod","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":78,"value":"\"tw-player-002\"","children":[{"start":79,"value":"tw-player-002","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":102,"value":"\"three-wizards\"","children":[{"start":103,"value":"three-wizards","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":14,"gherkinStepLine":27,"keywordType":"Outcome","textWithKeyword":"Then the response status is 403","stepMatchArguments":[{"group":{"start":23,"value":"403","children":[]},"parameterTypeName":"int"}]}]},
  {"pwTestLine":17,"pickleLine":29,"tags":[],"steps":[{"pwStepLine":18,"gherkinStepLine":30,"keywordType":"Context","textWithKeyword":"Given \"publisher-three-wizards\" is logged in","stepMatchArguments":[{"group":{"start":0,"value":"\"publisher-three-wizards\"","children":[{"start":1,"value":"publisher-three-wizards","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":19,"gherkinStepLine":31,"keywordType":"Action","textWithKeyword":"When \"publisher-three-wizards\" POSTs grant-mod JSON to \"/api/admin/role-grants/grant-mod\" targeting \"tw-player-002\" on game \"three-wizards\"","stepMatchArguments":[{"group":{"start":0,"value":"\"publisher-three-wizards\"","children":[{"start":1,"value":"publisher-three-wizards","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":50,"value":"\"/api/admin/role-grants/grant-mod\"","children":[{"start":51,"value":"/api/admin/role-grants/grant-mod","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":95,"value":"\"tw-player-002\"","children":[{"start":96,"value":"tw-player-002","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":119,"value":"\"three-wizards\"","children":[{"start":120,"value":"three-wizards","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":20,"gherkinStepLine":32,"keywordType":"Outcome","textWithKeyword":"Then the response status is 403","stepMatchArguments":[{"group":{"start":23,"value":"403","children":[]},"parameterTypeName":"int"}]}]},
  {"pwTestLine":23,"pickleLine":34,"tags":[],"steps":[{"pwStepLine":24,"gherkinStepLine":35,"keywordType":"Context","textWithKeyword":"Given \"publisher-three-wizards\" is logged in","stepMatchArguments":[{"group":{"start":0,"value":"\"publisher-three-wizards\"","children":[{"start":1,"value":"publisher-three-wizards","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":25,"gherkinStepLine":36,"keywordType":"Action","textWithKeyword":"When \"publisher-three-wizards\" POSTs grant-mod JSON to \"/api/publisher/grant-mod\" targeting \"tw-player-002\" on game \"dodecahedron\"","stepMatchArguments":[{"group":{"start":0,"value":"\"publisher-three-wizards\"","children":[{"start":1,"value":"publisher-three-wizards","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":50,"value":"\"/api/publisher/grant-mod\"","children":[{"start":51,"value":"/api/publisher/grant-mod","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":87,"value":"\"tw-player-002\"","children":[{"start":88,"value":"tw-player-002","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":111,"value":"\"dodecahedron\"","children":[{"start":112,"value":"dodecahedron","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":26,"gherkinStepLine":37,"keywordType":"Outcome","textWithKeyword":"Then the response status is 403","stepMatchArguments":[{"group":{"start":23,"value":"403","children":[]},"parameterTypeName":"int"}]}]},
  {"pwTestLine":29,"pickleLine":39,"tags":[],"steps":[{"pwStepLine":30,"gherkinStepLine":40,"keywordType":"Context","textWithKeyword":"Given \"player\" is logged in","stepMatchArguments":[{"group":{"start":0,"value":"\"player\"","children":[{"start":1,"value":"player","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":31,"gherkinStepLine":41,"keywordType":"Action","textWithKeyword":"When \"player\" POSTs grant-mod JSON to \"/api/publisher/grant-mod\" targeting \"tw-player-002\" on game \"three-wizards\"","stepMatchArguments":[{"group":{"start":0,"value":"\"player\"","children":[{"start":1,"value":"player","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":33,"value":"\"/api/publisher/grant-mod\"","children":[{"start":34,"value":"/api/publisher/grant-mod","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":70,"value":"\"tw-player-002\"","children":[{"start":71,"value":"tw-player-002","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":94,"value":"\"three-wizards\"","children":[{"start":95,"value":"three-wizards","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":32,"gherkinStepLine":42,"keywordType":"Outcome","textWithKeyword":"Then the response status is 403","stepMatchArguments":[{"group":{"start":23,"value":"403","children":[]},"parameterTypeName":"int"}]}]},
]; // bdd-data-end