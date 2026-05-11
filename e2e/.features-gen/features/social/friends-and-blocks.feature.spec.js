// Generated from: features/social/friends-and-blocks.feature
import { test } from "../../../features/support/fixtures.ts";

test.describe('Friends graph', () => {

  test('player1 and player2 become mutual friends', async ({ Given, When, Then, And, browser, page, user }) => { 
    await Given('a fresh browser context for "player1"', null, { browser, page, user }); 
    await And('a separate browser context for "player2"', null, { browser, page, user }); 
    await When('"player1" sends a friend request to "player2"', null, { page }); 
    await And('"player2" accepts the friend request from "player1"', null, { page }); 
    await Then('"player1" sees "player2" in their friends list', null, { page }); 
    await And('"player2" sees "player1" in their friends list', null, { page }); 
  });

});

// == technical section ==

test.beforeEach('BeforeEach Hooks', ({ $runScenarioHooks }) => $runScenarioHooks('before', {  }));
test.afterEach('AfterEach Hooks', ({ $runScenarioHooks }) => $runScenarioHooks('after', {  }));

test.use({
  $test: [({}, use) => use(test), { scope: 'test', box: true }],
  $uri: [({}, use) => use('features/social/friends-and-blocks.feature'), { scope: 'test', box: true }],
  $bddFileData: [({}, use) => use(bddFileData), { scope: "test", box: true }],
});

const bddFileData = [ // bdd-data-start
  {"pwTestLine":6,"pickleLine":12,"tags":[],"steps":[{"pwStepLine":7,"gherkinStepLine":13,"keywordType":"Context","textWithKeyword":"Given a fresh browser context for \"player1\"","stepMatchArguments":[{"group":{"start":28,"value":"\"player1\"","children":[{"start":29,"value":"player1","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":8,"gherkinStepLine":14,"keywordType":"Context","textWithKeyword":"And a separate browser context for \"player2\"","stepMatchArguments":[{"group":{"start":31,"value":"\"player2\"","children":[{"start":32,"value":"player2","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":9,"gherkinStepLine":15,"keywordType":"Action","textWithKeyword":"When \"player1\" sends a friend request to \"player2\"","stepMatchArguments":[{"group":{"start":0,"value":"\"player1\"","children":[{"start":1,"value":"player1","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":36,"value":"\"player2\"","children":[{"start":37,"value":"player2","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":10,"gherkinStepLine":16,"keywordType":"Action","textWithKeyword":"And \"player2\" accepts the friend request from \"player1\"","stepMatchArguments":[{"group":{"start":0,"value":"\"player2\"","children":[{"start":1,"value":"player2","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":42,"value":"\"player1\"","children":[{"start":43,"value":"player1","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":11,"gherkinStepLine":17,"keywordType":"Outcome","textWithKeyword":"Then \"player1\" sees \"player2\" in their friends list","stepMatchArguments":[{"group":{"start":0,"value":"\"player1\"","children":[{"start":1,"value":"player1","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":15,"value":"\"player2\"","children":[{"start":16,"value":"player2","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":12,"gherkinStepLine":18,"keywordType":"Outcome","textWithKeyword":"And \"player2\" sees \"player1\" in their friends list","stepMatchArguments":[{"group":{"start":0,"value":"\"player2\"","children":[{"start":1,"value":"player2","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"},{"group":{"start":15,"value":"\"player1\"","children":[{"start":16,"value":"player1","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]}]},
]; // bdd-data-end