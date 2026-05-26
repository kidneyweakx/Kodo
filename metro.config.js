const path = require('path');
const { getDefaultConfig } = require('expo/metro-config');

const config = getDefaultConfig(__dirname);

const escape = (p) => p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const projectRoot = escape(__dirname + path.sep);

config.resolver.blockList = [
  new RegExp(`^${projectRoot}vendor${escape(path.sep)}.*`),
  new RegExp(`^${projectRoot}nitrogen${escape(path.sep)}generated${escape(path.sep)}.*`),
];

module.exports = config;
