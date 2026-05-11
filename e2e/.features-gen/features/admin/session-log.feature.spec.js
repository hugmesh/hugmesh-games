// Generated from: features/admin/session-log.feature
import { test } from "../../../features/support/fixtures.ts";

test.describe('Session participation log', () => {

  test('player sees their own session history at /api/session-logs/me', async ({ Given, When, Then, browser, page, user }) => { 
    await Given('a fresh browser context for "player"', null, { browser, page, user }); 
    await When('"player" requests their own session log', null, { page }); 
    await Then('the response status is 200', null, { page }); 
  });

  test('admin can request anyone\'s session history', async ({ Given, When, Then, browser, page, user }) => { 
    await Given('a fresh browser context for "admin"', null, { browser, page, user }); 
    await When('the caller requests "player1"\'s session log via the admin endpoint', null, { page }); 
    await Then('the response status is 200', null, { page }); 
  });

  test('non-admin player gets 403 from the admin-scoped endpoint', async ({ Given, When, Then, browser, page, user }) => { 
    await Given('a fresh browser context for "player1"', null, { browser, page, user }); 
    await When('the caller requests "player2"\'s session log via the admin endpoint', null, { page }); 
    await Then('the response status is 403', null, { page }); 
  });

});

// == technical section ==

test.beforeEach('BeforeEach Hooks', ({ $runScenarioHooks }) => $runScenarioHooks('before', {  }));
test.afterEach('AfterEach Hooks', ({ $runScenarioHooks }) => $runScenarioHooks('after', {  }));

test.use({
  $test: [({}, use) => use(test), { scope: 'test', box: true }],
  $uri: [({}, use) => use('features/admin/session-log.feature'), { scope: 'test', box: true }],
  $bddFileData: [({}, use) => use(bddFileData), { scope: "test", box: true }],
});

const bddFileData = [ // bdd-data-start
  {"pwTestLine":6,"pickleLine":10,"tags":[],"steps":[{"pwStepLine":7,"gherkinStepLine":11,"keywordType":"Context","textWithKeyword":"Given a fresh browser context for \"player\"","stepMatchArguments":[{"group":{"start":28,"value":"\"player\"","children":[{"start":29,"value":"player","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":8,"gherkinStepLine":12,"keywordType":"Action","textWithKeyword":"When \"player\" requests their own session log","stepMatchArguments":[{"group":{"start":0,"value":"\"player\"","children":[{"start":1,"value":"player","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":9,"gherkinStepLine":13,"keywordType":"Outcome","textWithKeyword":"Then the response status is 200","stepMatchArguments":[{"group":{"start":23,"value":"200","children":[]},"parameterTypeName":"int"}]}]},
  {"pwTestLine":12,"pickleLine":15,"tags":[],"steps":[{"pwStepLine":13,"gherkinStepLine":16,"keywordType":"Context","textWithKeyword":"Given a fresh browser context for \"admin\"","stepMatchArguments":[{"group":{"start":28,"value":"\"admin\"","children":[{"start":29,"value":"admin","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":14,"gherkinStepLine":17,"keywordType":"Action","textWithKeyword":"When the caller requests \"player1\"'s session log via the admin endpoint","stepMatchArguments":[{"group":{"start":20,"value":"\"player1\"","children":[{"start":21,"value":"player1","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":15,"gherkinStepLine":18,"keywordType":"Outcome","textWithKeyword":"Then the response status is 200","stepMatchArguments":[{"group":{"start":23,"value":"200","children":[]},"parameterTypeName":"int"}]}]},
  {"pwTestLine":18,"pickleLine":20,"tags":[],"steps":[{"pwStepLine":19,"gherkinStepLine":21,"keywordType":"Context","textWithKeyword":"Given a fresh browser context for \"player1\"","stepMatchArguments":[{"group":{"start":28,"value":"\"player1\"","children":[{"start":29,"value":"player1","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":20,"gherkinStepLine":22,"keywordType":"Action","textWithKeyword":"When the caller requests \"player2\"'s session log via the admin endpoint","stepMatchArguments":[{"group":{"start":20,"value":"\"player2\"","children":[{"start":21,"value":"player2","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":21,"gherkinStepLine":23,"keywordType":"Outcome","textWithKeyword":"Then the response status is 403","stepMatchArguments":[{"group":{"start":23,"value":"403","children":[]},"parameterTypeName":"int"}]}]},
]; // bdd-data-end