// Generated from: features/makers/dev-fixtures.feature
import { test } from "../../../features/support/fixtures.ts";

test.describe('Maker dev fixtures — Flatland projects pre-seeded for publisher-flatland', () => {

  test('publisher-flatland sees both Flatland fixtures in their makers', async ({ Given, When, Then, And, context, page, user }) => { 
    await Given('a fresh browser context', null, { context, page }); 
    await When('"publisher-flatland" logs in', null, { page, user }); 
    await And('the publisher opens the RPG maker', null, { page }); 
    await Then('the projects list contains "Flatland RPG"', null, { page }); 
    await When('the publisher opens the VN maker', null, { page }); 
    await Then('the projects list contains "Flatland VN"', null, { page }); 
  });

});

// == technical section ==

test.beforeEach('BeforeEach Hooks', ({ $runScenarioHooks }) => $runScenarioHooks('before', {  }));
test.afterEach('AfterEach Hooks', ({ $runScenarioHooks }) => $runScenarioHooks('after', {  }));

test.use({
  $test: [({}, use) => use(test), { scope: 'test', box: true }],
  $uri: [({}, use) => use('features/makers/dev-fixtures.feature'), { scope: 'test', box: true }],
  $bddFileData: [({}, use) => use(bddFileData), { scope: "test", box: true }],
});

const bddFileData = [ // bdd-data-start
  {"pwTestLine":6,"pickleLine":17,"tags":[],"steps":[{"pwStepLine":7,"gherkinStepLine":18,"keywordType":"Context","textWithKeyword":"Given a fresh browser context","stepMatchArguments":[]},{"pwStepLine":8,"gherkinStepLine":19,"keywordType":"Action","textWithKeyword":"When \"publisher-flatland\" logs in","stepMatchArguments":[{"group":{"start":0,"value":"\"publisher-flatland\"","children":[{"start":1,"value":"publisher-flatland","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":9,"gherkinStepLine":20,"keywordType":"Action","textWithKeyword":"And the publisher opens the RPG maker","stepMatchArguments":[]},{"pwStepLine":10,"gherkinStepLine":21,"keywordType":"Outcome","textWithKeyword":"Then the projects list contains \"Flatland RPG\"","stepMatchArguments":[{"group":{"start":27,"value":"\"Flatland RPG\"","children":[{"start":28,"value":"Flatland RPG","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":11,"gherkinStepLine":22,"keywordType":"Action","textWithKeyword":"When the publisher opens the VN maker","stepMatchArguments":[]},{"pwStepLine":12,"gherkinStepLine":23,"keywordType":"Outcome","textWithKeyword":"Then the projects list contains \"Flatland VN\"","stepMatchArguments":[{"group":{"start":27,"value":"\"Flatland VN\"","children":[{"start":28,"value":"Flatland VN","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]}]},
]; // bdd-data-end