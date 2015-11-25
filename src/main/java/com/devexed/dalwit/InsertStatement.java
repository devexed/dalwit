package com.devexed.dalwit;

/**
 * A statement that inserts rows in a database and returns the keys it generates, if any.
 */
public interface InsertStatement extends Statement, Closer<Cursor> {

    /**
     * Execute the statement on the database, returning a cursor over the keys generated by the insertion.
     * @param transaction The transaction on which to execute the statement.
     * @return The cursor over the generated keys.
     * @throws DatabaseException If the statement failed to execute.
     */
    Cursor insert(Transaction transaction);

    /**
     * Close a cursor opened by this statement. Also, closes all the cursor's resources. If <code>cursor</code> is null
     * this is a no-op.
     *
     * @param cursor The cursor to close.
     */
    @Override
    void close(Cursor cursor);

}
