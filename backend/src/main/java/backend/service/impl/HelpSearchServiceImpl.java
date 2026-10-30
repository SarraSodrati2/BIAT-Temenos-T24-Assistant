package backend.service.impl;

import backend.entity.HelpField;
import backend.repository.HelpFieldRepository;
import backend.service.HelpSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * HELP_FIELD est un référentiel statique (peuplé une fois par {@code HelpFieldImportRunner},
 * jamais modifié ensuite) : les résultats de recherche sont mis en cache indéfiniment pour
 * accélérer les questions répétées/similaires du chatbot sans re-scanner 160k+ lignes à chaque fois.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HelpSearchServiceImpl implements HelpSearchService {

    private final HelpFieldRepository helpFieldRepository;

    @Override
    @Cacheable("helpFieldByFieldName")
    public List<HelpField> findByFieldName(String fieldName) {
        return helpFieldRepository.findByFieldNameIgnoreCase(fieldName);
    }

    @Override
    @Cacheable("helpFieldByTableName")
    public List<HelpField> findByTableName(String tableName) {
        return helpFieldRepository.findByTableNameIgnoreCase(tableName);
    }

    @Override
    @Cacheable("helpFieldSearch")
    public List<HelpField> search(String keyword) {
        return helpFieldRepository.searchByKeyword(keyword);
    }

    @Override
    @Cacheable("helpFieldSearchInProduct")
    public List<HelpField> searchInProduct(String keyword, String product) {
        return helpFieldRepository.searchByKeywordInProduct(keyword, product);
    }

    @Override
    public List<HelpField> findTopFields(String keyword) {
        List<HelpField> exactFieldMatches = findByFieldName(keyword);
        return exactFieldMatches.isEmpty() ? search(keyword) : exactFieldMatches;
    }
}
