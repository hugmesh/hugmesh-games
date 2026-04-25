// Karma config for Angular unit tests. `npm test` runs this once via
// ChromeHeadlessCI; the `make test-frontend` target shells out to a Docker
// container that already has Chromium installed (Playwright's image), so
// the host doesn't need a browser.
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
    ],
    client: { jasmine: {}, clearContext: false },
    jasmineHtmlReporter: { suppressAll: true },
    coverageReporter: {
      dir: require('path').join(__dirname, './coverage/three-wizards-frontend'),
      subdir: '.',
      reporters: [{ type: 'text-summary' }],
    },
    reporters: ['progress', 'kjhtml'],
    browsers: ['ChromeHeadlessCI'],
    customLaunchers: {
      // No-sandbox + no-dev-shm-usage so this works inside Docker.
      ChromeHeadlessCI: {
        base: 'ChromeHeadless',
        flags: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'],
      },
    },
    restartOnFileChange: true,
    singleRun: true,
  });
};
