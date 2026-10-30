package backend.service.impl;

import backend.entity.HelpText;
import backend.repository.HelpTextRepository;
import backend.service.HelpTextService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HelpTextServiceImpl implements HelpTextService {

    private final HelpTextRepository helpTextRepository;

    @Override
    public long count() {
        return helpTextRepository.count();
    }

    @Override
    @Transactional
    public HelpText save(HelpText helpText) {
        return helpTextRepository.save(helpText);
    }

    @Override
    public List<HelpText> findAll() {
        return helpTextRepository.findAll();
    }

    @Override
    public boolean existsByFileName(String fileName) {
        return helpTextRepository.existsByFileName(fileName);
    }

    @Override
    public Map<String, String> findContentHashesByFileName() {
        Map<String, String> hashes = new HashMap<>();
        for (Object[] row : helpTextRepository.findFileNamesAndContentHashes()) {
            if (row[0] != null && !hashes.containsKey(String.valueOf(row[0]))) {
                hashes.put(String.valueOf(row[0]), row[1] == null ? null : String.valueOf(row[1]));
            }
        }
        return hashes;
    }

    @Override
    public Optional<HelpText> findByFileName(String fileName) {
        return helpTextRepository.findFirstByFileName(fileName);
    }

    @Override
    public List<HelpText> findAllWithoutFields() {
        return helpTextRepository.findAllWithoutFields();
    }

    @Override
    public List<HelpText> findByProduct(String product) {
        return helpTextRepository.findByProductIgnoreCase(product);
    }

    @Override
    public Optional<HelpText> findByTableName(String tableName) {
        return helpTextRepository.findFirstByTableNameIgnoreCase(tableName);
    }
}
