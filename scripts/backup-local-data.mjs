/** 停机后备份本项目资料；MySQL仅导出安装时选定的应用库，连接密码不放命令行或日志。 */
import { cpSync, existsSync, readFileSync, writeFileSync, mkdirSync, readdirSync, unlinkSync, lstatSync } from 'node:fs'
import { resolve, join, dirname, delimiter, relative, isAbsolute } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createDecipheriv } from 'node:crypto'
import { spawn } from 'node:child_process'

const repo = fileURLToPath(new URL('../', import.meta.url))
const root = resolve(repo, 'backend/data'), destination = resolve(process.argv[2] || '')
const within = relative(resolve(repo, '.local/backups'), destination)
if (!within || within.startsWith('..') || isAbsolute(within)) throw new Error('Invalid backup destination')
mkdirSync(destination, { recursive: true })
const report = { createdAt: new Date().toISOString(), engine: 'H2', dataCopied: false, databaseDump: false }
if (existsSync(root)) {
  cpSync(root, join(destination, 'data'), { recursive: true, errorOnExist: true, force: false,
    filter: source => { if (lstatSync(source).isSymbolicLink()) throw new Error('Data backup refuses symbolic links'); return true } })
  report.dataCopied = true
}
const bootstrap = join(root, 'bootstrap.enc')
if (existsSync(bootstrap)) {
  const key = readFileSync(join(root, 'master.key'))
  const bytes = Buffer.from(readFileSync(bootstrap, 'utf8'), 'base64')
  const cipher = createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12))
  cipher.setAuthTag(bytes.subarray(-16))
  const clear = Buffer.concat([cipher.update(bytes.subarray(12, -16)), cipher.final()])
  const spec = JSON.parse(clear.toString('utf8'))
  clear.fill(0); key.fill(0)
  report.engine = spec.engine
  if (spec.engine === 'MYSQL') {
    const candidates = (process.env.PATH || '').split(delimiter).map(p => join(p, process.platform === 'win32' ? 'mysqldump.exe' : 'mysqldump'))
    const mysqlRoot = join(process.env.ProgramFiles || 'C:/Program Files', 'MySQL')
    if (existsSync(mysqlRoot)) for (const folder of readdirSync(mysqlRoot)) candidates.push(join(mysqlRoot, folder, 'bin/mysqldump.exe'))
    const binary = candidates.find(existsSync)
    if (!binary) throw new Error('MySQL backup requires mysqldump; previous version will be restored')
    const credentials = join(destination, 'mysql-client.temporary.cnf')
    const quote = value => '"' + String(value).replaceAll('\\', '\\\\').replaceAll('"', '\\"').replaceAll('\n', '\\n').replaceAll('\r', '\\r') + '"'
    writeFileSync(credentials, `[client]\nhost=${quote(spec.host)}\nport=${spec.port}\nuser=${quote(spec.username)}\npassword=${quote(spec.password)}\n`, { mode: 0o600 })
    try {
      await new Promise((resolve, reject) => {
        const child = spawn(binary, [`--defaults-extra-file=${credentials}`, '--ssl-mode=REQUIRED', '--single-transaction', '--skip-lock-tables', '--no-tablespaces',
          '--default-character-set=utf8mb4', `--result-file=${join(destination, 'database.sql')}`, '--databases', spec.database],
        { cwd: dirname(binary), windowsHide: true, stdio: ['ignore', 'ignore', 'ignore'] })
        const timeout = setTimeout(() => { child.kill(); reject(new Error('MySQL backup timed out')) }, 120000)
        child.on('error', () => { clearTimeout(timeout); reject(new Error('MySQL backup could not start')) })
        child.on('exit', code => { clearTimeout(timeout); code === 0 ? resolve() : reject(new Error('MySQL backup failed; check backup permissions and TLS')) })
      })
      report.databaseDump = true
    } finally { if (existsSync(credentials)) unlinkSync(credentials) }
  }
}
writeFileSync(join(destination, 'backup.json'), JSON.stringify(report, null, 2))
console.log(`本地数据备份完成：${report.engine}`)
