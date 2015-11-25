package com.devexed.dalwit.jdbc;

import com.devexed.dalwit.AccessorFactory;
import com.devexed.dalwit.DatabaseException;
import com.devexed.dalwit.Transaction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

final class JdbcDatabase extends JdbcAbstractDatabase {

    JdbcDatabase(Connection connection,
                 AccessorFactory<PreparedStatement, Integer, ResultSet, Integer, SQLException> accessorFactory,
                 JdbcGeneratedKeysSelector generatedKeysSelector) {
        super("database", connection, accessorFactory, generatedKeysSelector);
    }

    @Override
    public Transaction transact() {
        checkActive();
        JdbcRootTransaction transaction = new JdbcRootTransaction(this);
        openTransaction(transaction);
        return transaction;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new DatabaseException(e);
        }

        super.close();
    }

}
