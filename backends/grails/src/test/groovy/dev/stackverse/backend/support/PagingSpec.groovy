package dev.stackverse.backend.support

import spock.lang.Specification

class PagingSpec extends Specification {
    def "resultPage reports empty and partial totals correctly"() {
        expect:
        Paging.resultPage(["one", "two"], 0, 20, 0L).totalPages == 0
        Paging.resultPage(["one", "two"], 1, 20, 41L).totalPages == 3
    }

    def "size uses the default for missing values and enforces the contract maximum"() {
        expect:
        Paging.size(null) == 20
        Paging.size("") == 20
        Paging.size("100") == 100

        when:
        Paging.size("101")

        then:
        ApiError tooLarge = thrown()
        tooLarge.status == 400
        tooLarge.errors[0].field == "size"
    }

    def "size and page reject invalid numeric values as validation problems"() {
        when:
        parser(value)

        then:
        ApiError error = thrown()
        error.status == 400
        error.errors[0].field == field

        where:
        value | parser                  || field
        "0"   | { Paging.size(it) }     || "size"
        "-1"  | { Paging.page(it) }     || "page"
        "abc" | { Paging.page(it) }     || "page"
    }
}
