const { spawnSync } = require('node:child_process');
const path = require('node:path');
const python = process.platform === 'win32' ? 'py' : 'python3';
const args = process.platform === 'win32' ? ['-3.11'] : [];
args.push(path.join(__dirname, 'quality.py'), 'scan', 'eslint');
const result = spawnSync(python, args, { stdio: 'inherit' });
if (result.error) throw result.error;
process.exit(result.status ?? 1);
