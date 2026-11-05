// Windows环境兼容参数只作用于当前子进程，不修改全局Java或其他应用。
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { randomUUID } from 'node:crypto'

// In restricted Windows desktops AF_UNIX can bind but fail to connect.
// A deliberately absent socket directory makes OpenJDK's selector pipe fall back to TCP.
// This is process-local and does not change OS settings or require a configuration file.
export const javaOptions = process.platform === 'win32'
  ? [`-Djdk.net.unixdomain.tmpdir=${join(tmpdir(), `aiwyw-tcp-only-${randomUUID()}`)}`]
  : []
