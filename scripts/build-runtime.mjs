#!/usr/bin/env node
// build-runtime.mjs — 跨平台 DSH 运行时构建（可跑在任意主机，产出目标平台运行时）。
//
// 用法：
//   node scripts/build-runtime.mjs [--os win32|darwin|linux] [--arch x64|arm64]
//        [--node-version 22.23.2] [--dsh-version 0.1.1-rc.2]
//        [--output build/runtime-<os>-<arch>] [--registry <npm>] [--cache <dir>]
//        [--bundle] [--force]
//
// 说明：
//   - Node 发行包按目标平台选择（win-*.zip / darwin-*.tar.gz / linux-*.tar.gz），SHA-256
//     从同版本官方 SHASUMS256.txt 校验（镜像或 nodejs.org）。
//   - 解压后把 node 归一化到 <output>/node/ 布局：Windows=node.exe（顶层），
//     Unix 的 bin/node → node/node（工具链按 Platform.nodeBinName 取）。
//   - dsh 树用目标 node 的 npm 安装到 <output>/dsh/，并传 --os/--cpu 使 optionalDependencies
//     按目标平台解析（本机制影响 sharp/koffi 等原生依赖；CI 矩阵在各目标 OS 上构建最稳）。
//   - --bundle 时打包 runtime-<os>-<arch>.zip（根为 node/ + dsh/）并产出同名 .sha256 侧车。
import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');

// ---- CLI ----
const args = process.argv.slice(2);
function opt(name, def) {
  const i = args.indexOf('--' + name);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : def;
}
function flag(name) { return args.includes('--' + name); }

const osName = opt('os', process.platform);          // win32 | darwin | linux
const arch = opt('arch', process.arch);               // x64 | arm64
const nodeVersion = opt('node-version', '22.23.2');
const dshVersion = opt('dsh-version', '0.1.1-rc.2');
const output = opt('output', path.join(root, 'build', `runtime-${osId(osName)}-${arch}`));
const registry = opt('registry', process.env.npm_config_registry || 'https://registry.npmmirror.com/');
const cacheDir = opt('cache', path.join(output, '.npm-cache'));
const nodeMirror = opt('node-mirror', 'https://registry.npmmirror.com/-/binary/node');
const force = flag('force');
const bundle = flag('bundle');

function osId(o) {
  const n = String(o).trim().toLowerCase();
  // 用前缀匹配，避免 'darwin' 含 'win' 造成误判为 Windows
  if (n.startsWith('win')) return 'win';
  if (n.startsWith('darwin') || n.startsWith('mac')) return 'macos';
  if (n.startsWith('linux')) return 'linux';
  return n;
}

// ---- 目标平台 → Node 发行包 ----
const TARGETS = {
  'win-x64':     { os: 'win32',  file: (v) => `node-v${v}-win-x64.zip`,        sub: (v) => `node-v${v}-win-x64`,       nodeBin: 'node.exe', npmRel: 'node_modules/npm/bin/npm-cli.js' },
  'win-arm64':   { os: 'win32',  file: (v) => `node-v${v}-win-arm64.zip`,      sub: (v) => `node-v${v}-win-arm64`,     nodeBin: 'node.exe', npmRel: 'node_modules/npm/bin/npm-cli.js' },
  'darwin-x64':  { os: 'darwin', file: (v) => `node-v${v}-darwin-x64.tar.gz`,  sub: (v) => `node-v${v}-darwin-x64`,    nodeBin: 'node',     npmRel: 'lib/node_modules/npm/bin/npm-cli.js' },
  'darwin-arm64':{ os: 'darwin', file: (v) => `node-v${v}-darwin-arm64.tar.gz`,sub: (v) => `node-v${v}-darwin-arm64`,  nodeBin: 'node',     npmRel: 'lib/node_modules/npm/bin/npm-cli.js' },
  'linux-x64':   { os: 'linux',  file: (v) => `node-v${v}-linux-x64.tar.gz`,   sub: (v) => `node-v${v}-linux-x64`,     nodeBin: 'node',     npmRel: 'lib/node_modules/npm/bin/npm-cli.js' },
  'linux-arm64': { os: 'linux',  file: (v) => `node-v${v}-linux-arm64.tar.gz`, sub: (v) => `node-v${v}-linux-arm64`,   nodeBin: 'node',     npmRel: 'lib/node_modules/npm/bin/npm-cli.js' },
};
const targetKey = `${osId(osName)}-${arch}`;
const t = TARGETS[targetKey];
if (!t) { console.error(`unsupported target: ${targetKey}`); process.exit(1); }

function log(m) { console.log(`==> ${m}`); }
function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex').toUpperCase();
}
function sh(cmd, argsArr, opts = {}) {
  const r = spawnSync(cmd, argsArr, { stdio: 'inherit', ...opts });
  if (r.status !== 0) throw new Error(`command failed: ${cmd} ${argsArr.join(' ')}`);
}
async function fetchText(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(`HTTP ${r.status} ${url}`);
  return r.text();
}
async function fetchTo(url, dest) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(`HTTP ${r.status} ${url}`);
  const buf = Buffer.from(await r.arrayBuffer());
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.writeFileSync(dest, buf);
}

const nodeDir = path.join(output, 'node');
const nodeExe = path.join(nodeDir, t.nodeBin);
const dshDir = path.join(output, 'dsh');
const dshBin = path.join(dshDir, 'node_modules/@deepseek-ai/dsh/lib/bin.js');
const bundleZip = path.join(root, 'build', `runtime-${osId(osName)}-${arch}.zip`);

async function main() {
  log(`DSH runtime build: node v${nodeVersion} + @deepseek-ai/dsh@${dshVersion} -> ${output}`);
  // fresh checkout 无 build/ 等目录：先确保存在（下载包写 build/ 上层、bundle 到 build/）
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.mkdirSync(output, { recursive: true });
  fs.mkdirSync(path.dirname(bundleZip), { recursive: true });

  // ---- 1. Node ----
  if (fs.existsSync(nodeExe) && !force) {
    log('Node exists, skip download');
    sh(nodeExe, ['-v']);
  } else {
    const zipName = t.file(nodeVersion);
    const url = `${nodeMirror}/v${nodeVersion}/${zipName}`;
    const archive = path.join(output, '..', zipName);   // 放 output 上层，避免被 bundle 排除
    log(`Download ${zipName}`);
    console.log(`   ${url}`);
    if (!fs.existsSync(archive)) await fetchTo(url, archive);

    // SHA-256：从同版本 SHASUMS256.txt 校验
    log('Verify SHA-256');
    const sums = await fetchText(`${nodeMirror}/v${nodeVersion}/SHASUMS256.txt`);
    const line = sums.split('\n').find((l) => l.trim().endsWith(zipName));
    const expected = line?.split(/\s+/)[0]?.trim().toUpperCase();
    if (!expected) throw new Error(`no sha256 for ${zipName} in SHASUMS256.txt`);
    const actual = sha256(archive);
    if (actual !== expected) throw new Error(`SHA-256 mismatch: expected ${expected}, got ${actual} (${zipName})`);
    console.log(`   ok: ${actual}`);

    // 解压（zip 与 tar.gz 均用 tar；现代 Windows/macOS/Linux 自带 bsdtar/GNU tar）
    log('Extract to node/');
    const unpack = path.join(output, 'unpack');
    fs.rmSync(unpack, { recursive: true, force: true });
    fs.mkdirSync(unpack, { recursive: true });
    sh('tar', archive.endsWith('.gz') ? ['-xzf', archive, '-C', unpack] : ['-xf', archive, '-C', unpack]);
    const extracted = path.join(unpack, t.sub(nodeVersion));
    if (!fs.existsSync(extracted)) throw new Error(`extracted dir not found: ${extracted}`);

    fs.mkdirSync(nodeDir, { recursive: true });
    copyRecursive(extracted, nodeDir);
    // Unix：把 bin/node 上移到 node/ 根（归一化布局）；Windows 的 node.exe 已在顶层
    if (t.nodeBin !== 'node.exe') {
      const binNode = path.join(nodeDir, 'bin', t.nodeBin);
      if (fs.existsSync(binNode)) {
        fs.renameSync(binNode, path.join(nodeDir, t.nodeBin));
        fs.chmodSync(path.join(nodeDir, t.nodeBin), 0o755);
      }
    }
    fs.rmSync(unpack, { recursive: true, force: true });
    fs.rmSync(archive, { force: true });
    if (!fs.existsSync(nodeExe)) throw new Error(`node not found: ${nodeExe}`);
    sh(nodeExe, ['-v']);
  }

  // ---- 2. dsh ----
  if (fs.existsSync(dshBin) && !force) {
    log('dsh exists, skip install');
    const pkgJson = path.join(dshDir, 'node_modules/@deepseek-ai/dsh/package.json');
    if (fs.existsSync(pkgJson)) log(`   dsh ${JSON.parse(fs.readFileSync(pkgJson, 'utf8')).version}`);
  } else {
    log(`Install @deepseek-ai/dsh@${dshVersion}`);
    fs.rmSync(dshDir, { recursive: true, force: true });
    fs.mkdirSync(dshDir, { recursive: true });
    fs.writeFileSync(path.join(dshDir, 'package.json'), JSON.stringify({ name: 'dsh-runtime', private: true, dependencies: { '@deepseek-ai/dsh': dshVersion } }, null, 2));
    const npmCli = path.join(nodeDir, t.npmRel);
    if (!fs.existsSync(npmCli)) throw new Error(`bundled npm-cli.js missing: ${npmCli}`);
    const npmArgs = [npmCli, 'install', '--ignore-scripts', '--no-audit', '--no-fund', '--cache', cacheDir, '--registry', registry, '--os', t.os, '--cpu', arch];
    sh(nodeExe, npmArgs, { cwd: dshDir });
    if (!fs.existsSync(dshBin)) throw new Error(`dsh bin missing after install: ${dshBin}`);
  }

  // ---- 3. Smoke ----
  log('Smoke verification');
  const dshPkgDir = path.resolve(dshDir, 'node_modules/@deepseek-ai/dsh');
  sh(nodeExe, ['-e', `const p=require(process.argv[1]+'/package.json');console.log('   dsh '+p.version);`, dshPkgDir]);

  // ---- 4. Bundle ----
  if (bundle) {
    log('Bundle runtime-' + targetKey + '.zip');
    fs.rmSync(bundleZip, { force: true });
    const tarArgs = ['-a', '-c', '-f', bundleZip, '--exclude', '*.zip', '--exclude', '.npm-cache', '-C', output, 'node', 'dsh'];
    sh('tar', tarArgs);
    const sizeMb = Math.round(fs.statSync(bundleZip).size / 1048576 * 10) / 10;
    console.log(`   -> ${bundleZip} (${sizeMb} MB)`);
    fs.writeFileSync(bundleZip + '.sha256', sha256(bundleZip) + '\n');
    console.log(`   -> ${bundleZip}.sha256`);
  }

  log(`Done: ${output}`);
  console.log(`   node: ${nodeExe}`);
  console.log(`   dsh : ${dshBin}`);
}

function copyRecursive(src, dest) {
  for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
    const s = path.join(src, entry.name);
    const d = path.join(dest, entry.name);
    if (entry.isDirectory()) { fs.mkdirSync(d, { recursive: true }); copyRecursive(s, d); }
    else if (entry.isSymbolicLink()) { fs.symlinkSync(fs.readlinkSync(s), d); }
    else { fs.copyFileSync(s, d); }
  }
}

main().catch((e) => { console.error(e); process.exit(1); });
