package dev.stackverse.backend.message

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface MessageRepository : JpaRepository<Message, UUID> {

    fun findByKeyAndLanguage(key: String, language: String): Message?

    fun existsByKeyAndLanguage(key: String, language: String): Boolean

    fun findByLanguage(language: String): List<Message>

    fun findByLanguageIn(languages: Collection<String>): List<Message>

    @Query("select distinct m.language from Message m")
    fun findDistinctLanguages(): Set<String>

    @Query(
        """
        select m from Message m
        where (:key is null or m.key = :key) and (:language is null or m.language = :language)
        order by m.key, m.language
        """,
    )
    fun search(key: String?, language: String?, pageable: Pageable): Page<Message>
}
