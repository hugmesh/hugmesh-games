// Generated from: features/auth/per-role-login.feature
import { test } from "../../../features/support/fixtures.ts";

test.describe('Per-role login matrix', () => {

  test('player logs in and lands on the catalog', async ({ Given, When, Then, context, page, user }) => { 
    await Given('a fresh browser context', null, { context, page }); 
    await When('"player" logs in', null, { page, user }); 
    await Then('the catalog renders at least one game card', null, { page }); 
  });

  test('player1 logs in and lands on the catalog', async ({ Given, When, Then, context, page, user }) => { 
    await Given('a fresh browser context', null, { context, page }); 
    await When('"player1" logs in', null, { page, user }); 
    await Then('the catalog renders at least one game card', null, { page }); 
  });

  test('player2 logs in and lands on the catalog', async ({ Given, When, Then, context, page, user }) => { 
    await Given('a fresh browser context', null, { context, page }); 
    await When('"player2" logs in', null, { page, user }); 
    await Then('the catalog renders at least one game card', null, { page }); 
  });

  test('player3 logs in and lands on the catalog', async ({ Given, When, Then, context, page, user }) => { 
    await Given('a fresh browser context', null, { context, page }); 
    await When('"player3" logs in', null, { page, user }); 
    await Then('the catalog renders at least one game card', null, { page }); 
  });

  test('admin logs in and lands on the catalog', async ({ Given, When, Then, context, page, user }) => { 
    await Given('a fresh browser context', null, { context, page }); 
    await When('"admin" logs in', null, { page, user }); 
    await Then('the catalog renders at least one game card', null, { page }); 
  });

  test('mod logs in and lands on the catalog', async ({ Given, When, Then, context, page, user }) => { 
    await Given('a fresh browser context', null, { context, page }); 
    await When('"mod" logs in', null, { page, user }); 
    await Then('the catalog renders at least one game card', null, { page }); 
  });

  test('mod-three-wizards logs in and lands on the catalog', async ({ Given, When, Then, context, page, user }) => { 
    await Given('a fresh browser context', null, { context, page }); 
    await When('"mod-three-wizards" logs in', null, { page, user }); 
    await Then('the catalog renders at least one game card', null, { page }); 
  });

  test('mod-dodecahedron logs in and lands on the catalog', async ({ Given, When, Then, context, page, user }) => { 
    await Given('a fresh browser context', null, { context, page }); 
    await When('"mod-dodecahedron" logs in', null, { page, user }); 
    await Then('the catalog renders at least one game card', null, { page }); 
  });

  test('publisher logs in and lands on the catalog', async ({ Given, When, Then, context, page, user }) => { 
    await Given('a fresh browser context', null, { context, page }); 
    await When('"publisher" logs in', null, { page, user }); 
    await Then('the catalog renders at least one game card', null, { page }); 
  });

  test('publisher-three-wizards logs in and lands on the catalog', async ({ Given, When, Then, context, page, user }) => { 
    await Given('a fresh browser context', null, { context, page }); 
    await When('"publisher-three-wizards" logs in', null, { page, user }); 
    await Then('the catalog renders at least one game card', null, { page }); 
  });

  test('publisher-dodecahedron logs in and lands on the catalog', async ({ Given, When, Then, context, page, user }) => { 
    await Given('a fresh browser context', null, { context, page }); 
    await When('"publisher-dodecahedron" logs in', null, { page, user }); 
    await Then('the catalog renders at least one game card', null, { page }); 
  });

  test('publisher-flatland logs in and lands on the catalog', async ({ Given, When, Then, context, page, user }) => { 
    await Given('a fresh browser context', null, { context, page }); 
    await When('"publisher-flatland" logs in', null, { page, user }); 
    await Then('the catalog renders at least one game card', null, { page }); 
  });

});

// == technical section ==

test.beforeEach('BeforeEach Hooks', ({ $runScenarioHooks }) => $runScenarioHooks('before', {  }));
test.afterEach('AfterEach Hooks', ({ $runScenarioHooks }) => $runScenarioHooks('after', {  }));

test.use({
  $test: [({}, use) => use(test), { scope: 'test', box: true }],
  $uri: [({}, use) => use('features/auth/per-role-login.feature'), { scope: 'test', box: true }],
  $bddFileData: [({}, use) => use(bddFileData), { scope: "test", box: true }],
});

const bddFileData = [ // bdd-data-start
  {"pwTestLine":6,"pickleLine":15,"tags":[],"steps":[{"pwStepLine":7,"gherkinStepLine":16,"keywordType":"Context","textWithKeyword":"Given a fresh browser context","stepMatchArguments":[]},{"pwStepLine":8,"gherkinStepLine":17,"keywordType":"Action","textWithKeyword":"When \"player\" logs in","stepMatchArguments":[{"group":{"start":0,"value":"\"player\"","children":[{"start":1,"value":"player","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":9,"gherkinStepLine":18,"keywordType":"Outcome","textWithKeyword":"Then the catalog renders at least one game card","stepMatchArguments":[]}]},
  {"pwTestLine":12,"pickleLine":20,"tags":[],"steps":[{"pwStepLine":13,"gherkinStepLine":21,"keywordType":"Context","textWithKeyword":"Given a fresh browser context","stepMatchArguments":[]},{"pwStepLine":14,"gherkinStepLine":22,"keywordType":"Action","textWithKeyword":"When \"player1\" logs in","stepMatchArguments":[{"group":{"start":0,"value":"\"player1\"","children":[{"start":1,"value":"player1","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":15,"gherkinStepLine":23,"keywordType":"Outcome","textWithKeyword":"Then the catalog renders at least one game card","stepMatchArguments":[]}]},
  {"pwTestLine":18,"pickleLine":25,"tags":[],"steps":[{"pwStepLine":19,"gherkinStepLine":26,"keywordType":"Context","textWithKeyword":"Given a fresh browser context","stepMatchArguments":[]},{"pwStepLine":20,"gherkinStepLine":27,"keywordType":"Action","textWithKeyword":"When \"player2\" logs in","stepMatchArguments":[{"group":{"start":0,"value":"\"player2\"","children":[{"start":1,"value":"player2","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":21,"gherkinStepLine":28,"keywordType":"Outcome","textWithKeyword":"Then the catalog renders at least one game card","stepMatchArguments":[]}]},
  {"pwTestLine":24,"pickleLine":30,"tags":[],"steps":[{"pwStepLine":25,"gherkinStepLine":31,"keywordType":"Context","textWithKeyword":"Given a fresh browser context","stepMatchArguments":[]},{"pwStepLine":26,"gherkinStepLine":32,"keywordType":"Action","textWithKeyword":"When \"player3\" logs in","stepMatchArguments":[{"group":{"start":0,"value":"\"player3\"","children":[{"start":1,"value":"player3","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":27,"gherkinStepLine":33,"keywordType":"Outcome","textWithKeyword":"Then the catalog renders at least one game card","stepMatchArguments":[]}]},
  {"pwTestLine":30,"pickleLine":35,"tags":[],"steps":[{"pwStepLine":31,"gherkinStepLine":36,"keywordType":"Context","textWithKeyword":"Given a fresh browser context","stepMatchArguments":[]},{"pwStepLine":32,"gherkinStepLine":37,"keywordType":"Action","textWithKeyword":"When \"admin\" logs in","stepMatchArguments":[{"group":{"start":0,"value":"\"admin\"","children":[{"start":1,"value":"admin","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":33,"gherkinStepLine":38,"keywordType":"Outcome","textWithKeyword":"Then the catalog renders at least one game card","stepMatchArguments":[]}]},
  {"pwTestLine":36,"pickleLine":40,"tags":[],"steps":[{"pwStepLine":37,"gherkinStepLine":41,"keywordType":"Context","textWithKeyword":"Given a fresh browser context","stepMatchArguments":[]},{"pwStepLine":38,"gherkinStepLine":42,"keywordType":"Action","textWithKeyword":"When \"mod\" logs in","stepMatchArguments":[{"group":{"start":0,"value":"\"mod\"","children":[{"start":1,"value":"mod","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":39,"gherkinStepLine":43,"keywordType":"Outcome","textWithKeyword":"Then the catalog renders at least one game card","stepMatchArguments":[]}]},
  {"pwTestLine":42,"pickleLine":45,"tags":[],"steps":[{"pwStepLine":43,"gherkinStepLine":46,"keywordType":"Context","textWithKeyword":"Given a fresh browser context","stepMatchArguments":[]},{"pwStepLine":44,"gherkinStepLine":47,"keywordType":"Action","textWithKeyword":"When \"mod-three-wizards\" logs in","stepMatchArguments":[{"group":{"start":0,"value":"\"mod-three-wizards\"","children":[{"start":1,"value":"mod-three-wizards","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":45,"gherkinStepLine":48,"keywordType":"Outcome","textWithKeyword":"Then the catalog renders at least one game card","stepMatchArguments":[]}]},
  {"pwTestLine":48,"pickleLine":50,"tags":[],"steps":[{"pwStepLine":49,"gherkinStepLine":51,"keywordType":"Context","textWithKeyword":"Given a fresh browser context","stepMatchArguments":[]},{"pwStepLine":50,"gherkinStepLine":52,"keywordType":"Action","textWithKeyword":"When \"mod-dodecahedron\" logs in","stepMatchArguments":[{"group":{"start":0,"value":"\"mod-dodecahedron\"","children":[{"start":1,"value":"mod-dodecahedron","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":51,"gherkinStepLine":53,"keywordType":"Outcome","textWithKeyword":"Then the catalog renders at least one game card","stepMatchArguments":[]}]},
  {"pwTestLine":54,"pickleLine":55,"tags":[],"steps":[{"pwStepLine":55,"gherkinStepLine":56,"keywordType":"Context","textWithKeyword":"Given a fresh browser context","stepMatchArguments":[]},{"pwStepLine":56,"gherkinStepLine":57,"keywordType":"Action","textWithKeyword":"When \"publisher\" logs in","stepMatchArguments":[{"group":{"start":0,"value":"\"publisher\"","children":[{"start":1,"value":"publisher","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":57,"gherkinStepLine":58,"keywordType":"Outcome","textWithKeyword":"Then the catalog renders at least one game card","stepMatchArguments":[]}]},
  {"pwTestLine":60,"pickleLine":60,"tags":[],"steps":[{"pwStepLine":61,"gherkinStepLine":61,"keywordType":"Context","textWithKeyword":"Given a fresh browser context","stepMatchArguments":[]},{"pwStepLine":62,"gherkinStepLine":62,"keywordType":"Action","textWithKeyword":"When \"publisher-three-wizards\" logs in","stepMatchArguments":[{"group":{"start":0,"value":"\"publisher-three-wizards\"","children":[{"start":1,"value":"publisher-three-wizards","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":63,"gherkinStepLine":63,"keywordType":"Outcome","textWithKeyword":"Then the catalog renders at least one game card","stepMatchArguments":[]}]},
  {"pwTestLine":66,"pickleLine":65,"tags":[],"steps":[{"pwStepLine":67,"gherkinStepLine":66,"keywordType":"Context","textWithKeyword":"Given a fresh browser context","stepMatchArguments":[]},{"pwStepLine":68,"gherkinStepLine":67,"keywordType":"Action","textWithKeyword":"When \"publisher-dodecahedron\" logs in","stepMatchArguments":[{"group":{"start":0,"value":"\"publisher-dodecahedron\"","children":[{"start":1,"value":"publisher-dodecahedron","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":69,"gherkinStepLine":68,"keywordType":"Outcome","textWithKeyword":"Then the catalog renders at least one game card","stepMatchArguments":[]}]},
  {"pwTestLine":72,"pickleLine":70,"tags":[],"steps":[{"pwStepLine":73,"gherkinStepLine":71,"keywordType":"Context","textWithKeyword":"Given a fresh browser context","stepMatchArguments":[]},{"pwStepLine":74,"gherkinStepLine":72,"keywordType":"Action","textWithKeyword":"When \"publisher-flatland\" logs in","stepMatchArguments":[{"group":{"start":0,"value":"\"publisher-flatland\"","children":[{"start":1,"value":"publisher-flatland","children":[{"children":[]}]},{"children":[{"children":[]}]}]},"parameterTypeName":"string"}]},{"pwStepLine":75,"gherkinStepLine":73,"keywordType":"Outcome","textWithKeyword":"Then the catalog renders at least one game card","stepMatchArguments":[]}]},
]; // bdd-data-end