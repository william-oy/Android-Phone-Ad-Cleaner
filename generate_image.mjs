#!/usr/bin/env node
/**
 * generate_image.mjs — SenseNova 文生图 (text-to-image) 能力
 *
 * ── 文生图模型声明 ──────────────────────────────────────────────
 * provider : sensenova (商汤日日新)
 * model    : sensenova-u1.5-lite   (SenseNova U1.5 Lite，下一代统一生图/编辑模型)
 * endpoint : https://token.sensenova.cn/v1/images/generations
 * protocol : openai-compatible /images/generations  (POST)
 * request  : { model, prompt, size, n }            -> { data: [{ url | b64_json }] }
 * input    : text
 * output   : image (png)
 * auth     : Bearer SENSENOVA_API_KEY  (凭证在 ~/.dsh/.credentials.yaml refs)
 * ─────────────────────────────────────────────────────────────────
 *
 * 用法:
 *   node generate_image.mjs "一只金色凤凰在日出时飞翔，中国水墨画" [size] [outfile]
 *   size 默认 "1024x1024"; outfile 默认 generated/<时间戳>.png
 *
 * 注意: 账户 Token Plan 生图额度用尽时返回 429
 *       {"error":{"message":"token plan entitlement exhausted",
 *        "type":"quota_exceeded_error","code":"8"}} —— 充值/等额度重置后再用。
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, resolve, dirname, basename } from 'node:path';

const CREDS = 'C:/Users/Administrator/.dsh/.credentials.yaml';
const ENDPOINT = 'https://token.sensenova.cn/v1/images/generations';
const MODEL = 'sensenova-u1.5-lite';
const DEFAULT_SIZE = '1024x1024';
const OUT_DIR = resolve('generated');

// --- args ---
const prompt = process.argv[2];
const size = process.argv[3] || DEFAULT_SIZE;
const outArg = process.argv[4];
if (!prompt) {
  console.error('usage: node generate_image.mjs "<prompt>" [size] [outfile]');
  process.exit(2);
}

// --- key ---
const raw = readFileSync(CREDS, 'utf8');
const m = raw.match(/refs:\s*\n\s*SENSENOVA_API_KEY:\s*(\S+)/);
const key = m ? m[1].trim() : null;
if (!key) { console.error('NO SENSENOVA_API_KEY in ' + CREDS); process.exit(3); }

// --- call ---
const body = JSON.stringify({ model: MODEL, prompt, size, n: 1 });
const res = await fetch(ENDPOINT, {
  method: 'POST',
  headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
  body,
});
const text = await res.text();
let parsed;
try { parsed = JSON.parse(text); } catch { parsed = { raw: text }; }

if (!res.ok) {
  const err = parsed?.error || parsed;
  console.error(JSON.stringify({ ok: false, status: res.status, model: MODEL, error: err }, null, 2));
  process.exit(1);
}

// --- save image ---
const data = parsed?.data?.[0] || {};
let bytes;
let mime = 'image/png';
let url = data.url || null;
if (data.b64_json) {
  bytes = Buffer.from(data.b64_json, 'base64');
} else if (url) {
  const img = await fetch(url);
  bytes = Buffer.from(await img.arrayBuffer());
  mime = img.headers.get('content-type') || mime;
} else {
  console.error('no image in response: ' + text.slice(0, 300));
  process.exit(1);
}

mkdirSync(OUT_DIR, { recursive: true });
const outFile = outArg
  ? resolve(outArg)
  : join(OUT_DIR, `gen-${Date.now()}.${mime === 'image/jpeg' ? 'jpg' : 'png'}`);
writeFileSync(outFile, bytes);

console.log(JSON.stringify({
  ok: true,
  status: res.status,
  model: MODEL,
  size,
  saved: outFile,
  bytes: bytes.length,
  mime,
  url,
  revisedPrompt: parsed?.revised_prompt || null,
}, null, 2));
