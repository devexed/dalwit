package com.devexed.dalwit.jdbc;

import com.devexed.dalwit.*;
import com.devexed.dalwit.util.AbstractCloseable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

final class JdbcStatement extends AbstractCloseable implements Statement {

    private final Query query;
    private final JdbcAbstractDatabase database;
    private final PreparedStatement statement;

    JdbcStatement(JdbcAbstractDatabase database, Query query) {
        this.database = database;
        this.query = query;
        String sql = query.sql();

        try {
            if (query.keys().isEmpty()) {
                // Regular query, update or execute statement
                statement = database.connection.prepareStatement(sql);
            } else {
                // Insert statement with returnable generated columns
                statement = database.generatedKeysSelector.prepareInsertStatement(database.connection, query.sql(), query.keys());
            }
        } catch (SQLException e) {
            throw new DatabaseException("SQL Error when executing query: " + sql, e);
        }
    }

    @Override
    public Cursor query() {
        checkNotClosed();
        database.checkActive();

        try {
            Map<String, Cursor.Getter<?>> columns = new HashMap<>();
            ResultSet resultSet = statement.executeQuery();
            ResultSetMetaData resultSetMetaData = resultSet.getMetaData();

            for (int i = 0, l = resultSetMetaData.getColumnCount(); i < l; i++) {
                String rawColumnName = resultSetMetaData.getColumnName(i + 1);

                if (rawColumnName.startsWith("\"") && rawColumnName.endsWith("\"")) {
                    rawColumnName = rawColumnName
                            .substring(1, rawColumnName.length() - 1)
                            .replace("\"\"", "\"");
                }

                String mappedColumnName = database.columnNameMapper.apply(rawColumnName).toLowerCase();
                String columnName = null;
                Class<?> columnType = null;

                for (String c : new String[]{ rawColumnName.toLowerCase(), mappedColumnName }) {
                    columnName = c;
                    columnType = query.columns().get(c);

                    if (columnType != null)
                        break;
                }

                if (columnType != null) {
                    Accessor<PreparedStatement, ResultSet, SQLException> accessor = database.accessorFactory.create(columnType);

                    if (accessor == null) {
                        throw new DatabaseException("No accessor is defined for type " + columnType + " (column " + rawColumnName + ")");
                    }

                    ResultSetCursor.ResultSetGetter getter = new ResultSetCursor.ResultSetGetter(accessor, resultSet, i);
                    columns.put(columnName, getter);

                    if (!columnName.equals(mappedColumnName))
                        columns.put(mappedColumnName, getter);
                }
            }

            return new ResultSetCursor(resultSet, columns);
        } catch (SQLException e) {
            throw new DatabaseException(e);
        }
    }

    @Override
    public long update() {
        checkNotClosed();
        database.checkActive();

        try {
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException(e);
        }
    }

    @Override
    public void execute() {
        checkNotClosed();
        database.checkActive();

        try {
            statement.execute();
        } catch (SQLException e) {
            throw new DatabaseException(e);
        }
    }

    @Override
    public Cursor insert() {
        checkNotClosed();
        database.checkActive();

        try {
            statement.executeUpdate();
            return database.generatedKeysSelector.selectGeneratedKeys(database, statement, query.keys());
        } catch (SQLException e) {
            throw new DatabaseException(e);
        }
    }

    @Override
    public <T> Binder<T> binder(String parameter) {
        checkNotClosed();
        Integer listSize = query.parameterListSizes().get(parameter.toLowerCase());

        if (listSize == null) {
            // Scalar parameter
            String parameterName = parameter.toLowerCase();
            Class<?> parameterType = query.parameters().get(parameterName);

            if (parameterType == null) {
                throw new DatabaseException("No type is defined for parameter " + parameter);
            }

            int[] parameterIndices = query.parameterIndices().get(parameterName);
            Accessor<PreparedStatement, ResultSet, SQLException> accessor = database.accessorFactory.create(parameterType);

            if (accessor == null) {
                throw new DatabaseException("No accessor is defined for type " + parameterType + " (parameter " + parameter + ")");
            }

            return new JdbcBinder<>(statement, accessor, parameterIndices);
        } else {
            // List parameter
            ArrayList<Binder<Object>> binders = new ArrayList<>(listSize);

            for (int i = 0; i < listSize; i++) {
                binders.add(binder(Query.parameterListIndexer(parameter, i)));
            }

            return new ListBinder<>(binders);
        }
    }

    @Override
    protected final boolean isClosed() {
        try {
            return super.isClosed() || statement.isClosed();
        } catch (SQLException e) {
            throw new DatabaseException(e);
        }
    }

    @Override
    public final void close() {
        try {
            statement.close();
        } catch (SQLException e) {
            throw new DatabaseException(e);
        }

        super.close();
    }

    private static final class JdbcBinder<T> implements Binder<T> {

        private final PreparedStatement statement;
        private final Accessor<PreparedStatement, ?, SQLException> accessor;
        private final int[] indices;

        private JdbcBinder(PreparedStatement statement, Accessor<PreparedStatement, ?, SQLException> accessor, int[] indices) {
            this.statement = statement;
            this.accessor = accessor;
            this.indices = indices;
        }

        @Override
        public void bind(T value) {
            try {
                for (int index : indices) accessor.set(statement, index, value);
            } catch (SQLException e) {
                throw new DatabaseException(e);
            }
        }

    }

}
