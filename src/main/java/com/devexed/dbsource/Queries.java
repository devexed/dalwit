package com.devexed.dbsource;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Helpers for building database engine independent queries.
 */
public final class Queries {

    /**
     * Builds a potentially complex query to handle multiple database types and versions.
     *
     * @return A query builder.
     */
    public static QueryBuilder builder() {
        return new QueryBuilder();
    }

    /**
     * Creates a simple query with the same SQL for all database types and versions.
     *
     * @param sql   The SQL of the query.
     * @param types The types of the selected columns and parameters in the query.
     * @return A simple query.
     */
    public static Query of(final String sql, final Map<String, Class<?>> types) {
        final Map<String, ArrayList<Integer>> queryParameterIndexes = new HashMap<String, ArrayList<Integer>>();
        final String querySql = parseParameterQuery(sql, queryParameterIndexes);

        return new Query() {

            @Override
            public String create(ReadonlyDatabase database, Map<String, ArrayList<Integer>> parameterIndexes) {
                parameterIndexes.putAll(queryParameterIndexes);
                return querySql;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> Class<T> typeOf(String name) {
                return (Class<T>) types.get(name);
            }

        };
    }

    /**
     * Creates a simple query with the same SQL for all database types and versions and with no parameters or columns.
     *
     * @param sql The SQL of the query.
     * @return A simple query.
     */
    public static Query of(final String sql) {
        return of(sql, Collections.<String, Class<?>>emptyMap());
    }

    /**
     * Concatenate multiple queries into one single query, including the types and parameters of all the concatenated.
     *
     * @param queries The queries to concatenate.
     * @return The concatenated query.
     */
    public static Query concat(final Iterable<Query> queries) {
        return new ConcatenatedQuery(queries) {

            @Override
            public String create(ReadonlyDatabase database, Map<String, ArrayList<Integer>> parameterIndexes) {
                StringBuilder queryBuilder = new StringBuilder();

                for (Query query : queries) queryBuilder.append(query.create(database, parameterIndexes));

                return queryBuilder.toString();
            }

        };
    }

    public static Query concat(Query... queries) {
        return concat(new ArrayIterable<Query>(queries));
    }

    /**
     * Includes the created SQL from the argument queries in the specified query at %s in the query using
     * {@link String#format}.
     *
     * @param query The query to format.
     * @param args  The queries to include.
     * @return The formatted query.
     */
    public static Query format(final Query query, final Iterable<Query> args) {
        return new ConcatenatedQuery(new ArrayList<Query>() {{
            add(query);
            for (Query arg : args) add(arg);
        }}) {

            @Override
            public String create(ReadonlyDatabase database, Map<String, ArrayList<Integer>> parameterIndexes) {
                ArrayList<String> stringArgList = new ArrayList<String>();

                for (Query arg : args) stringArgList.add(arg.create(database, parameterIndexes));

                String[] stringArgs = new String[stringArgList.size()];
                stringArgList.toArray(stringArgs);

                return String.format(query.create(database, parameterIndexes), (Object[]) stringArgs);
            }

        };
    }

    public static Query format(Query query, Query... args) {
        return format(query, new ArrayIterable<Query>(args));
    }

    /**
     * <p>Parse a query for named parameter named in the form of a colon (:) followed by
     * a java identifier and insert a ? at these occurrences.
     * Additionally map the replaced occurrences to unique sequential indexes starting
     * at zero and store the result in the provided parameter map.</p>
     * <p/>
     * <p>For example <code>SELECT name FROM person WHERE name = :name AND (mother_surname = :surname OR father_surname
     * = :surname)</code> becomes <code>SELECT name FROM person WHERE name = ? AND (mother_surname = ? OR father_surname
     * = ?)</code> and the parameter index map will contain the values <code>{"name": [0], "surname": [1, 2]}</code></p>
     *
     * @param query            The query to parse.
     * @param parameterIndexes The map which to fill with parameter indexes.
     * @return The query with the named parameters replaced with ?.
     */
    public static String parseParameterQuery(String query, Map<String, ArrayList<Integer>> parameterIndexes) {
        StringBuilder queryBuilder = new StringBuilder();
        StringBuilder parameterBuilder = new StringBuilder();
        int parameterIndex = 0;

        /* Various ranges where parameters aren't parsed. Handling escaped characters inside the ranges is unnecessary
           because SQL handles escaping by doubling the character. The parser will simply immediately begin a new range
           again after closing the previous when a doubled range end is encountered. For example 'abc''def' will be
           understood as two separate strings. */
        EscapedRange[] escapedRanges = new EscapedRange[]{
                new EscapedRange("--", "\n"),
                new EscapedRange("'"),
                new EscapedRange("\""),
                new EscapedRange("[", "]"),
                new EscapedRange("`")
        };

        queryLoop:
        for (int i = 0, l = query.length(); i < l; ) {
            // Check for escaped ranges in which query parameters can't appear.
            for (EscapedRange range : escapedRanges) {
                if (range.inRange) {
                    // Is this the end of an escaped range?
                    int length = range.end.length();
                    int endIndex = i + range.end.length();

                    if (endIndex <= l && query.substring(i, endIndex).equals(range.end)) {
                        range.inRange = false;
                        queryBuilder.append(range.end);
                        i += length;
                    } else {
                        queryBuilder.append(query.charAt(i));
                        i++;
                    }

                    continue queryLoop;
                } else {
                    // Is this the start of an escaped range?
                    int length = range.start.length();
                    int endIndex = i + length;

                    if (endIndex <= l && query.substring(i, endIndex).equals(range.start)) {
                        range.inRange = true;
                        queryBuilder.append(range.start);
                        i += length;

                        continue queryLoop;
                    }
                }
            }

            char c = query.charAt(i);

            if (c == '?')
                throw new IllegalArgumentException("Illegal character '?'. Only named parameters are allowed.");

            if (c == ':') {
                i++;

                // Empty parameter at end.
                if (i == l)
                    throw new IllegalArgumentException("Empty parameter at query end.");

                char ps = query.charAt(i);

                // Illegal parameter start.
                if (!Character.isJavaIdentifierStart(ps))
                    throw new IllegalArgumentException("Character " + ps + " is not a valid parameter (character position " + i + ").");

                // Build parameter string.
                parameterBuilder.setLength(0);
                parameterBuilder.append(ps);
                i++;

                for (; i < l; i++) {
                    char p = query.charAt(i);
                    if (!Character.isJavaIdentifierPart(query.charAt(i))) break;
                    parameterBuilder.append(p);
                }

                // Add parameter to parameter indexes map and substitute it with a ? in the resulting query.
                String parameter = parameterBuilder.toString();
                ArrayList<Integer> indexes = parameterIndexes.get(parameter);

                if (indexes == null) {
                    indexes = new ArrayList<Integer>();
                    parameterIndexes.put(parameter, indexes);
                }

                indexes.add(parameterIndex);
                parameterIndex++;
                queryBuilder.append('?');

                continue;
            }

            queryBuilder.append(c);
            i++;
        }

        return queryBuilder.toString();
    }

    private static final class EscapedRange {
        final String start;
        final String end;
        boolean inRange;

        EscapedRange(String start, String end) {
            this.start = start;
            this.end = end;
        }

        EscapedRange(String startAndEnd) {
            this(startAndEnd, startAndEnd);
        }
    }

    private static abstract class QueryPermutation {

        final String query;

        QueryPermutation(String query) {
            this.query = query;
        }

        final String getQuery() {
            return query;
        }

        abstract boolean matchesDatabase(ReadonlyDatabase database);

    }

    private static class TypeQueryPermutation extends QueryPermutation {

        final String type;

        private TypeQueryPermutation(String type, String query) {
            super(query);
            this.type = type;
        }

        @Override
        boolean matchesDatabase(ReadonlyDatabase database) {
            return type.equals(database.getType());
        }

    }

    private static final class PatternQueryPermutation extends TypeQueryPermutation {

        final Pattern version;

        private PatternQueryPermutation(String type, Pattern version, String query) {
            super(type, query);
            this.version = version;
        }

        @Override
        public boolean matchesDatabase(ReadonlyDatabase database) {
            return super.matchesDatabase(database) && version.matcher(database.getVersion()).find();

        }

    }

    private static final class MinimumVersionQueryPermutation extends TypeQueryPermutation {

        final int[] version;

        private MinimumVersionQueryPermutation(String type, int[] version, String query) {
            super(type, query);
            this.version = version;
        }

        @Override
        public boolean matchesDatabase(ReadonlyDatabase database) {
            if (!super.matchesDatabase(database)) return false;

            int versionIndex = 0;

            for (String part : database.getType().split("\\.")) {
                if (versionIndex >= version.length) break;

                try {
                    if (Integer.parseInt(part) < version[versionIndex]) return false;
                } catch (Exception e) {
                    return false;
                }
            }

            return true;
        }

    }

    /**
     * A builder for complex queries.
     */
    public static final class QueryBuilder {

        private final ArrayList<QueryPermutation> permutations = new ArrayList<QueryPermutation>();
        private String defaultQuery = null;

        private QueryBuilder() {
        }

        /**
         * Add a query permutation only applicable to a certain database type.
         *
         * @param type  The database type for which the query is used.
         * @param query The query to use for this permutation.
         */
        public QueryBuilder forType(String type, String query) {
            permutations.add(new TypeQueryPermutation(type, query));
            return this;
        }

        /**
         * Add a query permutation only applicable to a certain database type whose version string matches a certain
         * regular expression.
         *
         * @param type           The database type for which the query is used.
         * @param versionPattern The regular expression pattern to match against the database version.
         * @param query          The query to use for this permutation.
         */
        public QueryBuilder forVersion(String type, Pattern versionPattern, String query) {
            permutations.add(new PatternQueryPermutation(type, versionPattern, query));
            return this;
        }

        /**
         * Add a query permutation only applicable to a certain database type whose version string interpreted as a
         * series of point-separated numbers is, in order, larger or equal to each of the integers in the minimum
         * version parameter. E.g. a specified minimum version of {2, 5, 8} matches the version string "2.5.9" and "3.0"
         * but not "1.2" or "2.5.3".
         *
         * @param type           The database type for which the query is used.
         * @param minimumVersion The regular expression pattern to match against the database version.
         * @param query          The query to use for this permutation.
         */
        public QueryBuilder forVersion(String type, int[] minimumVersion, String query) {
            permutations.add(new MinimumVersionQueryPermutation(type, minimumVersion, query));
            return this;
        }

        /**
         * Supplies the fallback query to use when no other permutation matches.
         *
         * @param query The query to use as fallback.
         */
        public QueryBuilder forDefault(String query) {
            defaultQuery = query;
            return this;
        }

        /**
         * Build the query with no selected columns or parameters.
         */
        public Query build() {
            return build(Collections.<String, Class<?>>emptyMap());
        }

        /**
         * Build the query with selected columns and parameters types.
         *
         * @param types The types of the selected columns and parameters in the query.
         */
        public Query build(final Map<String, Class<?>> types) {
            return new Query() {

                @Override
                public String create(ReadonlyDatabase database, Map<String, ArrayList<Integer>> parameterIndexes) {
                    String query = defaultQuery;

                    for (QueryPermutation permutation : permutations) {
                        if (permutation.matchesDatabase(database)) {
                            query = permutation.query;
                            break;
                        }
                    }

                    if (query == null)
                        throw new DatabaseException("No applicable query permutation found for database " + database);

                    return query;
                }

                @Override
                @SuppressWarnings("unchecked")
                public <T> Class<T> typeOf(String name) {
                    return (Class<T>) types.get(name);
                }

            };
        }

    }

    private static abstract class ConcatenatedQuery implements Query {

        private final Iterable<Query> queries;

        private ConcatenatedQuery(Iterable<Query> queries) {
            this.queries = queries;
        }

        @Override
        @SuppressWarnings("unchecked")
        public final <T> Class<T> typeOf(String name) {
            Set<Class<?>> types = new HashSet<Class<?>>();

            for (Query query : queries) {
                Class<?> type = query.typeOf(name);

                if (type != null) types.add(type);
            }

            if (types.isEmpty()) return null;
            else if (types.size() > 0)
                throw new DatabaseException("Multiple different types are defined for parameter with name " + name +
                        ": " + types);

            return (Class<T>) types.iterator().next();
        }

    }

    private static final class ArrayIterable<E> implements Iterable<E> {

        private final E[] array;

        private ArrayIterable(E[] array) {
            this.array = array;
        }

        @Override
        public Iterator<E> iterator() {
            return new ArrayIterator<E>(array);
        }

    }

    private static final class ArrayIterator<E> implements Iterator<E> {

        private final E[] array;
        private int position = 0;

        private ArrayIterator(E[] array) {
            this.array = array;
        }

        @Override
        public boolean hasNext() {
            return position < array.length;
        }

        @Override
        public E next() {
            return array[position];
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

    }

}
