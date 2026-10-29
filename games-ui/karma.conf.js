// Karma configuration for the hugmesh-games games-ui native-federation remote.
// Mirrors the org-wide template (hugmesh-user-ui): Jasmine specs run on Chrome
// headless via the ChromeHeadlessCI launcher (--no-sandbox
// --disable-dev-shm-usage) in CI.
module.exports = function (config) {
  config.set({
    basePath: '',
    frameworks: ['jasmine', '@angular-devkit/build-angular'],
    plugins: [
      require('karma-jasmine'),
      require('karma-chrome-launcher'),
      require('karma-jasmine-html-reporter'),
      require('karma-coverage'),
      require('@angular-devkit/build-angular/plugins/karma'),
      require('karma-junit-reporter'),
    ],
    client: {
      jasmine: {},
      clearContext: false,
    },
    coverageReporter: {
      dir: require('path').join(__dirname, './coverage/games'),
      subdir: '.',
      reporters: [{ type: 'html' }, { type: 'lcovonly' }, { type: 'text-summary' }],
    },
    reporters: ['progress', 'kjhtml', 'junit'],
    junitReporter: { outputDir: 'test-results/', outputFile: 'results.xml', useBrowserName: false },
    port: 9876,
    colors: true,
    logLevel: config.LOG_INFO,
    autoWatch: false,
    browsers: ['ChromeHeadless', 'ChromeHeadlessCI'],
    customLaunchers: {
      ChromeHeadlessCI: {
        base: 'ChromeHeadless',
        flags: ['--no-sandbox', '--disable-dev-shm-usage'],
      },
    },
    singleRun: true,
    restartOnFileChange: true,
  });
};
