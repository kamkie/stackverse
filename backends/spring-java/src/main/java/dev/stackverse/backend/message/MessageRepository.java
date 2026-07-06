package dev.stackverse.backend.message;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRepository extends JpaRepository<Message, UUID> {
    Message findByKeyAndLanguage(String key, String language);

    boolean existsByKeyAndLanguage(String key, String language);

    List<Message> findByLanguage(String language);

    @Query("select m from Message m where m.language in :languages order by m.key, m.language")
    List<Message> findByLanguageIn(@Param("languages") Collection<String> languages);

    @Query("select distinct m.language from Message m")
    Set<String> findDistinctLanguages();

    @Query(
        """
        select m from Message m
        where (:key is null or m.key = :key)
          and (:qLike is null or lower(m.key) like :qLike escape '\\' or lower(m.text) like :qLike escape '\\')
          and (:language is null or m.language = :language)
        order by m.key, m.language
        """
    )
    Page<Message> search(
        @Param("key") String key,
        @Param("qLike") String qLike,
        @Param("language") String language,
        Pageable pageable
    );
}
