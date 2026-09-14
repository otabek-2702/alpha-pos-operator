const { getDefaultConfig } = require('expo/metro-config');

const config = getDefaultConfig(__dirname);
// Leave enough memory for Gradle and the Android emulator on the operator build PC.
config.maxWorkers = 2;
module.exports = config;
