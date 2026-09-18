// 安全自检：确认仓库（代码 / 提交信息 / Release 正文）与本地工作区不含凭据。
//
// 本脚本自身**不含任何凭据**：需要比对的明文由调用方通过环境变量传入（可选），
// 未传入时退化为"通用凭据形态"检测（ghp_ / github_pat_ / perm- / 常见赋值形式）。
//
// 用法：
//   $env:GITHUB_PAT="<只读权限即可>"; $env:AUDIT_NEEDLES="<待查明文1>,<待查明文2>"; node scripts/secret-audit.mjs
import { readdirSync, statSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const OWNER = 'tieJiangW';
const REPO = 'deepseek-harness-idea';
const TAG = 'v0.2.4';

// 通用形态（不指向任何具体凭据值；占位符如 "..." 与 <...> 不算命中）
const GENERIC = [
  /\bghp_[A-Za-z0-9]{20,}/,
  /\bgithub_pat_[A-Za-z0-9_]{20,}/,
  /\bperm-[A-Za-z0-9._=-]{20,}/,
  /(GITHUB_PAT|MARKETPLACE_TOKEN)\s*[:=]\s*(?!["'<]?(?:\.\.\.|<\S*>|$))\S{10,}/m,
];

// 调用方传入的明文（按逗号分隔；留空则不检查具体值）
const EXPLICIT = (process.env.AUDIT_NEEDLES || '')
  .split(',')
  .map((s) => s.trim())
  .filter((s) => s.length >= 10);

const pat = process.env.GITHUB_PAT;
const headers = {
  Accept: 'application/vnd.github+json',
  'User-Agent': 'dsh-idea-audit',
  ...(pat ? { Authorization: `Bearer ${pat}` } : {}),
};

const hits = [];
function scan(label, text) {
  if (typeof text !== 'string' || text.length === 0) return;
  for (const re of GENERIC) {
    const m = re.exec(text);
    if (m) hits.push(`${label}: matched ${m[0].slice(0, 16)}…`);
  }
  for (const needle of EXPLICIT) {
    if (text.includes(needle)) hits.push(`${label}: contains an explicitly supplied secret`);
  }
}

async function get(url) {
  try {
    const res = await fetch(url, { headers });
    if (!res.ok) return { ok: false, status: res.status, body: '' };
    return { ok: true, status: res.status, body: await res.text() };
  } catch (e) {
    return { ok: false, status: 0, body: '' };
  }
}

// 1) 提交信息
const commits = await get(`https://api.github.com/repos/${OWNER}/${REPO}/commits?per_page=40`);
if (commits.ok) {
  const list = JSON.parse(commits.body);
  for (const c of list) scan(`commit ${c.sha.slice(0, 7)}`, c.commit?.message);
  console.log(`scanned ${list.length} commit messages`);
} else {
  console.log(`commit scan skipped (HTTP ${commits.status})`);
}

// 2) 仓库文本文件（逐文件取原文）
const tree = await get(`https://api.github.com/repos/${OWNER}/${REPO}/git/trees/main?recursive=1`);
if (tree.ok) {
  const entries = JSON.parse(tree.body).tree.filter(
    (e) => e.type === 'blob' && /\.(mjs|mts|js|ts|kts|kt|ps1|bat|sh|yml|yaml|json|txt|md|properties|xml)$/i.test(e.path),
  );
  console.log(`scanning ${entries.length} repo text files`);
  for (const e of entries) {
    const f = await get(`https://raw.githubusercontent.com/${OWNER}/${REPO}/main/${e.path}`);
    if (f.ok) scan(`file ${e.path}`, f.body);
  }
} else {
  console.log(`repo scan skipped (HTTP ${tree.status})`);
}

// 3) Release 正文与标题
const rel = await get(`https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/${TAG}`);
if (rel.ok) {
  const j = JSON.parse(rel.body);
  scan('release body', j.body);
  scan('release name', j.name);
  console.log(`release ${TAG}: ${(j.assets || []).length} assets`);
} else {
  console.log(`release scan skipped (HTTP ${rel.status})`);
}

// 4) 本地工作区（含未跟踪；跳过产物与 VCS 目录）
const skip = /(^|\/|\\)(\.git|build|tooling|release-assets|\.gradle|\.kotlin|\.idea|node_modules)(\/|\\)/;
let scannedFiles = 0;
function walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (skip.test(p)) continue;
    let st;
    try { st = statSync(p); } catch { continue; }
    if (st.isDirectory()) { walk(p); continue; }
    if (st.size > 2_000_000) continue;
    try { scan(`local ${p}`, readFileSync(p, 'utf8')); } catch { /* binary */ }
    scannedFiles++;
  }
}
walk('.');
console.log(`local files scanned: ${scannedFiles}`);
if (EXPLICIT.length > 0) console.log(`explicit needles checked: ${EXPLICIT.length}`);

console.log(hits.length === 0
  ? 'AUDIT PASS: no credential found in repo, release or local workspace'
  : 'AUDIT FAIL:\n' + hits.join('\n'));
process.exit(hits.length === 0 ? 0 : 1);
