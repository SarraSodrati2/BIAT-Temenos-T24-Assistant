package backend.service.impl;

import backend.entity.HelpField;
import backend.repository.HelpFieldRepository;
import backend.service.HelpFieldService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HelpFieldServiceImpl implements HelpFieldService {

    private final HelpFieldRepository helpFieldRepository;

    @Override
    public long count() {
        return helpFieldRepository.count();
    }

    @Override
    @Transactional
    public List<HelpField> saveAll(List<HelpField> helpFields) {
        return helpFieldRepository.saveAll(helpFields);
    }

    @Override
    @Transactional
    public int deleteByHelpTextId(Long helpTextId) {
        return helpFieldRepository.deleteByHelpTextId(helpTextId);
    }

    @Override
    public List<HelpField> findByTableName(String tableName) {
        return helpFieldRepository.findByTableNameIgnoreCase(tableName);
    }
}
