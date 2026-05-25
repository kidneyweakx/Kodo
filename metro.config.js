const { getDefaultConfig } = require('expo/metro-config');

const config = getDefaultConfig(__dirname);

config.resolver.blockList = [/vendor\/.*/, /nitrogen\/generated\/.*/];

module.exports = config;
