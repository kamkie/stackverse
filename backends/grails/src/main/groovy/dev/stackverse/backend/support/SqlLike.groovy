package dev.stackverse.backend.support

class SqlLike {
    static String escape(String value) {
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
