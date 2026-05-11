// Generated from: features/lobby/session-visibility.feature
import { test } from "../../../features/support/fixtures.ts";

test.describe('Session visibility tiers', () => {

  test('friends-tier session is hidden from strangers', async ({ Given, When, Then, And, browser, page, user }) => { 
    await Given('a fresh browser context for "player1"', null, { browser, page, user }); 
    await And('a separate browser context for "player3"', null, { browser, page, user }); 
    await And('"player1" creates a friends-only session named "fr-session" in Three Wizards', null, { page }); 
    await When('"player3" opens the Three Wizards lobby', null, { page }); 
    await Then('the lobby does not show the session named "fr-session"', null, { page }); 
  });

  test('private session is hidden from everyone except the creator', async ({ Given, When, Then, And, But, browser, page, user }) => { 
    await Given('a fresh browser context for "player1"', null, { browser, page, user }); 
    await And('a separate browser context for "player2"', null, { browser, page, user }); 
    await And('"player1" creates a private session named "priv-session" in Three Wizards', null, { page }); 
    await When('"player2" opens the Three Wizards lobby', null, { page }); 
    await Then('the lobby does not show the session named "priv-session"', null, { page }); 
    await But('"player1" still sees the session named "priv-session" in their own lobby', null, { page }); 
  });

});

// == technical section ==

test.beforeEach('BeforeEach Hooks', ({ $runScenarioHooks }) => $runScenarioHooks('before', {  }));
test.afterEach('AfterEach Hooks', ({ $runScenarioHooks }) => $runScenarioHooks('after', {  }));

test.use({
  $test: [({}, use) => use(test), { scope: 'test', box: true }],
  $uri: [({}, use) => use('features/lobby/session-visibility.feature'), { scope: 'test', box: true }],
  $bddFileData: [({}, use) => use(bddFileData), { scope: "test", box: true }],
});

const bddFileData = [ // bdd-data-start
  {"pwTestLine":6,"pickleLine":11,"tags":[],"steps":[{"pwStepLine":7,"gherkinStepLine":12,"keywordType":"Context","textWithKeyword":"Given a fresh browser context for \"player1\"","stepMatchArguments":[{"group":{"start":28,"value":"\"player1\"","children":[{"start":29,"value":"player1","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":8,"gherkinStepLine":13,"keywordType":"Context","textWithKeyword":"And a separate browser context for \"player3\"","stepMatchArguments":[{"group":{"start":31,"value":"\"player3\"","children":[{"start":32,"value":"player3","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":9,"gherkinStepLine":14,"keywordType":"Context","textWithKeyword":"And \"player1\" creates a friends-only session named \"fr-session\" in Three Wizards","stepMatchArguments":[{"group":{"start":0,"value":"\"player1\"","children":[{"start":1,"value":"player1","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":47,"value":"\"fr-session\"","children":[{"start":48,"value":"fr-session","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":10,"gherkinStepLine":15,"keywordType":"Action","textWithKeyword":"When \"player3\" opens the Three Wizards lobby","stepMatchArguments":[{"group":{"start":0,"value":"\"player3\"","children":[{"start":1,"value":"player3","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":11,"gherkinStepLine":16,"keywordType":"Outcome","textWithKeyword":"Then the lobby does not show the session named \"fr-session\"","stepMatchArguments":[{"group":{"start":42,"value":"\"fr-session\"","children":[{"start":43,"value":"fr-session","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]}]},
  {"pwTestLine":14,"pickleLine":18,"tags":[],"steps":[{"pwStepLine":15,"gherkinStepLine":19,"keywordType":"Context","textWithKeyword":"Given a fresh browser context for \"player1\"","stepMatchArguments":[{"group":{"start":28,"value":"\"player1\"","children":[{"start":29,"value":"player1","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":16,"gherkinStepLine":20,"keywordType":"Context","textWithKeyword":"And a separate browser context for \"player2\"","stepMatchArguments":[{"group":{"start":31,"value":"\"player2\"","children":[{"start":32,"value":"player2","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":17,"gherkinStepLine":21,"keywordType":"Context","textWithKeyword":"And \"player1\" creates a private session named \"priv-session\" in Three Wizards","stepMatchArguments":[{"group":{"start":0,"value":"\"player1\"","children":[{"start":1,"value":"player1","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":42,"value":"\"priv-session\"","children":[{"start":43,"value":"priv-session","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":18,"gherkinStepLine":22,"keywordType":"Action","textWithKeyword":"When \"player2\" opens the Three Wizards lobby","stepMatchArguments":[{"group":{"start":0,"value":"\"player2\"","children":[{"start":1,"value":"player2","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":19,"gherkinStepLine":23,"keywordType":"Outcome","textWithKeyword":"Then the lobby does not show the session named \"priv-session\"","stepMatchArguments":[{"group":{"start":42,"value":"\"priv-session\"","children":[{"start":43,"value":"priv-session","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":20,"gherkinStepLine":24,"keywordType":"Outcome","textWithKeyword":"But \"player1\" still sees the session named \"priv-session\" in their own lobby","stepMatchArguments":[{"group":{"start":0,"value":"\"player1\"","children":[{"start":1,"value":"player1","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":39,"value":"\"priv-session\"","children":[{"start":40,"value":"priv-session","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]}]},
]; // bdd-data-end