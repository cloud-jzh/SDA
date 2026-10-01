import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const crate = resolve(root, 'crates/sda-native');
const android = resolve(root, 'apps/mobile/android');
const assetDir = resolve(android, 'app/src/main/assets/hrtf');
const webHrtf = resolve(root, 'apps/web/public/hrtf');
const env = { ...process.env };
env.ANDROID_NDK_HOME ??= env.ANDROID_NDK_ROOT ?? 'D:/sdk/ndk/android-ndk-r30';
env.ANDROID_HOME ??= env.ANDROID_SDK_ROOT ?? 'C:/Users/jzh/AppData/Local/Android/Sdk';
env.JAVA_HOME ??= 'D:/sdk/jdk/jdk21';
for (const name of ['ANDROID_NDK_HOME', 'ANDROID_HOME', 'JAVA_HOME']) {
  if (!existsSync(env[name])) throw new Error(`${name} path does not exist: ${env[name]}`);
}
env.MACINDECODE_AC4_SPEC_DIR ??= resolve(root, 'tmp/MacinDecode-AC4-Core/spec');
const toolchain = env.SDA_RUST_TOOLCHAIN ?? '1.98.0';
const requested = process.argv.slice(2).find((arg) => arg.startsWith('--abi='))?.slice(6) ?? 'arm64-v8a,x86_64';
const verifyOnly = process.argv.includes('--verify-only');
const abiTargets = { 'arm64-v8a': 'aarch64-linux-android', x86_64: 'x86_64-linux-android' };
const abis = [...new Set(requested.split(',').filter(Boolean))];
if (!abis.length || abis.some((abi) => !abiTargets[abi])) throw new Error(`Unsupported ABI list: ${requested}`);
const run = (command, args, cwd, extraEnv = env) => {
  const result = spawnSync(command, args, { cwd, env: extraEnv, stdio: 'inherit', shell: process.platform === 'win32', timeout: 600000 });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} ${args.join(' ')} failed (${result.status})`);
};
const output = (command, args, cwd, extraEnv = env) => {
  const result = spawnSync(command, args, { cwd, env: extraEnv, encoding: 'utf8', shell: process.platform === 'win32', timeout: 30000 });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(result.stderr || `${command} failed (${result.status})`);
  return result.stdout.trim();
};
const hash = (file) => createHash('sha256').update(readFileSync(file)).digest('hex');
const cargoMeta = JSON.parse(output('cargo', [`+${toolchain}`, 'metadata', '--no-deps', '--format-version', '1'], crate));
const targetDir = cargoMeta.target_directory;
const libTarget = cargoMeta.packages.find((pkg) => pkg.name === 'sda-native')?.targets.find((target) => target.name === 'sda_native' && target.crate_types.includes('cdylib'));
if (!libTarget) throw new Error('cargo metadata did not expose the sda_native cdylib target');

// Preserve existing app HRTF files; refuse conflicts and only fill missing defaults.
const webFiles = readdirSync(webHrtf).filter((name) => name === 'hrtf-set.json' || name.endsWith('.f32'));
if (!webFiles.includes('hrtf-set.json') || !webFiles.some((name) => name.endsWith('_dry.f32')) || !webFiles.some((name) => name.endsWith('_wet.f32'))) {
  throw new Error('Web HRTF default manifest or FIR set is incomplete');
}
mkdirSync(assetDir, { recursive: true });
for (const name of webFiles) {
  const source = join(webHrtf, name);
  const destination = join(assetDir, name);
  if (!existsSync(destination)) copyFileSync(source, destination);
  else if (hash(source) !== hash(destination)) throw new Error(`HRTF conflict at ${destination}; refusing to overwrite`);
}
const manifest = JSON.parse(readFileSync(join(assetDir, 'hrtf-set.json'), 'utf8'));
const references = new Set((manifest.positions ?? []).flatMap((position) => Object.values(position).filter((value) => typeof value === 'string' && value.endsWith('.f32'))));
const missingFir = [...references].filter((name) => !existsSync(join(assetDir, name)));
if (missingFir.length) throw new Error(`KU100 manifest references missing FIR files: ${missingFir.join(', ')}`);

const native = {};
if (!verifyOnly) for (const abi of abis) {
  run('cargo', [`+${toolchain}`, 'ndk', '-t', abi, '--platform', '26', 'build', '--release', '--no-default-features'], crate);
  const libraryFile = `lib${libTarget.name}.so`;
  const source = resolve(targetDir, abiTargets[abi], 'release', libraryFile);
  if (!existsSync(source)) throw new Error(`Successful cargo build did not produce ${source}`);
  const staged = resolve(android, 'app/src/main/jniLibs', abi, libraryFile);
  mkdirSync(dirname(staged), { recursive: true });
  copyFileSync(source, staged);
  if (hash(source) !== hash(staged)) throw new Error(`${abi} native staging checksum mismatch`);
  native[abi] = { source, staged, sha256: hash(staged), size: statSync(staged).size };
}

const gradle = env.SDA_GRADLE ?? resolve(android, 'gradlew.bat');
const gradleArgs = ['assembleRelease', '--no-daemon', '--console=plain', '--max-workers=1', '-Pkotlin.compiler.execution.strategy=in-process'];
if (!verifyOnly) run(gradle, gradleArgs, android);
const apk = resolve(android, 'app/build/outputs/apk/release/app-release.apk');
if (!existsSync(apk)) throw new Error(`Gradle succeeded but APK is absent: ${apk}`);
const zipEntries = output('jar', ['tf', apk], root).split(/\r?\n/);
const nativeEntries = {};
for (const abi of Object.keys(abiTargets)) {
  const entry = `lib/${abi}/lib${libTarget.name}.so`;
  if (zipEntries.includes(entry)) nativeEntries[abi] = entry;
}
for (const abi of abis) if (!nativeEntries[abi]) throw new Error(`APK is missing requested ABI ${abi}`);
const requiredAbiLibraries = ['libsda_native.so', 'libexpo-gl.so', 'libexpo-modules-core.so', 'libhermes.so', 'libreactnative.so', 'libfbjni.so', 'libjsi.so'];
const abiLibraries = {};
for (const abi of abis) {
  const missing = requiredAbiLibraries.filter((lib) => !zipEntries.includes(`lib/${abi}/${lib}`));
  if (missing.length) throw new Error(`APK ${abi} is missing native libraries: ${missing.join(', ')}`);
  abiLibraries[abi] = requiredAbiLibraries;
}
const bundleEntry = zipEntries.find((entry) => entry === 'assets/index.android.bundle');
if (!bundleEntry) throw new Error('APK does not contain the embedded React Native JS bundle');
const llvmBin = resolve(env.ANDROID_NDK_HOME, 'toolchains/llvm/prebuilt/windows-x86_64/bin');
const extract = (entry, destination) => {
  const result = spawnSync(resolve(env.ANDROID_HOME, 'build-tools/35.0.0/zipalign.exe'), ['-c', '-P', '16', '4', apk], { env, encoding: 'utf8', shell: process.platform === 'win32', timeout: 10000 });
  if (result.status !== 0) throw new Error(`APK ZIP alignment check failed: ${result.stderr || result.stdout}`);
  const extractPython = 'import sys, zipfile; z=zipfile.ZipFile(sys.argv[1]); open(sys.argv[3], \'wb\').write(z.read(sys.argv[2]))';
  const unpack = spawnSync('D:/sdk/python/Python312/python.exe', ['-c', extractPython, apk, entry, destination], { env, encoding: 'utf8', shell: false, timeout: 10000 });
  if (unpack.status !== 0 || !existsSync(destination)) throw new Error(`Failed to extract ${entry} from APK: ${unpack.stderr || unpack.stdout}`);
};
const nativeArtifacts = {};
const requiredJni = ['nativeInit', 'nativeInitError', 'nativeHrtfLoaded', 'nativeOpenMp3', 'nativePullMp3', 'nativeLastError', 'nativeObjects', 'nativeSetHeadYaw', 'nativeResetHeadPose', 'nativeStart', 'nativeFeed', 'nativeStatus', 'nativeFinish', 'nativePause', 'nativeSetVolume', 'nativeClose'];
for (const abi of abis) {
  const libraryFile = `lib${libTarget.name}.so`;
  const packaged = resolve(dirname(apk), `${abi}-${libraryFile}`);
  extract(`lib/${abi}/${libraryFile}`, packaged);
  const nm = output(resolve(llvmBin, 'llvm-nm.exe'), ['-D', '--defined-only', packaged], root);
  const missing = requiredJni.filter((name) => !nm.includes(`Java_com_sda_nativebridge_SdaEngine_${name}`));
  if (missing.length) throw new Error(`${abi} packaged JNI library is missing exports: ${missing.join(', ')}`);
  const strippedCandidates = [
    resolve(android, 'app/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib', abi, libraryFile),
    resolve(android, 'app/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib', abi, 'lib' + libTarget.name + '.so'),
  ];
  const stripped = strippedCandidates.find(existsSync);
  const readBuildId = (file) => output(resolve(llvmBin, 'llvm-readelf.exe'), ['-n', file], root).match(/Build ID: ([0-9a-f]+)/i)?.[1] ?? null;
  const apkBuildId = readBuildId(packaged);
  const strippedBuildId = stripped ? readBuildId(stripped) : null;
  const apkSha256 = hash(packaged);
  const strippedSha256 = stripped ? hash(stripped) : null;
  if (!stripped || (apkBuildId && strippedBuildId ? apkBuildId !== strippedBuildId : apkSha256 !== strippedSha256)) {
    throw new Error(`${abi} APK JNI ELF does not match AGP stripped artifact by Build ID or SHA-256`);
  }
  nativeArtifacts[abi] = { apkPath: packaged, sha256: apkSha256, buildId: apkBuildId, strippedPath: stripped, strippedSha256 };
}
for (const name of webFiles) if (!zipEntries.includes(`assets/hrtf/${name}`)) throw new Error(`APK is missing KU100 asset ${name}`);
const apkDir = dirname(apk);
const verification = {
  contract: 'sda-mobile-android-v1', builtAt: new Date().toISOString(), requestedAbis: abis,
  includedAbis: Object.keys(nativeEntries), targetDirectory: targetDir, cargoTarget: libTarget,
  native, nativeArtifacts, apk: { path: apk, sha256: hash(apk), size: statSync(apk).size, nativeEntries, jsBundle: bundleEntry, requiredAbiLibraries: abiLibraries },
  hrtf: { manifestSha256: hash(join(assetDir, 'hrtf-set.json')), firCount: webFiles.filter((name) => name.endsWith('.f32')).length }, gradleArgs,
};
writeFileSync(resolve(apkDir, 'build-verification.json'), `${JSON.stringify(verification, null, 2)}\n`);
console.log(JSON.stringify(verification, null, 2));
