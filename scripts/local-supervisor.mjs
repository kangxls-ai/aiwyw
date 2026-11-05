/**
 * 本地阶段版本的后台进程管理。运行隔离的发布副本，编译不会改动正在服务的文件。
 * 控制请求只通过当前用户受保护目录和子进程stdin传递，不暴露HTTP停机接口。
 */
import { spawn } from 'node:child_process'
import { mkdirSync, readFileSync, writeFileSync, renameSync, existsSync, openSync, closeSync } from 'node:fs'
import { resolve, relative, join, isAbsolute } from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomUUID } from 'node:crypto'
import { javaOptions } from './java-options.mjs'

const repo = fileURLToPath(new URL('../', import.meta.url))
const local = resolve(repo, '.local')
const release = resolve(process.argv[2] || '')
const relativeRelease = relative(join(local, 'releases'), release)
if (!relativeRelease || relativeRelease.startsWith('..') || isAbsolute(relativeRelease)) throw new Error('Invalid local release path')
const manifest = JSON.parse(readFileSync(join(release, 'release.json'), 'utf8'))
const runId = randomUUID()
const logDir = join(local, 'logs', runId)
mkdirSync(logDir, { recursive: true })
const out = openSync(join(logDir, 'application.log'), 'a')
const err = openSync(join(logDir, 'application-error.log'), 'a')
const stateFile = join(local, 'runtime-state.json')
const stopFile = join(local, 'stop-request.json')
const state = { runId, supervisorPid: process.pid, release, logDir, stage: manifest.stage, status: 'starting', startedAt: new Date().toISOString() }
function save() {
  const temporary = stateFile + '.pending'
  writeFileSync(temporary, JSON.stringify(state, null, 2))
  renameSync(temporary, stateFile)
}
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java'
const backend = spawn(java, [...javaOptions, '-Daiwyw.local-supervisor=true', '-jar', join(release, 'backend', 'server.jar'),
  `--aiwyw.data-dir=${resolve(repo, 'backend/data')}`, '--server.address=127.0.0.1', '--server.port=18080',
  '--server.shutdown=graceful', '--spring.lifecycle.timeout-per-shutdown-phase=15s'],
{ cwd: repo, windowsHide: true, stdio: ['pipe', out, err] })
const web = spawn(process.execPath, [join(release, 'web', 'server', 'index.mjs')],
  { cwd: repo, windowsHide: true, env: { ...process.env, HOST: '127.0.0.1', PORT: '3000' }, stdio: ['ignore', out, err] })
state.backendPid = backend.pid
state.webPid = web.pid
state.status = 'running'
save()
let stopping = false
const closed = child => new Promise(resolve => { if (!child.pid || child.exitCode !== null || child.signalCode !== null) resolve(); else child.once('close', resolve) })
async function stop(reason, failed = false) {
  if (stopping) return
  stopping = true
  clearInterval(timer)
  state.status = 'stopping'; state.reason = reason; save()
  const webClosed = closed(web), backendClosed = closed(backend)
  web.kill('SIGTERM')
  // Spring关闭连接池和数据库后进程才退出，停机备份不会复制活动中的H2文件。
  backend.stdin.on('error', () => {})
  if (!backend.stdin.destroyed) backend.stdin.end('AIWYW_STOP\n')
  let forced = false
  const timeout = setTimeout(() => { forced = true; backend.kill('SIGTERM'); web.kill('SIGTERM') }, 25000)
  await Promise.all([webClosed, backendClosed])
  clearTimeout(timeout)
  state.status = failed ? 'failed' : 'stopped'; state.forced = forced; state.stoppedAt = new Date().toISOString(); save()
  closeSync(out); closeSync(err)
  process.exit(failed ? 1 : 0)
}
for (const [name, child] of [['backend', backend], ['web', web]]) {
  child.on('error', error => { state.error = `${name}: ${error.code || 'start failed'}`; stop(state.error, true) })
  child.on('exit', code => { if (!stopping) stop(`${name} exited (${code})`, true) })
}
const timer = setInterval(() => {
  if (!existsSync(stopFile)) return
  try { if (JSON.parse(readFileSync(stopFile, 'utf8')).runId === runId) stop('requested') } catch { /* 尚未写完的请求下一轮重读。 */ }
}, 400)
process.on('SIGINT', () => stop('SIGINT'))
process.on('SIGTERM', () => stop('SIGTERM'))
