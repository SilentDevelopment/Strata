package io.github.silentdevelopment.strata.durable.jdbc;


/**
 * SQL dialect settings for the generic JDBC durable layer.
 */
public enum JdbcDialect {

    GENERIC("BLOB"),
    H2("BLOB"),
    POSTGRESQL("BYTEA"),
    MYSQL("BLOB"),
    MARIADB("BLOB");

    private final String binaryType;

    JdbcDialect(String binaryType) {
        this.binaryType = binaryType;
    }

    public String binaryType() {
        return binaryType;
    }

}