package dev.stackverse.backend.support

import spock.lang.Specification

class SqlLikeSpec extends Specification {
    def "escape protects wildcard and escape characters for LIKE filters"() {
        expect:
        SqlLike.escape("""100%_match\\tail""") == """100\\%\\_match\\\\tail"""
    }
}
