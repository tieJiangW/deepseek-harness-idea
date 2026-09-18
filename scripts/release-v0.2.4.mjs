// GitHub Release + Marketplace 上传（v0.2.4）。
// 用法（凭据只从环境变量读，绝不写入仓库）：
//   $env:GITHUB_PAT="..."; $env:MARKETPLACE_TOKEN="..."; node scripts/release-v0.2.4.mjs
// 缺哪项就跳过哪项；每一项都可单独重复执行（幂等）。
import { readFileSync, existsSync, statSync } from 'node:fs';
import { join } from 'node:path';

const OWNER = 'tieJiangW';
const REPO = 'deepseek-harness-idea';
const TAG = 'v0.2.4';
const PLUGIN_ZIP = join('build', 'distributions', 'deepseek-harness-idea-0.2.4.zip');
const NOTES = join('docs', 'releases', 'v0.2.4.md');
const PLATFORMS = ['win-x64', 'macos-arm64', 'macos-x64', 'linux-x64', 'linux-arm64'];

const gh = process.env.GITHUB_PAT;
const mp = process.env.MARKETPLACE_TOKEN;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function assets() {
  const list = [];
  if (existsSync(PLUGIN_ZIP)) list.push({ path: PLUGIN_ZIP, name: 'deepseek-harness-idea-0.2.4.zip', type: 'application/zip' });
  for (const p of PLATFORMS) {
    const base = join('release-assets', `runtime-${p}.zip`);
    if (existsSync(base)) list.push({ path: base, name: `runtime-${p}.zip`, type: 'application/zip' });
    if (existsSync(base + '.sha256')) list.push({ path: base + '.sha256', name: `runtime-${p}.zip.sha256`, type: 'text/plain' });
  }
  return list;
}

async function ghApi(path, init = {}) {
  const res = await fetch(`https://api.github.com${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${gh}`,
      Accept: 'application/vnd.github+json',
      'User-Agent': 'dsh-idea-release',
      ...(init.headers || {}),
    },
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* non-JSON */ }
  return { status: res.status, ok: res.ok, json, text };
}

if (!gh) {
  console.log('GITHUB_PAT 未设置：跳过 GitHub 部分');
} else {
  console.log('== GitHub ==');

  // 1) 推送代码（用 token 内嵌 URL，避免无 TTY 的凭据弹窗）
  const { spawnSync } = await import('node:child_process');
  const url = `https://x-access-token:${gh}@github.com/${OWNER}/${REPO}.git`;
  const push = spawnSync('git', ['push', url, 'main'], { encoding: 'utf8' });
  console.log(`git push: exit=${push.status}`);
  if (push.stderr) console.log(push.stderr.trim().split('\n').slice(-3).join('\n'));

  // 2) 创建/复用 release
  const notes = existsSync(NOTES) ? readFileSync(NOTES, 'utf8') : '';
  let rel = await ghApi(`/repos/${OWNER}/${REPO}/releases/tags/${TAG}`);
  if (rel.ok && rel.json) {
    console.log(`release ${TAG} exists (id=${rel.json.id})`);
  } else {
    const created = await ghApi(`/repos/${OWNER}/${REPO}/releases`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tag_name: TAG, name: TAG, body: notes, draft: false, prerelease: false }),
    });
    if (!created.ok) throw new Error(`create release failed: ${created.status} ${created.text.slice(0, 300)}`);
    rel = created;
    console.log(`release ${TAG} created (id=${rel.json.id})`);
  }
  const releaseId = rel.json.id;

  // 3) 上传资产（同名先删再传，幂等）
  const existing = await ghApi(`/repos/${OWNER}/${REPO}/releases/${releaseId}/assets?per_page=100`);
  const byName = new Map((existing.json || []).map((a) => [a.name, a.id]));
  for (const a of assets()) {
    if (byName.has(a.name)) {
      await ghApi(`/repos/${OWNER}/${REPO}/releases/assets/${byName.get(a.name)}`, { method: 'DELETE' });
      console.log(`  removed existing asset ${a.name}`);
    }
    const body = readFileSync(a.path);
    const up = await fetch(`https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${releaseId}/assets?name=${encodeURIComponent(a.name)}`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${gh}`,
        'Content-Type': a.type,
        'Content-Length': String(body.length),
        'User-Agent': 'dsh-idea-release',
      },
      body,
    });
    const okText = await up.text();
    console.log(`  upload ${a.name}: HTTP ${up.status} ${up.ok ? 'OK' : okText.slice(0, 200)}`);
    if (!up.ok) await sleep(2000);
  }
  console.log(`assets: ${assets().length} uploaded`);
}

if (!mp) {
  console.log('MARKETPLACE_TOKEN 未设置：跳过 Marketplace 部分');
} else {
  console.log('== Marketplace ==');
  const zip = readFileSync(PLUGIN_ZIP);
  const form = new FormData();
  form.append('xmlId', 'com.deepseek.harness.idea');
  form.append('file', new Blob([zip], { type: 'application/zip' }), 'deepseek-harness-idea-0.2.4.zip');
  const res = await fetch('https://plugins.jetbrains.com/api/updates/upload', {
    method: 'POST',
    headers: { Authorization: `Bearer ${mp}` },
    body: form,
  });
  console.log(`HTTP ${res.status}`);
  console.log(await res.text());
}

console.log('done');
