const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const projectRoot = path.resolve(__dirname, '..');

function readVersion() {
  const raw = fs.readFileSync(path.join(projectRoot, 'version.properties'), 'utf8');
  const values = Object.fromEntries(raw.split(/\r?\n/).filter(Boolean).map(line => {
    const index = line.indexOf('=');
    return [line.slice(0, index).trim(), line.slice(index + 1).trim()];
  }));
  return { versionName: values.versionName, versionCode: Number(values.versionCode) };
}

function sha256File(filePath) {
  const hash = crypto.createHash('sha256');
  hash.update(fs.readFileSync(filePath));
  return hash.digest('hex');
}

function fail(message) { throw new Error('[release:verify] ' + message); }

function verify() {
  const version = readVersion();
  if (!/^\d+\.\d+\.\d+$/.test(version.versionName) ||
      !Number.isSafeInteger(version.versionCode) || version.versionCode < 1) {
    fail('version.properties is invalid');
  }
  const releaseDir = path.join(projectRoot, 'dist', 'apk', 'release');
  const apkName = 'ShineVoice-V' + version.versionName + '-release.apk';
  const apkPath = path.join(releaseDir, apkName);
  const metadataPath = path.join(releaseDir, 'update.json');
  if (!fs.existsSync(apkPath)) fail('missing APK');
  if (!fs.existsSync(metadataPath)) fail('missing update.json');
  let metadata;
  try { metadata = JSON.parse(fs.readFileSync(metadataPath, 'utf8')); } catch (_) { fail('metadata JSON is invalid'); }
  const required = ['versionName', 'versionCode', 'apkName', 'apkUrl', 'sha256',
    'forceUpdate', 'minimumVersionCode', 'title', 'notes', 'apkSizeBytes'];
  required.forEach(key => {
    if (!Object.prototype.hasOwnProperty.call(metadata, key)) fail('missing field: ' + key);
  });
  if (metadata.versionName !== version.versionName) fail('versionName mismatch');
  if (metadata.versionCode !== version.versionCode) fail('versionCode mismatch');
  if (metadata.apkName !== apkName) fail('apkName mismatch');
  if (metadata.apkUrl !== '' &&
      !/^https:\/\/(github\.com|objects\.githubusercontent\.com)\//.test(metadata.apkUrl)) {
    fail('apkUrl host is not GitHub');
  }
  if (!/^[a-f0-9]{64}$/.test(metadata.sha256)) fail('sha256 is invalid');
  if (typeof metadata.forceUpdate !== 'boolean') fail('forceUpdate is invalid');
  if (!Number.isSafeInteger(metadata.minimumVersionCode) || metadata.minimumVersionCode < 0) {
    fail('minimumVersionCode is invalid');
  }
  if (typeof metadata.title !== 'string' || !metadata.title.trim()) fail('title is invalid');
  if (!Array.isArray(metadata.notes) || metadata.notes.some(note => typeof note !== 'string')) {
    fail('notes is invalid');
  }
  const stats = fs.statSync(apkPath);
  if (!Number.isSafeInteger(metadata.apkSizeBytes) || metadata.apkSizeBytes !== stats.size) {
    fail('apkSizeBytes mismatch');
  }
  const actualHash = sha256File(apkPath);
  if (metadata.sha256 !== actualHash) fail('sha256 mismatch');
  console.log('[release:verify] PASS');
  console.log('APK=' + apkPath);
  console.log('update.json=' + metadataPath);
  console.log('versionName=' + metadata.versionName);
  console.log('versionCode=' + metadata.versionCode);
  console.log('sha256=' + actualHash);
  console.log('apkSizeBytes=' + stats.size);
}

if (require.main === module) {
  try { verify(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}

module.exports = { verify, readVersion, sha256File };
