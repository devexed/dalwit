# Dalwit

**Dalwit** is a <b>D</b>atabase <b>A</b>bstraction <b>L</b>ayer <b>w</b>ith <b>I</b>ntegrated <b>T</b>ransactions.
Specifically it is an abstraction for communication with SQL databases in Java, designed to be more minimal and
single-minded than JDBC. The main features distinguishing Dalwit from JDBC are:

 * *First class transactions.* Where in JDBC using transactions means disabling auto commit and creating, committing
 and releasing save points, in Dalwit [transactions](#transactions) and nesting of transactions are part of the core
 interface.
 * *Named and typed columns and query parameters.* Query parameters are accessed by name and have a defined Java class.
 No more coercing and converting parameters at binding and retrieval time, and being unsure whether to use
 `getTimestamp`, `getLong`, or even `getString` for that DATETIME column.
 * *No unexceptional exceptions.* Meaning no checked exceptions that require boiler plate or wrapping to handle.
 Dalwit follows the philosophy that exceptions are not the rule, but the exception.

## Examples

### Opening a database

Where in pure JDBC you typically create open a connection with some reflection and the driver manager.

```java
try {
    Class.forName("org.h2.Driver");
    java.sql.Connection connection = DriverManager.getConnection("jdbc:h2:./test.db");
} catch (ClassNotFoundException | SQLException e) {
    /* ... */
}
```

In Dalwit you create a connection which can open `Database` objects which correspond to a JDBC `java.sql.Connection`
object. A Dalwit `Connection` is an inert object which contains the configuration to open databases, in writable or
readonly mode.

```java
Connection connection = new JdbcConnection("org.h2.Driver", "jdbc:h2:./test.db");
Database database = connection.write();
```

However, since many JDBC implementations lack support for all getters and setters as well as full
`PreparedStatement.getGeneratedKeys()` support, `JdbcConnection` allows you to define how you want your objects accessed
and your generated keys provided.

```java
Connection connection = new JdbcConnection(
        "org.h2.Driver",
        "jdbc:h2:./test.db",
        new Properties(),
        
        // The default JDBC accessor factory assumes the JDBC driver supports all getters and setters in the
        // JDBC interface
        new DefaultJdbcAccessorFactory(),
        
        // Since the H2 driver doesn't support getting all keys generated by an insert
        // statement we can use a function selector to mock generated key support.
        new FunctionJdbcGeneratedKeysSelector("scope_identity()", Long.TYPE));
```

### Querying 

```java
Query countQuery = Query
        .builder("SELECT count(*) AS c FROM t")
        .column("c", Integer.TYPE)
        .build();

try (ReadonlyDatabase database = connection.read();
     ReadonlyStatement statement = database.prepare(countQuery);
     Cursor cursor = statement.query(database)) {
    String c = cursor.get("c");
    System.out.println(c);
}
```

### <a name="transactions">Transactions</a>

Transactions which are explicitly committed or otherwise automatically rolled back:

```java
Query insertQuery = Query
        .builder("INSERT INTO t (a) VALUES (:a)")
        .parameter("a", String.class)
        .build();

try (Database database = connection.write();
     Statement statement = database.prepare(insertQuery)) {
    statement.bind("a", "Text 123");
    statement.execute();
}

// ... Or the short form for statements only executed once

try (Database database = connection.write()) {
    insertQuery.on(database).bind("a", "Text 123").execute();
}

```
