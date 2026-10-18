// GitHub Release + JetBrains Marketplace 上传（v0.2.5）。
//
// 用法（凭据只从环境变量读，绝不写入仓库/脚本/提交）：
//   $env:GITHUB_PAT="..."; $env:MARKETPLACE_TOKEN="..."; node scripts/release-v0.2.5.mjs
//   - 缺哪项凭据就跳过哪一段（可分两步跑）
//   - 每一段都可重复执行（幂等：同名资产先删再传；Release 已存在则复用）
//
// 注意顺序（见 docs/RELEASE_PROCESS.md §3.3）：**先推送 main，再创建 Release**，
// 否则 tag 会指向旧的 main HEAD。本脚本已把推送放在最前。
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const OWNER = 'tieJiangW';
const REPO = 'deepseek-harness-idea';
const TAG = 'v0.2.5';
const PLUGIN_ZIP = join('build', 'distributions', 'deepseek-harness-idea-0.2.5.zip');
const NOTES = join('docs', 'releases', 'v0.2.5.md');
const PLATFORMS = ['win-x64', 'macos-arm64', 'macos-x64', 'linux-x64', 'linux-arm64'];

const gh = process.env.GITHUB_PAT;
const mp = process.env.MARKETPLACE_TOKEN;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function assets() {
  const list = [];
  if (existsSync(PLUGIN_ZIP)) {
    list.push({ path: PLUGIN_ZIP, name: 'deepseek-harness-idea-0.2.5.zip', type: 'application/zip' });
  }
  for (const p of PLATFORMS) {
    const base = join('release-assets', `runtime-${p}.zip`);
    if (existsSync(base)) list.push({ path: base, name: `runtime-${p}.zip`, type: 'application/zip' });
    if (existsSync(base + '.sha256')) {
      list.push({ path: base + '.sha256', name: `runtime-${p}.zip.sha256`, type: 'text/plain' });
    }
  }
  return list;
}

/**
 * 读取远端某个资产的文本内容（.sha256 是 65 字节，走 octet-stream 重定向）。
 * 用来**成对**判断 zip 与 sha256 是否配套 —— 只比大小会漏掉"哈希不同、字节数相同"的情况
 * （v0.2.5 实测：本地构建与 CI 构建的 runtime zip 字节数相同、sha256 不同，若只补 zip 就会错配）。
 */
async function remoteAssetText(name, byName) {
  const a = byName.get(name);
  if (!a || a.state !== 'uploaded') return null;
  const res = await fetch(`https://api.github.com/repos/${OWNER}/${REPO}/releases/assets/${a.id}`, {
    headers: { Authorization: `Bearer ${gh}`, Accept: 'application/octet-stream', 'User-Agent': 'dsh-idea-release' },
    redirect: 'follow',
  });
  return res.ok ? (await res.text()).trim() : null;
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

  // 1) 推送代码（token 内嵌 URL，避免无 TTY 环境下的凭据弹窗）
  const { spawnSync } = await import('node:child_process');
  const url = `https://x-access-token:${gh}@github.com/${OWNER}/${REPO}.git`;
  const push = spawnSync('git', ['push', url, 'main'], { encoding: 'utf8' });
  console.log(`git push main: exit=${push.status}`);
  if (push.stderr) console.log(push.stderr.trim().split('\n').slice(-3).join('\n'));
  const tagPush = spawnSync('git', ['push', url, `refs/tags/${TAG}:refs/tags/${TAG}`, '--force'], { encoding: 'utf8' });
  console.log(`git push tag ${TAG}: exit=${tagPush.status}`);
  if (tagPush.stderr) console.log(tagPush.stderr.trim().split('\n').slice(-3).join('\n'));

  // 2) 创建/复用 release（tag 已存在则复用）
  const notes = existsSync(NOTES) ? readFileSync(NOTES, 'utf8') : '';
  let rel = await ghApi(`/repos/${OWNER}/${REPO}/releases/tags/${TAG}`);
  if (rel.ok && rel.json) {
    console.log(`release ${TAG} exists (id=${rel.json.id})`);
    // 正文可能被 CI 的 generate_release_notes 覆盖过，这里再纠正一次
    const patched = await ghApi(`/repos/${OWNER}/${REPO}/releases/${rel.json.id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body: notes }),
    });
    console.log(`  release body ${patched.ok ? 'updated' : 'update failed ' + patched.status}`);
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

  // 3) 同步资产：以 .sha256 内容为准**成对**更新（避免 zip 与校验文件错配）
  const existing = await ghApi(`/repos/${OWNER}/${REPO}/releases/${releaseId}/assets?per_page=100`);
  let byName = new Map((existing.json || []).map((a) => [a.name, a]));

  async function replace(a) {
    const have = byName.get(a.name);
    if (have) {
      await ghApi(`/repos/${OWNER}/${REPO}/releases/assets/${have.id}`, { method: 'DELETE' });
      console.log(`  removed existing asset ${a.name}`);
    }
    const body = readFileSync(a.path);
    const up = await fetch(
      `https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${releaseId}/assets?name=${encodeURIComponent(a.name)}`,
      {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${gh}`,
          'Content-Type': a.type,
          'Content-Length': String(body.length),
          'User-Agent': 'dsh-idea-release',
        },
        body,
      },
    );
    const text = await up.text();
    console.log(`  upload ${a.name}: HTTP ${up.status} ${up.ok ? 'OK' : text.slice(0, 200)}`);
    if (!up.ok) await sleep(2000);
  }

  // 插件 zip：小文件，始终重传（保证与本地构建一致）
  const pluginAsset = assets().find((a) => a.name === 'deepseek-harness-idea-0.2.5.zip');
  if (pluginAsset) await replace(pluginAsset);

  // 运行时：远端 sha256 与本地一致才跳过；否则 zip + sha256 一起重传
  for (const p of PLATFORMS) {
    const shaName = `runtime-${p}.zip.sha256`;
    const zipName = `runtime-${p}.zip`;
    const localShaFile = join('release-assets', shaName);
    const zipFile = join('release-assets', zipName);
    if (!existsSync(zipFile)) { console.log(`  skip ${zipName} (not built locally)`); continue; }
    const localSha = existsSync(localShaFile) ? readFileSync(localShaFile, 'utf8').trim().toLowerCase() : null;
    const remoteSha = (await remoteAssetText(shaName, byName)) || '';
    if (localSha && remoteSha.toLowerCase() === localSha) {
      console.log(`  keep ${zipName} + ${shaName} (sha256 matches)`);
      continue;
    }
    console.log(`  ${zipName}: remote sha256 differs/missing -> re-uploading the pair`);
    if (existsSync(localShaFile)) {
      await replace({ path: localShaFile, name: shaName, type: 'text/plain' });
    }
    await replace({ path: zipFile, name: zipName, type: 'application/zip' });
  }

  // 4) 复核：每个平台的远端 sha256 必须与本地一致
  const after = await ghApi(`/repos/${OWNER}/${REPO}/releases/${releaseId}/assets?per_page=100`);
  byName = new Map((after.json || []).map((a) => [a.name, a]));
  let mismatched = 0;
  for (const p of PLATFORMS) {
    const shaName = `runtime-${p}.zip.sha256`;
    const localShaFile = join('release-assets', shaName);
    if (!existsSync(localShaFile)) continue;
    const localSha = readFileSync(localShaFile, 'utf8').trim().toLowerCase();
    const remoteSha = ((await remoteAssetText(shaName, byName)) || '').toLowerCase();
    const ok = localSha === remoteSha;
    if (!ok) mismatched++;
    console.log(`  ${ok ? 'OK  ' : 'BAD '} ${p}`);
  }
  console.log(`assets now: ${(after.json || []).length}, mismatched sha256: ${mismatched}`);


  // 4) 校验：tag 指向与 main HEAD（打印出来便于人工确认）
  const ref = await ghApi(`/git/ref/tags/${TAG}`);
  const head = await ghApi(`/commits/main`);
  console.log(`tag ${TAG} -> ${ref.json?.object?.sha ?? '?'}`);
  console.log(`main       -> ${head.json?.sha ?? '?'}  ${head.json?.commit?.message?.split('\n')[0] ?? ''}`);
}

if (!mp) {
  console.log('MARKETPLACE_TOKEN 未设置：跳过 Marketplace 部分');
} else {
  console.log('== Marketplace ==');
  const zip = readFileSync(PLUGIN_ZIP);
  const form = new FormData();
  form.append('xmlId', 'com.deepseek.harness.idea');
  form.append('file', new Blob([zip], { type: 'application/zip' }), 'deepseek-harness-idea-0.2.5.zip');
  const res = await fetch('https://plugins.jetbrains.com/api/updates/upload', {
    method: 'POST',
    headers: { Authorization: `Bearer ${mp}` },
    body: form,
  });
  console.log(`HTTP ${res.status}`);
  console.log(await res.text());
}

console.log('done');
