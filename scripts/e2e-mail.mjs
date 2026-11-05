/** 浏览器验收用隔离TLS邮件收件箱，不发送到外网，不放宽生产SMTP证书验证。 */
import { SMTPServer } from "smtp-server";
import { execFileSync } from "node:child_process";
import { join } from "node:path";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { randomUUID } from "node:crypto";
export async function startTestInbox(directory) {
  const password = "isolated-inbox-918273",
    pfx = join(directory, "inbox.p12"),
    cert = join(directory, "inbox.crt"),
    trust = join(directory, "inbox-trust.p12");
  const keytool = process.env.JAVA_HOME
    ? join(
        process.env.JAVA_HOME,
        "bin",
        process.platform === "win32" ? "keytool.exe" : "keytool",
      )
    : "keytool";
  const call = (args) =>
    execFileSync(keytool, args, { windowsHide: true, stdio: "pipe" });
  call([
    "-genkeypair",
    "-alias",
    "inbox",
    "-keyalg",
    "RSA",
    "-keysize",
    "2048",
    "-validity",
    "2",
    "-dname",
    "CN=localhost",
    "-ext",
    "SAN=DNS:localhost,IP:127.0.0.1",
    "-keystore",
    pfx,
    "-storetype",
    "PKCS12",
    "-storepass",
    password,
    "-keypass",
    password,
  ]);
  call([
    "-exportcert",
    "-alias",
    "inbox",
    "-keystore",
    pfx,
    "-storepass",
    password,
    "-rfc",
    "-file",
    cert,
  ]);
  call([
    "-importcert",
    "-noprompt",
    "-alias",
    "inbox",
    "-keystore",
    trust,
    "-storetype",
    "PKCS12",
    "-storepass",
    password,
    "-file",
    cert,
  ]);
  mkdirSync(join(directory, "inbox"), { recursive: true });
  const server = new SMTPServer({
    secure: true,
    pfx: readFileSync(pfx),
    passphrase: password,
    name: "localhost",
    disableReverseLookup: true,
    logger: false,
    onAuth(auth, session, done) {
      if (
        auth.username !== "isolated-inbox" ||
        auth.password !== "isolated-mail-password"
      )
        return done(new Error("Bad fixture credentials"));
      done(null, { user: auth.username });
    },
    onRcptTo(address, session, done) {
      if (!address.address.endsWith("@example.com"))
        return done(new Error("Fixture only accepts example.com"));
      done();
    },
    onData(stream, session, done) {
      const chunks = [];
      stream.on("data", (chunk) => chunks.push(chunk));
      stream.on("end", () => {
        const raw = Buffer.concat(chunks).toString("utf8");
        const split = raw.indexOf("\r\n\r\n"),
          headers = raw.slice(0, split),
          payload = raw.slice(split + 4);
        const body = /Content-Transfer-Encoding: base64/i.test(headers)
          ? Buffer.from(payload.replace(/\s/g, ""), "base64").toString("utf8")
          : payload
              .replace(/=\r\n/g, "")
              .replace(/=([0-9A-F]{2})/gi, (_, n) =>
                String.fromCharCode(parseInt(n, 16)),
              );
        writeFileSync(
          join(directory, "inbox", randomUUID() + ".json"),
          JSON.stringify({
            to: session.envelope.rcptTo.map((a) => a.address),
            body,
            receivedAt: Date.now(),
          }),
        );
        done();
      });
    },
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  writeFileSync(
    join(directory, "inbox.json"),
    JSON.stringify({ port: server.server.address().port }),
  );
  return {
    server,
    javaOptions: [
      `-Djavax.net.ssl.trustStore=${trust}`,
      `-Djavax.net.ssl.trustStorePassword=${password}`,
    ],
  };
}
