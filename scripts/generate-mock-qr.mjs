import QRCode from 'qrcode'
import { writeFileSync, mkdirSync } from 'node:fs'
const qr = await QRCode.toString('AIWYW SIMULATED PAYMENT ONLY - DO NOT TRANSFER MONEY', { type: 'svg', width: 220, margin: 2, color: { dark: '#60786aff', light: '#ffffffff' } })
mkdirSync('web/public/images', { recursive: true })
writeFileSync('web/public/images/mock-payment.svg', `<svg xmlns="http://www.w3.org/2000/svg" width="220" height="260" viewBox="0 0 220 260"><title>模拟收款二维码 不可用于真实付款</title><rect width="220" height="260" rx="8" fill="white"/>${qr}<rect y="218" width="220" height="42" fill="#edf4ef"/><text x="110" y="237" text-anchor="middle" font-size="12" font-family="sans-serif" fill="#36604b">模拟测试 · 请勿转账</text><text x="110" y="251" text-anchor="middle" font-size="8" font-family="sans-serif" fill="#7c9485">SIMULATED PAYMENT ONLY</text></svg>`)
