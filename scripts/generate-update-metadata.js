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
  if (!/^\d+\.\d+\.\d+$/.test(values.versionName) || !/^\d+$/.test(values.versionCode)) {
    throw new Error('Invalid version.properties');
  }
  return { versionName: values.versionName, versionCode: Number(values.versionCode) };
}

function sha256File(filePath) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha256');
    const stream = fs.createReadStream(filePath);
    stream.on('error', reject);
    stream.on('data', chunk => hash.update(chunk));
    stream.on('end', () => resolve(hash.digest('hex')));
  });
}

function readNotes(versionName) {
  const changelogPath = path.join(projectRoot, 'CHANGELOG.md');
  if (!fs.existsSync(changelogPath)) return [];
  const lines = fs.readFileSync(changelogPath, 'utf8').split(/\r?\n/);
  const start = lines.findIndex(line =>
    line.startsWith('## V' + versionName) ||
    line.startsWith('## [' + versionName + ']') ||
    line.startsWith('## [V' + versionName + ']'),
  );
  if (start < 0) return [];
  const body = [];
  for (const line of lines.slice(start + 1)) {
    if (line.startsWith('## V') || line.startsWith('## [')) break;
    const match = line.match(/^\s*[-*]\s+(.*)$/);
    if (match && match[1].trim()) body.push(match[1].trim());
  }
  return body;
}

async function main() {
  const version = readVersion();
  const apkName = 'ShineVoice-V' + version.versionName + '-release.apk';
  const releaseDir = path.join(projectRoot, 'dist', 'apk', 'release');
  const apkPath = path.join(releaseDir, apkName);
  if (!fs.existsSync(apkPath)) throw new Error('Release APK not found: ' + apkPath);
  const stats = fs.statSync(apkPath);
  const metadata = {
    versionName: version.versionName,
    versionCode: version.versionCode,
    apkName,
    apkUrl: process.env.SHINEVOICE_RELEASE_APK_URL ||
      'https://github.com/anjingdtl/ShineVoice/releases/download/V' +
      version.versionName + '/' + apkName,
    sha256: (await sha256File(apkPath)).toLowerCase(),
    forceUpdate: false,
    minimumVersionCode: 0,
    title: 'ShineVoice V' + version.versionName,
    notes: readNotes(version.versionName),
    apkSizeBytes: stats.size,
  };
  if (metadata.apkUrl && !/^https:\/\/(github\.com|objects\.githubusercontent\.com)\//.test(metadata.apkUrl)) {
    throw new Error('SHINEVOICE_RELEASE_APK_URL must be a GitHub HTTPS asset URL');
  }
  fs.mkdirSync(releaseDir, { recursive: true });
  const outputPath = path.join(releaseDir, 'update.json');
  fs.writeFileSync(outputPath, JSON.stringify(metadata, null, 2) + '\n');
  console.log('update.json=' + outputPath);
  console.log('apkName=' + metadata.apkName);
  console.log('versionName=' + metadata.versionName);
  console.log('versionCode=' + metadata.versionCode);
  console.log('sha256=' + metadata.sha256);
  console.log('apkSizeBytes=' + metadata.apkSizeBytes);
}

if (require.main === module) {
  main().catch(error => {
    console.error('[release:metadata] ' + error.message);
    process.exitCode = 1;
  });
}

module.exports = { readVersion, readNotes, sha256File };
