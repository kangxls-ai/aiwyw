package com.aiwyw;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** 本地受管停机后的显式迁移工具，不进入网站接口。只接收凭据文件路径，拒绝覆盖任何已有MySQL库。 */
public final class LocalMysqlMigration {
  static final ObjectMapper JSON = new ObjectMapper();

  static Map<String, String> credentials(Path file) throws Exception {
    byte[] bytes = Files.readAllBytes(file);
    String text = null;
    for (Charset charset :
        List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16, Charset.forName("GB18030"))) {
      try {
        text =
            charset
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        break;
      } catch (CharacterCodingException ignored) {
      }
    }
    if (text == null) throw new IllegalArgumentException("凭据文件编码不支持");
    var result = new HashMap<String, String>();
    for (String line : text.replace("\uFEFF", "").split("\\R")) {
      String[] pair = line.split("[:=：]", 2);
      if (pair.length == 2) result.put(pair[0].trim().toLowerCase(Locale.ROOT), pair[1].trim());
    }
    return result;
  }

  static Database.ConnectionSpec mysql(Path file, String name) throws Exception {
    var values = credentials(file);
    String host = values.getOrDefault("ip", values.getOrDefault("host", "127.0.0.1"));
    int port = Integer.parseInt(values.getOrDefault("端口", values.getOrDefault("port", "3306")));
    String user = values.getOrDefault("用户名", values.getOrDefault("username", "admin"));
    String password = values.getOrDefault("密码", values.get("password"));
    if (password == null
        || !host.matches("[A-Za-z0-9._:-]{1,253}")
        || !name.matches("[a-z][a-z0-9_]{1,63}")) throw new IllegalArgumentException("数据库凭据或库名不正确");
    return new Database.ConnectionSpec("MYSQL", host, port, name, user, password);
  }

  static String quote(String name) {
    if (!name.matches("[a-zA-Z0-9_]+")) throw new IllegalArgumentException("标识符不正确");
    return "`" + name + "`";
  }

  static SortedMap<String, String> tables(Connection connection) throws Exception {
    var result = new TreeMap<String, String>();
    try (var rows =
        connection
            .getMetaData()
            .getTables(connection.getCatalog(), connection.getSchema(), "%", null)) {
      while (rows.next())
        if (Set.of("TABLE", "BASE TABLE").contains(rows.getString("TABLE_TYPE"))) {
          String table = rows.getString("TABLE_NAME");
          result.put(table.toLowerCase(Locale.ROOT), table);
        }
    }
    return result;
  }

  static List<String> columns(Connection connection, String table) throws Exception {
    var names = new ArrayList<String>();
    try (var row =
        connection
            .getMetaData()
            .getColumns(connection.getCatalog(), connection.getSchema(), table, null)) {
      while (row.next()) names.add(row.getString("COLUMN_NAME"));
    }
    return names;
  }

  static String sourceQuote(String name) {
    return "\"" + name.replace("\"", "\"\"") + "\"";
  }

  static String digest(
      Connection connection,
      String table,
      List<String> columns,
      boolean h2,
      List<String> keys,
      Set<String> booleans)
      throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    var rowDigests = new ArrayList<String>();
    java.util.function.Function<String, String> q =
        h2 ? LocalMysqlMigration::sourceQuote : LocalMysqlMigration::quote;
    String sql =
        "SELECT "
            + String.join(",", columns.stream().map(q).toList())
            + " FROM "
            + q.apply(table)
            + " ORDER BY "
            + String.join(",", keys.stream().map(q).toList());
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        var rowDigest = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < columns.size(); i++) {
          String value =
              booleans.contains(columns.get(i).toLowerCase(Locale.ROOT))
                  ? (rows.getObject(i + 1) == null
                      ? null
                      : Boolean.toString(rows.getBoolean(i + 1)))
                  : rows.getString(i + 1);
          byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
          rowDigest.update(
              ByteBuffer.allocate(4).putInt(value == null ? -1 : bytes.length).array());
          rowDigest.update(bytes);
        }
        rowDigests.add(HexFormat.of().formatHex(rowDigest.digest()));
      }
    }
    // H2与MySQL排序规则不同，按行摘要排序后再汇总，既保留重复行计数也不受字符排序影响。
    Collections.sort(rowDigests);
    for (String row : rowDigests) digest.update(row.getBytes(StandardCharsets.US_ASCII));
    return HexFormat.of().formatHex(digest.digest());
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 4) throw new IllegalArgumentException("需要数据目录、凭据文件、全新库名、受管备份目录");
    Path root = Path.of(args[0]).toAbsolutePath().normalize(),
        backup = Path.of(args[3]).toAbsolutePath().normalize();
    for (String name : List.of("master.key", "bootstrap.enc")) {
      Path original = root.resolve(name), saved = backup.resolve("data").resolve(name);
      if (!Files.isRegularFile(original)
          || !Files.isRegularFile(saved)
          || !Arrays.equals(Files.readAllBytes(original), Files.readAllBytes(saved)))
        throw new IllegalStateException("源数据与受管备份不一致，拒绝迁移");
    }
    var store = new SecureStore(root.toString());
    var old =
        JSON.readValue(
            store.decrypt(Files.readString(root.resolve("bootstrap.enc"))),
            Database.ConnectionSpec.class);
    if (!old.engine().equals("H2")) throw new IllegalStateException("当前安装已使用外部数据库，不自动重迁或覆盖");
    var next = mysql(Path.of(args[1]), args[2]);
    String url =
        "jdbc:mysql://"
            + next.host()
            + ":"
            + next.port()
            + "/?sslMode=REQUIRED&connectionTimeZone=UTC&connectTimeout=5000&socketTimeout=60000";
    try (var admin = DriverManager.getConnection(url, next.username(), next.password());
        var statement = admin.createStatement()) {
      // 不使用IF NOT EXISTS；库已存在即停止，绝不清空用户已有数据库。
      statement.execute(
          "CREATE DATABASE "
              + quote(next.database())
              + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    }
    var database = new Database(store, JSON);
    var report = new LinkedHashMap<String, Object>();
    try (var source = database.connect(old);
        var target = database.connect(next)) {
      Database.schema(target);
      try (var a = source.getConnection();
          var b = target.getConnection()) {
        var sourceTables = tables(a);
        var targetTables = tables(b);
        if (!targetTables.keySet().containsAll(sourceTables.keySet()))
          throw new IllegalStateException("源库有未识别表，拒绝遗漏数据");
        b.setAutoCommit(false);
        try (var statement = b.createStatement()) {
          statement.execute("SET FOREIGN_KEY_CHECKS=0");
          for (String table : targetTables.keySet())
            statement.executeUpdate("DELETE FROM " + quote(table));
        }
        try {
          for (var entry : sourceTables.entrySet()) {
            String name = entry.getKey(), physical = entry.getValue();
            var columns = columns(a, physical);
            String insert =
                "INSERT INTO "
                    + quote(name)
                    + " ("
                    + String.join(
                        ",", columns.stream().map(c -> quote(c.toLowerCase(Locale.ROOT))).toList())
                    + ") VALUES("
                    + String.join(",", Collections.nCopies(columns.size(), "?"))
                    + ")";
            long count = 0;
            try (var read = a.createStatement();
                var rows = read.executeQuery("SELECT * FROM " + sourceQuote(physical));
                var write = b.prepareStatement(insert)) {
              while (rows.next()) {
                for (int i = 0; i < columns.size(); i++) {
                  Object value = rows.getObject(columns.get(i));
                  if (value instanceof Clob clob)
                    value = clob.getSubString(1, Math.toIntExact(clob.length()));
                  if (value instanceof Blob blob)
                    value = blob.getBytes(1, Math.toIntExact(blob.length()));
                  write.setObject(i + 1, value);
                }
                write.addBatch();
                count++;
                if (count % 200 == 0) write.executeBatch();
              }
              write.executeBatch();
            }
            var primary = new TreeMap<Short, String>();
            try (var keys =
                a.getMetaData().getPrimaryKeys(a.getCatalog(), a.getSchema(), physical)) {
              while (keys.next())
                primary.put(keys.getShort("KEY_SEQ"), keys.getString("COLUMN_NAME"));
            }
            if (primary.isEmpty()) throw new IllegalStateException("源表缺少稳定主键，不能可靠核对：" + name);
            Set<String> booleans = new HashSet<>();
            try (var meta =
                a.getMetaData().getColumns(a.getCatalog(), a.getSchema(), physical, null)) {
              while (meta.next())
                if (Set.of(Types.BOOLEAN, Types.BIT).contains(meta.getInt("DATA_TYPE")))
                  booleans.add(meta.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
            String original =
                digest(a, physical, columns, true, new ArrayList<>(primary.values()), booleans);
            String copied =
                digest(
                    b,
                    name,
                    columns.stream().map(c -> c.toLowerCase(Locale.ROOT)).toList(),
                    false,
                    primary.values().stream().map(c -> c.toLowerCase(Locale.ROOT)).toList(),
                    booleans);
            if (!original.equals(copied)) throw new IllegalStateException("数据摘要核对未通过：" + name);
            report.put(name, Map.of("rows", count, "sha256", original));
          }
          b.commit();
        } catch (Exception e) {
          b.rollback();
          throw e;
        } finally {
          try (var statement = b.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS=1");
          }
        }
      }
      // 逐表核对后再应用新增迁移；例如旧菜单授权合并，不能把它误报为复制不一致。
      Database.schema(target);
    }
    Path pending = root.resolve("bootstrap.mysql.pending");
    Files.writeString(pending, store.encrypt(JSON.writeValueAsString(next)));
    store.protect(pending);
    Files.move(
        pending,
        root.resolve("bootstrap.enc"),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
    Path evidence = root.resolve("mysql-migration-report.json");
    Files.writeString(
        evidence,
        JSON.writerWithDefaultPrettyPrinter()
            .writeValueAsString(
                Map.of(
                    "database",
                    next.database(),
                    "tables",
                    report,
                    "verified",
                    true,
                    "completedAt",
                    System.currentTimeMillis())));
    store.protect(evidence);
    System.out.println(
        "MySQL迁移完成；已逐表核对 " + report.size() + " 张源表，原H2文件与密钥均保留。数据库：" + next.database());
  }
}
