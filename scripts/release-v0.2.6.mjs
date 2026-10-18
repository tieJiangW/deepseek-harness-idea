// GitHub Release 上传（v0.2.6；本次不上传 JetBrains Marketplace）。
//
// 用法（凭据只从环境变量读，绝不写入仓库/脚本/提交）：
//   $env:GITHUB_PAT="..."; $env:MARKETPLACE_TOKEN="..."; node scripts/release-v0.2.6.mjs
//   - 缺哪项凭据就跳过哪一段（可分两步跑）
//   - 每一段都可重复执行（幂等：同名资产先删再传；Release 已存在则复用）
//
// 注意顺序（见 docs/RELEASE_PROCESS.md §3.3）：**先推送 main，再创建 Release**，
// 否则 tag 会指向旧的 main HEAD。本脚本已把推送放在最前。
//
// §8 备用通道：本机 github.com:443 不通（git push 直接 connection reset），
// 但 api.github.com / uploads.github.com 可达。git push 失败时自动改用
// Git Data API（blobs → trees → commits → refs）推送同一份内容；要求工作区已干净提交。
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const OWNER = 'tieJiangW';
const REPO = 'deepseek-harness-idea';
const TAG = 'v0.2.6';
const PLUGIN_ZIP = join('build', 'distributions', 'deepseek-harness-idea-0.2.6.zip');
const NOTES = join('docs', 'releases', 'v0.2.6.md');
const PLATFORMS = ['win-x64', 'macos-arm64', 'macos-x64', 'linux-x64', 'linux-arm64'];

const gh = process.env.GITHUB_PAT;
const mp = process.env.MARKETPLACE_TOKEN;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function assets() {
  const list = [];
  if (existsSync(PLUGIN_ZIP)) {
    list.push({ path: PLUGIN_ZIP, name: 'deepseek-harness-idea-0.2.6.zip', type: 'application/zip' });
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
 * （v0.2.6 实测：本地构建与 CI 构建的 runtime zip 字节数相同、sha256 不同，若只补 zip 就会错配）。
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

/**
 * 用 Git Data API 把**本地已提交的 HEAD** 推到远端 main（github.com:443 被屏蔽时的唯一通道）。
 *
 * 策略：优先只传"远端 main → 本地 HEAD"的差异（省请求、可读）；若远端 commit 在本地不存在
 * （无法 diff，例如 CLONE 落后），退化为**整树快照**（HEAD 里每个文件重新上传为 blob），
 * 保证远端树与本地 HEAD 完全一致。工作区必须干净，否则拒绝（避免把半成品推上去）。
 */
async function pushViaGitDataApi() {
  const { spawnSync } = await import('node:child_process');
  const git = (args) => spawnSync('git', args, { encoding: 'utf8' });

  if (git(['status', '--porcelain']).stdout.trim()) {
    throw new Error('refusing API push: working tree is dirty; commit locally first');
  }

  const head = git(['rev-parse', 'HEAD']).stdout.trim();
  const ref = await ghApi(`/repos/${OWNER}/${REPO}/git/ref/heads/main`);
  if (!ref.ok) throw new Error(`read main ref failed: ${ref.status} ${ref.text.slice(0, 200)}`);
  const parentSha = ref.json.object.sha;
  const parent = await ghApi(`/repos/${OWNER}/${REPO}/git/commits/${parentSha}`);
  if (!parent.ok) throw new Error(`read parent commit failed: ${parent.status}`);
  const baseTree = parent.json.tree.sha;

  async function blobFor(path) {
    const body = readFileSync(path);
    const res = await ghApi(`/repos/${OWNER}/${REPO}/git/blobs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: body.toString('base64'), encoding: 'base64' }),
    });
    if (!res.ok) throw new Error(`blob ${path} failed: ${res.status} ${res.text.slice(0, 200)}`);
    return res.json.sha;
  }

  const canDiff = spawnSync('git', ['cat-file', '-e', parentSha], { encoding: 'utf8' }).status === 0;
  const entries = [];
  let strategy;

  if (canDiff) {
    strategy = 'incremental vs ' + parentSha.slice(0, 8);
    const out = git(['diff', '--name-status', parentSha, head]).stdout;
    for (const line of out.split('\n')) {
      if (!line.trim()) continue;
      const parts = line.split('\t');
      const st = parts[0][0];
      if (st === 'R' || st === 'C') {
        entries.push({ path: parts[1], sha: null });
        entries.push({ path: parts[2], mode: '100644', sha: await blobFor(parts[2]) });
      } else if (st === 'D') {
        entries.push({ path: parts[1], sha: null });
      } else {
        entries.push({ path: parts[1], mode: '100644', sha: await blobFor(parts[1]) });
      }
    }
  } else {
    strategy = 'full snapshot of local HEAD (remote commit not present locally)';
    for (const line of git(['ls-tree', '-r', head]).stdout.split('\n')) {
      if (!line.trim()) continue;
      const m = /^(\d+) blob [0-9a-f]+\t(.+)$/.exec(line);
      if (!m) continue;
      entries.push({ path: m[2], mode: m[1], sha: await blobFor(m[2]) });
    }
  }
  console.log(`  API push strategy: ${strategy}; entries=${entries.length}`);

  const tree = await ghApi(`/repos/${OWNER}/${REPO}/git/trees`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(canDiff ? { base_tree: baseTree, tree: entries } : { tree: entries }),
  });
  if (!tree.ok) throw new Error(`create tree failed: ${tree.status} ${tree.text.slice(0, 300)}`);

  const message = git(['log', '-1', '--pretty=%B']).stdout.trim();
  const commit = await ghApi(`/repos/${OWNER}/${REPO}/git/commits`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message, tree: tree.json.sha, parents: [parentSha] }),
  });
  if (!commit.ok) throw new Error(`create commit failed: ${commit.status} ${commit.text.slice(0, 300)}`);

  const moved = await ghApi(`/repos/${OWNER}/${REPO}/git/refs/heads/main`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sha: commit.json.sha, force: false }),
  });
  if (!moved.ok) throw new Error(`update main failed: ${moved.status} ${moved.text.slice(0, 300)}`);
  return commit.json.sha;
}

if (!gh) {
  console.log('GITHUB_PAT 未设置：跳过 GitHub 部分');
} else {
  console.log('== GitHub ==');

  // 1) 推送代码：先试 git push；github.com:443 不通时改用 Git Data API（见文件头与 RELEASE_PROCESS §8）
  const { spawnSync } = await import('node:child_process');
  // 凭据只放在 URL 里、且任何输出都要脱敏：git 的报错会把整条 URL（含 token）原样打出来。
  const redact = (text) => String(text || '')
    .replace(/gh[pousr]_[A-Za-z0-9]+/g, 'gh?_***')
    .replace(/x-access-token:[^@]+@/g, 'x-access-token:***@');
  const url = `https://x-access-token:${gh}@github.com/${OWNER}/${REPO}.git`;
  const push = spawnSync('git', ['push', url, 'main'], { encoding: 'utf8' });
  console.log(`git push main: exit=${push.status}`);
  if (push.stderr) console.log(redact(push.stderr.trim().split('\n').slice(-3).join('\n')));

  let mainSha = null;
  if (push.status !== 0) {
    console.log('git push failed (github.com:443 blocked) -> pushing through the Git Data API');
    mainSha = await pushViaGitDataApi();
    console.log(`API push main -> ${mainSha}`);
  } else {
    const refNow = await ghApi(`/repos/${OWNER}/${REPO}/git/ref/heads/main`);
    mainSha = refNow.json?.object?.sha ?? null;
  }

  // tag 指向刚推上去的 main（Release 创建时复用该 tag）
  if (mainSha) {
    const tagRef = await ghApi(`/repos/${OWNER}/${REPO}/git/refs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ref: `refs/tags/${TAG}`, sha: mainSha }),
    });
    if (tagRef.ok) {
      console.log(`tag ${TAG} created -> ${mainSha}`);
    } else {
      const patchedTag = await ghApi(`/repos/${OWNER}/${REPO}/git/refs/tags/${TAG}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sha: mainSha, force: true }),
      });
      console.log(`tag ${TAG} ${patchedTag.ok ? 'moved' : 'update failed ' + patchedTag.status} -> ${mainSha}`);
    }
  }

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
  const pluginAsset = assets().find((a) => a.name === 'deepseek-harness-idea-0.2.6.zip');
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
  form.append('file', new Blob([zip], { type: 'application/zip' }), 'deepseek-harness-idea-0.2.6.zip');
  const res = await fetch('https://plugins.jetbrains.com/api/updates/upload', {
    method: 'POST',
    headers: { Authorization: `Bearer ${mp}` },
    body: form,
  });
  console.log(`HTTP ${res.status}`);
  console.log(await res.text());
}

console.log('done');
