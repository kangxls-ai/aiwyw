// 本机启动编译产物；复用backend/data，不创建生产账户、不清库。生产部署流程另行配置。
import { spawn } from 'node:child_process'
import { resolve } from 'node:path'
import { existsSync } from 'node:fs'
import { javaOptions } from './java-options.mjs'

const jar = resolve('backend/target/aiwyw-server-0.1.0-SNAPSHOT.jar')
const web = resolve('web/.output/server/index.mjs')
if (!existsSync(jar) || !existsSync(web)) {
  console.error('请先执行后端打包与 npm run build，再运行 npm start。')
  process.exit(1)
}
const children = []
let stopping = false
function stop(code = 0) { if (stopping) return; stopping = true; for (const child of children) child.kill('SIGTERM'); setTimeout(() => process.exit(code), 300) }
for (const [command, args, env] of [
  ['java', [...javaOptions, '-jar', jar, `--aiwyw.data-dir=${resolve('backend/data')}`], process.env],
  [process.execPath, [web], { ...process.env, HOST: '127.0.0.1', PORT: '3000' }],
]) {
  const child = spawn(command, args, { stdio: 'inherit', env, windowsHide: true })
  children.push(child)
  child.on('error', error => { console.error(error.message); stop(1) })
  child.on('exit', code => stop(code || 0))
}
process.on('SIGTERM', () => stop())
process.on('SIGINT', () => stop())
console.log('AI万有网 本地预览: http://127.0.0.1:3000')
console.log('首次安装: http://127.0.0.1:3000/install （安装码见后端首次启动提示）')
