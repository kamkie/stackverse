package dev.stackverse.backend.support

import spock.lang.Specification

class TimeSourceSpec extends Specification {
    def "timestamps are monotonic at microsecond precision"() {
        given:
        def source = new TimeSource()

        when:
        def first = source.now()
        def second = source.now()

        then:
        second.isAfter(first)
        first.nano % 1_000 == 0
        second.nano % 1_000 == 0
    }
}
