package com.manhwanet.creator.application;

import com.manhwanet.creator.dto.CreateCreatorRequest;
import com.manhwanet.creator.dto.CreatorResponse;
import com.manhwanet.creator.domain.Creator;
import com.manhwanet.creator.domain.CreatorRepository;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.InvalidInputException;
import com.manhwanet.global.response.ApiError;
import com.manhwanet.global.response.PageResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CreatorService {

    private final CreatorRepository creatorRepository;

    public CreatorService(CreatorRepository creatorRepository) {
        this.creatorRepository = creatorRepository;
    }

    @Transactional
    public CreatorResponse register(CreateCreatorRequest request) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        String name = normalizeName("name", request.name(), errors);
        String originalName = request.originalName() == null
                ? null
                : normalizeName("originalName", request.originalName(), errors);
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        Creator creator = creatorRepository.save(Creator.create(name, originalName));
        return CreatorResponse.from(creator);
    }

    public CreatorResponse getCreator(Long creatorId) {
        return creatorRepository.findById(creatorId)
                .map(CreatorResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.CREATOR_NOT_FOUND));
    }

    public PageResponse<CreatorResponse> getCreators(Pageable pageable) {
        Page<Creator> page = creatorRepository.findAll(pageable);
        return PageResponse.of(page, page.getContent().stream().map(CreatorResponse::from).toList());
    }

    /**
     * 주어진 ID 중 존재하지 않는 작가 ID 를 반환한다. (쿼리 1회)
     */
    public Set<Long> findMissingIds(Collection<Long> creatorIds) {
        if (creatorIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> missing = new HashSet<>(creatorIds);
        creatorRepository.findExistingIds(Set.copyOf(creatorIds)).forEach(missing::remove);
        return missing;
    }

    /**
     * 작가 요약 정보를 ID 기준으로 일괄 조회한다. (쿼리 1회)
     */
    public Map<Long, CreatorSummary> getSummaries(Collection<Long> creatorIds) {
        if (creatorIds.isEmpty()) {
            return Map.of();
        }
        return creatorRepository.findAllById(Set.copyOf(creatorIds)).stream()
                .map(creator -> new CreatorSummary(creator.getId(), creator.getName(), creator.getOriginalName()))
                .collect(Collectors.toMap(CreatorSummary::id, Function.identity()));
    }

    private static String normalizeName(String field, String raw, List<ApiError.FieldError> errors) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            errors.add(new ApiError.FieldError(field, "공백만 입력할 수 없습니다."));
            return null;
        }
        if (value.codePointCount(0, value.length()) > Creator.NAME_MAX_LENGTH) {
            errors.add(new ApiError.FieldError(field, Creator.NAME_MAX_LENGTH + "자 이하여야 합니다."));
            return null;
        }
        return value;
    }
}
