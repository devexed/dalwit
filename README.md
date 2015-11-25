# Dalwit

**Dalwit** is a <b>D</b>atabase <b>A</b>bstraction <b>L</b>ayer <b>w</b>ith <b>I</b>ntegrated <b>T</b>ransactions. Specifically it is an abstraction for communication with SQL databases in Java. Dalwit came about while examining ways of sharing database logic between Android applications and desktop Java applications. Failing to muster up any passion for implementing the quite massive JDBC interface for Android, I set upon creating a more minimal database abstraction. The Android implementation of Dalwit can be found at [dalwit-android](//github.com/cattuz/dalwit-android). The main features distinguishing Dalwit from JDBC are:

 * *First class transactions.* Where in JDBC using transactions means disabling auto commit and creating, committing and releasing savepoints, in Dalwit [transactions](#transactions) and nesting of transactions are part of the core interface.
 * *Driver dependent queries.* Dalwit features the possibility of defining query permutations for different database types and versions to bridge the gap where our dear Standard Query Language is not quite as standard as we would like it.
 * *Named and typed columns and query parameters.* Query parameters are accessed by name and have a defined Java class. No more coercing and converting parameters at binding and retrieval time, and being unsure whether to use `getTimestamp`, `getLong`, or even `getString` for that DATETIME column.
 * *No unexceptional exceptions.* Meaning no checked exceptions that require boiler plate or wrapping to handle. Dalwit follows the philosophy that exceptions are not the rule, but the exception.

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

In Dalwit you create a connection which can open `Database` objects which correspond to a JDBC `java.sql.Connection` object. A Dalwit `Connection` is an inert object which contains the configuration to open databases, in writable or readonly mode. 

```java
Connection connection = new JdbcConnection("org.h2.Driver", "jdbc:h2:./test.db");
Database database = connection.write();
```

However, since many JDBC implementations lack support for all getters and setters as well as full `PreparedStatement.getGeneratedKeys()` support, `JdbcConnection` allows you to define how you want your objects accessed and your generated keys provided.

```java
Connection connection = new JdbcConnection(
        "org.h2.Driver",
        "jdbc:h2:./test.db",
        new Properties(),
        
        // The default JDBC accessor factory assumes the JDBC driver supports all getters
        // and setters in the JDBC interface
        new DefaultJdbcAccessorFactory(),
        
        // Since the H2 driver doesn't support getting all keys generated by an insert
        // statement we can use a function selector to mock generated key support
        new FunctionJdbcGeneratedKeysSelector("scope_identity()", Long.TYPE));
```

### Querying 

```java
// Creating the typed query
Query countQuery = Queries.of("SELECT count(*) AS c FROM t", Collections.singletonMap("c", Long.TYPE));

// The long form...
Database database = null;

try {
    database = connection.write();
    Cursor cursor = database.createQuery(countQuery).query(database);
    System.out.println(cursor.get("c"));
} finally {
    // Close methods ignore null and automatically close all their child resources
    connection.close(database);
}

// ... or using provided utilities for that Java 7 swagger...
try (Database database = Connections.write(connection)) {
    Cursor cursor = Statements.query(database, countQuery);
    System.out.println(cursor.get("c"));
}
```

### <a name="transactions"></a>Transactions

Updating the database occurs within transactions which are explicitly committed or rolled back:

```java
Query insertQuery = Queries.of("INSERT INTO t (a) VALUES (:a)", Collections.singletonMap("a", String.class));

// The long form...
Database database = null;
Transaction transaction = null;

try {
    database = connection.write();
    transaction = database.transact();
    UpdateStatement statement = transaction.createUpdate(insertQuery);
    statement.bind("a", "Text 123");
    long count = statement.update(transaction);
    System.out.println("Success! Inserted " + count + " rows");
    
    // Committing the transaction if everything has gone smoothly
    database.commit(transaction);
} catch (DatabaseException e) {
    // Rolling back the transaction on any database exception. The rollback method is a no-op if transaction is null. 
    database.rollback(transaction);
} finally {
    connection.close(database);
}

// ... or using utilities...
try (Database database = Connections.write(connection)) {
    UpdateStatement statement = database.createUpdate(insertQuery);
    statement.bind("a", "Text 123");
    long count = Statements.update(db, statement);
    System.out.println("Success! Inserted " + count + " rows");
}
```
