const { getDefaultConfig } = require('expo/metro-config');
const fs = require('node:fs');
const path = require('node:path');

const projectRoot = __dirname;
const workspaceRoot = path.resolve(projectRoot, '../..');
const rnPackage = require.resolve('react-native/package.json', { paths: [projectRoot] });
const reactPackage = require.resolve('react/package.json', { paths: [path.dirname(rnPackage)] });
const reactRoot = path.dirname(reactPackage);
const config = getDefaultConfig(projectRoot);

config.watchFolders = [...new Set([...(config.watchFolders ?? []), workspaceRoot])];
config.resolver.nodeModulesPaths = [
  path.join(projectRoot, 'node_modules'),
  path.join(workspaceRoot, 'node_modules'),
];
config.resolver.extraNodeModules = {
  ...(config.resolver.extraNodeModules ?? {}),
  react: reactRoot,
};

const defaultResolveRequest = config.resolver.resolveRequest;
config.resolver.resolveRequest = (context, moduleName, platform) => {
  const resolved = defaultResolveRequest
    ? defaultResolveRequest(context, moduleName, platform)
    : context.resolveRequest(context, moduleName, platform);
  if (resolved.type !== 'sourceFile') return resolved;

  let realPath;
  try {
    realPath = fs.realpathSync.native(resolved.filePath);
  } catch {
    return resolved;
  }
  const normalized = realPath.split(path.sep).join('/');
  const marker = '/node_modules/react/';
  const index = normalized.lastIndexOf(marker);
  if (index < 0) return resolved;

  const suffix = normalized.slice(index + marker.length);
  const canonical = path.join(reactRoot, suffix);
  return { ...resolved, filePath: canonical };
};

module.exports = config;
